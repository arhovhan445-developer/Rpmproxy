package com.example.rpmproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class Mode(val name: String, val up: Double, val down: Double, val curve: Double, val maxRpm: Double)

/**
 * Прокси ELM327 для Rev Heads (TCP :35000).
 * Источник данных: машина через Bluetooth ELM327 ИЛИ демо-режим (акселерометр + GPS телефона).
 * Rev Heads получает: 010C RPM (виртуальная коробка), 010D скорость, 0111 дроссель, 0104 нагрузка.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 35000
        const val IDLE_RPM = 850.0
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        val MODES = listOf(
            Mode("Мягкий", 0.06, 0.04, 1.3, 4500.0),
            Mode("Обычный", 0.15, 0.10, 1.0, 5500.0),
            Mode("Резкий", 0.30, 0.20, 0.8, 6500.0),
            Mode("Спорт", 0.50, 0.35, 0.65, 7000.0)
        )

        // оборотов двигателя на 1 км/ч для каждой передачи (1..6)
        val GEAR_RATIO = doubleArrayOf(120.0, 70.0, 48.0, 36.0, 28.0, 22.0)

        @Volatile var modeIndex = 1
        @Volatile var gearbox = true
        @Volatile var running = false
        @Volatile var carConnected = false
        @Volatile var status = "Остановлен"
        @Volatile var pedal = 0.0
        @Volatile var rawPedal = 0.0
        @Volatile var pedalMin = 0.20
        @Volatile var pedalMax = 0.83
        @Volatile var sampleMs = 200.0
        @Volatile var fastSuffix = true
        @Volatile var rpm = IDLE_RPM
        @Volatile var speedSample = 0.0
        @Volatile var speedKmh = 0.0
        @Volatile var accel = 0.0
        @Volatile var gear = 1
        @Volatile var pid = "0149"
        @Volatile var lastRaw = ""
        val clientCount = AtomicInteger(0)

        // пауза для Rev Heads: до этого момента (elapsedRealtime) скорость отдаётся как 0
        @Volatile var holdUntilMs = 0L

        // демо-режим (без ELM)
        @Volatile var demo = false
        @Volatile var demoPedal = 0.0      // виртуальная педаль из ускорения 0..1
        @Volatile var demoAF = 0.0         // ускорение вперёд, м/с²
        @Volatile var demoGpsKmh = 0.0
        @Volatile var demoAxisOk = false   // направление движения в телефоне определено
        @Volatile var demoGpsMs = 0L

        // журнал запросов Rev Heads
        private val seenCount = LinkedHashMap<String, Int>()
        private val seenLast = HashMap<String, String>()

        fun noteCmd(cmd: String, resp: String) = synchronized(seenCount) {
            if (seenCount.size < 200 || seenCount.containsKey(cmd)) {
                seenCount[cmd] = (seenCount[cmd] ?: 0) + 1
                seenLast[cmd] = resp
            }
        }

        fun clearLog() = synchronized(seenCount) { seenCount.clear(); seenLast.clear() }

        fun cmdLog(): String = synchronized(seenCount) {
            seenCount.entries.take(25).joinToString("\n") {
                "${it.key} x${it.value} -> ${seenLast[it.key]?.take(40)}"
            }
        }
    }

    private val lock = Any()
    private val interpLock = Any()
    private var pFrom = 0.0
    private var pTo = 0.0
    private var tSample = System.nanoTime()
    private val cache = HashMap<String, Pair<Long, String>>()

    private var carIn: InputStream? = null
    private var carOut: OutputStream? = null
    private var btSocket: BluetoothSocket? = null
    private var server: ServerSocket? = null

    private var demoThread: HandlerThread? = null
    private var demoImpl: DemoSensors? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val demoMode = intent?.getBooleanExtra("demo", false) ?: false
        startForegroundCompat(demoMode)
        val mac = intent?.getStringExtra("mac")
        if (!demoMode && mac == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        demo = demoMode
        rpm = IDLE_RPM
        pedal = 0.0
        demoPedal = 0.0
        speedSample = 0.0
        speedKmh = 0.0
        accel = 0.0
        gear = 1
        holdUntilMs = 0L
        clearLog()
        synchronized(interpLock) { pFrom = 0.0; pTo = 0.0; tSample = System.nanoTime() }
        thread { physicsLoop() }
        if (demoMode) {
            status = "Демо-режим: ожидание GPS..."
            thread { startServer() }
            startDemo()
        } else {
            thread { runProxy(mac!!) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        carConnected = false
        demo = false
        status = "Остановлен"
        pedal = 0.0
        stopDemo()
        try { server?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundCompat(demoMode: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("proxy", "RPM proxy", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "proxy")
            .setContentTitle(if (demoMode) "OBD RPM proxy: демо-режим" else "OBD RPM proxy работает")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        val type = if (demoMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        startForeground(1, n, type)
    }

    // ---------------- сервер для Rev Heads ----------------

    private fun startServer(): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            thread { serveClients(ss) }
            true
        } catch (e: Exception) {
            status = "Не удалось открыть порт $PORT: ${e.message}"
            running = false
            stopSelf()
            false
        }
    }

    // ---------------- режим с ELM ----------------

    @SuppressLint("MissingPermission")
    private fun runProxy(mac: String) {
        if (!startServer()) return

        val bt = BluetoothAdapter.getDefaultAdapter()
        while (running) {
            try {
                status = "Подключение к адаптеру..."
                val s = bt.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SPP)
                btSocket = s
                s.connect()
                carIn = s.inputStream
                carOut = s.outputStream
                for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) carCmd(c)
                carConnected = true
                status = "Адаптер подключён"
                pollLoop()
            } catch (e: Exception) {
                if (running) status = "Ошибка адаптера: ${e.message}. Повтор через 3 с"
            } finally {
                carConnected = false
                try { btSocket?.close() } catch (_: Exception) {}
                carIn = null
                carOut = null
            }
            if (running) Thread.sleep(3000)
        }
    }

    private fun carCmd(cmd: String): String = synchronized(lock) {
        val o = carOut ?: return "NO DATA"
        val i = carIn ?: return "NO DATA"
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0 || c == '>'.code) break
            sb.append(c.toChar())
        }
        sb.toString().replace("\n", "\r").split("\r")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd }
            .joinToString("\r")
    }

    private fun pollLoop() {
        val rePedal = Regex("41(?:49|5A)([0-9A-F]{2})")
        val reSpeed = Regex("410D([0-9A-F]{2})")
        var fails = 0
        var n = 0
        while (running && carConnected) {
            n++
            if (n % 3 == 0) {
                val r = carCmd("010D" + if (fastSuffix) "1" else "")
                if (fastSuffix && r.contains("?")) { fastSuffix = false; continue }
                reSpeed.find(r.replace(" ", ""))?.let {
                    speedSample = it.groupValues[1].toInt(16).toDouble()
                }
                continue
            }
            val raw = carCmd(pid + if (fastSuffix) "1" else "")
            lastRaw = raw
            if (fastSuffix && raw.contains("?")) { fastSuffix = false; continue }
            val m = rePedal.find(raw.replace(" ", ""))
            if (m != null) {
                onSample(m.groupValues[1].toInt(16) / 255.0)
                fails = 0
            } else {
                if (++fails >= 5) {
                    pid = if (pid == "0149") "015A" else "0149"
                    fails = 0
                }
                Thread.sleep(50)
            }
        }
    }

    private fun onSample(raw: Double) {
        rawPedal = raw
        if (raw < pedalMin) pedalMin = raw
        if (raw > pedalMax) pedalMax = raw
        val range = (pedalMax - pedalMin).coerceAtLeast(0.05)
        val n = ((raw - pedalMin) / range).coerceIn(0.0, 1.0)
        val now = System.nanoTime()
        synchronized(interpLock) {
            val dtMs = (now - tSample) / 1e6
            sampleMs = sampleMs * 0.7 + dtMs.coerceIn(40.0, 600.0) * 0.3
            pFrom = interp(now)
            pTo = n
            tSample = now
        }
    }

    private fun interp(now: Long): Double {
        val f = ((now - tSample) / 1e6 / sampleMs).coerceIn(0.0, 1.0)
        return pFrom + (pTo - pFrom) * f
    }

    // ---------------- демо-режим: акселерометр + GPS ----------------

    @SuppressLint("MissingPermission")
    private fun startDemo() {
        val ht = HandlerThread("demo").also { it.start() }
        demoThread = ht
        val d = DemoSensors()
        demoImpl = d
        val handler = Handler(ht.looper)

        val sm = getSystemService(SensorManager::class.java)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            status = "В телефоне нет акселерометра"
        } else {
            sm.registerListener(d, acc, SensorManager.SENSOR_DELAY_GAME, handler)
        }

        val lm = getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            status = "Включите геолокацию (GPS) в настройках телефона"
        } else {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, d, ht.looper)
        }
    }

    private fun stopDemo() {
        val d = demoImpl ?: return
        try { getSystemService(SensorManager::class.java).unregisterListener(d) } catch (_: Exception) {}
        try { getSystemService(LocationManager::class.java).removeUpdates(d) } catch (_: Exception) {}
        demoThread?.quitSafely()
        demoImpl = null
        demoThread = null
    }

    /**
     * Скорость: GPS (точно, ~1 Гц) + акселерометр между замерами GPS.
     * Направление "вперёд" в телефоне определяется само: коррелируем горизонтальное
     * ускорение телефона с изменением скорости по GPS (нужны 1-2 разгона/торможения).
     * Телефон должен быть закреплён в машине неподвижно.
     */
    private inner class DemoSensors : SensorEventListener, LocationListener {
        private val g = DoubleArray(3)
        private var gInit = false
        private var lastT = 0L
        private val sAxis = DoubleArray(3)
        private val f = DoubleArray(3)
        private var fValid = false
        private val aSum = DoubleArray(3)
        private var aN = 0
        private var vEst = 0.0        // оценка скорости, м/с
        private var vGps = 0.0
        private var lastFixMs = 0L
        private var gpsAccel = 0.0    // ускорение из GPS (запасной вариант)
        private var aF = 0.0          // ускорение вперёд (сглаженное)

        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val a0 = e.values[0].toDouble()
            val a1 = e.values[1].toDouble()
            val a2 = e.values[2].toDouble()
            val t = e.timestamp
            if (!gInit) {
                g[0] = a0; g[1] = a1; g[2] = a2
                gInit = true; lastT = t
                return
            }
            val dt = (t - lastT) / 1e9
            lastT = t
            if (dt <= 0.0 || dt > 0.5) return

            // гравитация: медленный фильтр, остаток - линейное ускорение
            val ag = Math.exp(-dt / 4.0)
            g[0] = ag * g[0] + (1 - ag) * a0
            g[1] = ag * g[1] + (1 - ag) * a1
            g[2] = ag * g[2] + (1 - ag) * a2
            val l0 = a0 - g[0]
            val l1 = a1 - g[1]
            val l2 = a2 - g[2]
            val gn = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (gn < 1.0) return
            val u0 = g[0] / gn
            val u1 = g[1] / gn
            val u2 = g[2] / gn
            val dot = l0 * u0 + l1 * u1 + l2 * u2
            val h0 = l0 - dot * u0      // горизонтальная часть ускорения
            val h1 = l1 - dot * u1
            val h2 = l2 - dot * u2
            aSum[0] += h0; aSum[1] += h1; aSum[2] += h2
            aN++

            val aUse: Double
            if (fValid) {
                aUse = h0 * f[0] + h1 * f[1] + h2 * f[2]
            } else {
                gpsAccel *= Math.exp(-dt / 0.7)
                aUse = gpsAccel
            }
            aF += (aUse - aF) * (1.0 - Math.exp(-dt / 0.15))
            vEst += aUse * dt

            // GPS пропал надолго: скорость медленно затухает, чтобы не "уплывала"
            if (lastFixMs != 0L && SystemClock.elapsedRealtime() - lastFixMs > 5000) {
                vEst -= vEst * (1.0 - Math.exp(-dt / 5.0))
            }
            if (vEst < 0.0) vEst = 0.0
            publish()
        }

        private fun publish() {
            val kmh = vEst * 3.6
            speedSample = kmh
            var t = if (Math.abs(aF) < 0.15) 0.0 else aF / 2.5   // 2.5 м/с² = педаль в пол
            if (kmh > 5.0) t += 0.08                              // лёгкий газ на ходу
            demoPedal = t.coerceIn(0.0, 1.0)
            demoAF = aF
        }

        override fun onLocationChanged(loc: Location) {
            if (!loc.hasSpeed()) return
            val v = loc.speed.toDouble()
            val now = SystemClock.elapsedRealtime()
            if (lastFixMs != 0L) {
                val dt = (now - lastFixMs) / 1000.0
                if (dt > 0.05) {
                    val dvdt = (v - vGps) / dt
                    gpsAccel += (dvdt - gpsAccel) * 0.6
                    if (aN > 0 && Math.abs(dvdt) > 0.5) {
                        val inv = 1.0 / aN
                        for (i in 0..2) sAxis[i] = sAxis[i] * 0.97 + aSum[i] * inv * dvdt
                        val n = Math.sqrt(sAxis[0] * sAxis[0] + sAxis[1] * sAxis[1] + sAxis[2] * sAxis[2])
                        if (n > 1.0) {
                            for (i in 0..2) f[i] = sAxis[i] / n
                            fValid = true
                        }
                    }
                }
            }
            for (i in 0..2) aSum[i] = 0.0
            aN = 0
            lastFixMs = now
            vGps = v
            vEst = if (fValid) vEst + (v - vEst) * 0.5 else v
            demoGpsKmh = v * 3.6
            demoAxisOk = fValid
            demoGpsMs = now
            status = "Демо-режим: GPS ок"
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, st: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            status = "GPS выключен"
        }
    }

    // ---------------- физика: скорость, ускорение, передача, RPM ----------------

    private fun physicsLoop() {
        var last = System.nanoTime()
        var lastSpeed = 0.0
        var shiftUntil = 0L
        var lastShift = 0L
        while (running) {
            Thread.sleep(20)
            val now = System.nanoTime()
            val dt = (now - last) / 1e9
            last = now
            val nowMs = now / 1_000_000

            val p = if (demo) demoPedal
            else if (carConnected) synchronized(interpLock) { interp(now) }
            else 0.0
            pedal = p

            val spTarget = if (demo || carConnected) speedSample else 0.0
            speedKmh += (spTarget - speedKmh) * (1.0 - Math.exp(-dt / 0.3))
            val rawAcc = (speedKmh - lastSpeed) / 3.6 / dt
            lastSpeed = speedKmh
            accel += (rawAcc - accel) * (1.0 - Math.exp(-dt / 0.4))

            val md = MODES[modeIndex]
            val target: Double
            if (gearbox) {
                val ratio = GEAR_RATIO
                var g = gear.coerceIn(1, ratio.size)
                if (speedKmh < 5.0) {
                    g = 1
                } else if (nowMs >= shiftUntil && nowMs - lastShift > 700) {
                    val up = 2300.0 + p * (md.maxRpm * 0.8 - 2300.0)
                    val down = 1100.0 + p * 1300.0
                    val cur = speedKmh * ratio[g - 1]
                    if (g < ratio.size && cur > up) {
                        g++; shiftUntil = nowMs + 350; lastShift = nowMs
                    } else if (g > 1 && cur < down && speedKmh * ratio[g - 2] < up * 0.92) {
                        g--; shiftUntil = nowMs + 300; lastShift = nowMs
                    }
                }
                gear = g
                val pe = if (nowMs < shiftUntil) 0.0 else p
                val shaped = Math.pow(pe, md.curve)
                val launch = IDLE_RPM + shaped * (Math.min(md.maxRpm, 3500.0) - IDLE_RPM)
                val geared = speedKmh * ratio[g - 1] + shaped * 600.0
                val w = (speedKmh / 15.0).coerceIn(0.0, 1.0)
                target = (launch * (1.0 - w) + geared * w).coerceIn(IDLE_RPM, md.maxRpm)
            } else {
                gear = 0
                target = IDLE_RPM + Math.pow(p, md.curve) * (md.maxRpm - IDLE_RPM)
            }

            var base = if (target > rpm) md.up else md.down
            if (nowMs < shiftUntil && target < rpm) base = Math.max(base, 0.3)
            val k = 1.0 - Math.pow(1.0 - base, dt / 0.08)
            rpm += (target - rpm) * k
        }
    }

    private fun forward(cmd: String): String {
        val cacheable = cmd.startsWith("01")
        if (cacheable) {
            val now = SystemClock.elapsedRealtime()
            synchronized(cache) {
                val e = cache[cmd]
                if (e != null && now - e.first < 400) return e.second
            }
        }
        val r = try { carCmd(cmd) } catch (e: Exception) { "NO DATA" }
        if (cacheable) synchronized(cache) { cache[cmd] = SystemClock.elapsedRealtime() to r }
        return r
    }

    // ---------------- TCP-сервер для Rev Heads ----------------

    private fun serveClients(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept()
                thread { handleClient(client) }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handleClient(s: Socket) {
        clientCount.incrementAndGet()
        try {
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            val sb = StringBuilder()
            while (running) {
                val c = inp.read()
                if (c < 0) break
                if (c == '\r'.code || c == '\n'.code) {
                    val cmd = sb.toString().trim().replace(" ", "").uppercase()
                    sb.setLength(0)
                    if (cmd.isEmpty()) continue
                    val resp = answer(cmd)
                    noteCmd(cmd, resp)
                    out.write(("$resp\r\r>").toByteArray()); out.flush()
                } else sb.append(c.toChar())
            }
        } catch (_: Exception) {
        } finally {
            clientCount.decrementAndGet()
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun hex(v: Double): Int = Math.round(v * 255.0).toInt().coerceIn(0, 255)

    private fun load(): Double =
        (0.12 + 0.55 * pedal + 0.33 * (accel / 3.0).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)

    private fun reportedSpeed(): Int =
        if (SystemClock.elapsedRealtime() < holdUntilMs) 0
        else Math.round(speedKmh).toInt().coerceIn(0, 255)

    private fun pidData(p: String): String? = when (p) {
        "00" -> "10 18 80 01"
        "20" -> "00 00 00 01"
        "40" -> "0A 90 00 40"
        "04" -> "%02X".format(hex(load()))
        "0C" -> {
            val raw = (rpm * 4).toInt().coerceIn(0, 65535)
            "%02X %02X".format((raw shr 8) and 0xFF, raw and 0xFF)
        }
        "0D" -> "%02X".format(reportedSpeed())
        "11", "45", "47", "49", "4C", "5A" -> "%02X".format(hex(pedal))
        else -> null
    }

    private fun atAnswer(cmd: String): String = when (cmd) {
        "ATZ", "ATI" -> "ELM327 v1.5"
        "AT@1" -> "OBDII to RS232 Interpreter"
        "ATRV" -> "12.6V"
        "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        "ATDPN" -> "A6"
        else -> "OK"
    }

    private fun answer(cmd0: String): String {
        var cmd = cmd0
        if (cmd.startsWith("AT")) return atAnswer(cmd)
        if (cmd.startsWith("01") && cmd.length % 2 == 1) cmd = cmd.dropLast(1)
        if (cmd.startsWith("01") && cmd.length >= 4) {
            val sb = StringBuilder("41")
            for (id in cmd.substring(2).chunked(2)) {
                val d = pidData(id) ?: return "NO DATA"
                sb.append(' ').append(id).append(' ').append(d)
            }
            return sb.toString()
        }
        return if (demo) "NO DATA" else forward(cmd)
    }
}
