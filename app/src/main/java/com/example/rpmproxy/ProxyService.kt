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
 * Прокси ELM327: Volt <-> (Bluetooth) <-> сервис <-> (TCP :35000) <-> Rev Heads.
 * 010C (RPM) подменяется значением, рассчитанным от педали газа.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 35000
        const val IDLE_RPM = 850.0
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // up/down - скорость роста/падения оборотов (0..1 за шаг 80 мс),
        // curve < 1 делает педаль чувствительнее, > 1 мягче
        val MODES = listOf(
            Mode("Мягкий", 0.06, 0.04, 1.3, 4500.0),
            Mode("Обычный", 0.15, 0.10, 1.0, 5500.0),
            Mode("Резкий", 0.30, 0.20, 0.8, 6500.0),
            Mode("Спорт", 0.50, 0.35, 0.65, 7000.0)
        )

        @Volatile var modeIndex = 1
        @Volatile var running = false
        @Volatile var carConnected = false
        @Volatile var status = "Остановлен"
        @Volatile var pedal = 0.0          // сглаженная, откалиброванная 0..1
        @Volatile var rawPedal = 0.0       // сырое значение машины 0..1
        @Volatile var pedalMin = 0.20      // сырое значение при отпущенной педали
        @Volatile var pedalMax = 0.83      // сырое значение при педали в пол
        @Volatile var sampleMs = 200.0     // средний интервал между замерами
        @Volatile var fastSuffix = true    // ускоренный запрос ELM327 ("01491")
        @Volatile var rpm = IDLE_RPM
        @Volatile var pid = "0149"
        @Volatile var lastRaw = ""
        val clientCount = AtomicInteger(0)
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

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val mac = intent?.getStringExtra("mac")
        if (mac == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        rpm = IDLE_RPM
        pedal = 0.0
        synchronized(interpLock) { pFrom = 0.0; pTo = 0.0; tSample = System.nanoTime() }
        thread { physicsLoop() }
        thread { runProxy(mac) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        carConnected = false
        status = "Остановлен"
        pedal = 0.0
        try { server?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("proxy", "RPM proxy", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "proxy")
            .setContentTitle("OBD RPM proxy работает")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    // ---------------- основной цикл ----------------

    @SuppressLint("MissingPermission")
    private fun runProxy(mac: String) {
        // 1. Сначала поднимаем сервер для Rev Heads
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            thread { serveClients(ss) }
        } catch (e: Exception) {
            status = "Не удалось открыть порт $PORT: ${e.message}"
            running = false
            stopSelf()
            return
        }

        // 2. Подключаемся к адаптеру, при обрыве пробуем снова
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

    /** Команда в машину, ответ до '>' */
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

    /** Опрос педали: крутится без пауз, так быстро, как отвечает адаптер */
    private fun pollLoop() {
        val re = Regex("41(?:49|5A)([0-9A-F]{2})")
        var fails = 0
        while (running && carConnected) {
            val raw = carCmd(pid + if (fastSuffix) "1" else "")
            lastRaw = raw
            if (fastSuffix && raw.contains("?")) { fastSuffix = false; continue }
            val m = re.find(raw.replace(" ", ""))
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

    /** Новый замер: плавно ведём педаль от текущего значения к новому за средний интервал */
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

    /** Физика RPM: 50 раз в секунду, независимо от скорости Bluetooth */
    private fun physicsLoop() {
        var last = System.nanoTime()
        while (running) {
            Thread.sleep(20)
            val now = System.nanoTime()
            val dt = (now - last) / 1e9
            last = now
            val p = if (carConnected) synchronized(interpLock) { interp(now) } else 0.0
            pedal = p
            val md = MODES[modeIndex]
            val target = IDLE_RPM + Math.pow(p, md.curve) * (md.maxRpm - IDLE_RPM)
            val base = if (target > rpm) md.up else md.down
            val k = 1.0 - Math.pow(1.0 - base, dt / 0.08)
            rpm += (target - rpm) * k
        }
    }

    /** Пересылка в машину; режимы 01 кэшируются на 400 мс, чтобы не забивать Bluetooth */
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
                    out.write(("$resp\r\r>").toByteArray()); out.flush()
                } else sb.append(c.toChar())
            }
        } catch (_: Exception) {
        } finally {
            clientCount.decrementAndGet()
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun answer(cmd: String): String = when {
        cmd.startsWith("AT") -> when (cmd) {
            "ATZ", "ATI" -> "ELM327 v1.5"
            "AT@1" -> "OBDII to RS232 Interpreter"
            "ATRV" -> "12.6V"
            else -> "OK"
        }
        cmd == "010C" -> {
            val raw = (rpm * 4).toInt()
            "41 0C %02X %02X".format((raw shr 8) and 0xFF, raw and 0xFF)
        }
        cmd == "0100" -> patchSupported(forward(cmd))
        else -> forward(cmd)
    }

    /** В списке поддерживаемых PID 01-20 обязательно отмечаем 0C (RPM) */
    private fun patchSupported(resp: String): String {
        val line = resp.split("\r").firstOrNull { it.replace(" ", "").startsWith("4100") }
            ?: return "41 00 00 10 00 00"
        val bytes = line.replace(" ", "").chunked(2).map { it.toInt(16) }.toMutableList()
        if (bytes.size >= 6) bytes[3] = bytes[3] or 0x10
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
