package com.example.rpmproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Прокси ELM327:  Chevrolet Volt <-> (Bluetooth) <-> этот сервис <-> (TCP :35000) <-> Rev Heads
 * Все команды пересылаются в машину как есть, кроме 010C (RPM): он считается от педали газа (0149).
 *
 * Запуск: startForegroundService(Intent(ctx, ProxyService::class.java).putExtra("mac", "AA:BB:CC:DD:EE:FF"))
 *
 * Манифест: permissions BLUETOOTH_CONNECT, INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE,
 * FOREGROUND_SERVICE_CONNECTED_DEVICE; service android:foregroundServiceType="connectedDevice".
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 35000
        const val IDLE_RPM = 850.0
        const val MAX_RPM = 5500.0
        const val SMOOTH = 0.15          // 0..1, больше = быстрее реакция
        const val PEDAL_PID = "0149"     // если не отвечает, попробуйте "015A"
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @Volatile private var running = false
    @Volatile private var rpm = IDLE_RPM
    @Volatile private var pedal = 0.0     // 0..1

    private val lock = Any()              // реальный адаптер один, обращаемся по очереди
    private var carIn: InputStream? = null
    private var carOut: OutputStream? = null
    private var server: ServerSocket? = null

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val mac = intent?.getStringExtra("mac") ?: return START_NOT_STICKY
        if (running) return START_STICKY
        running = true
        thread { runProxy(mac) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
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

    // ---------------- связь с реальным адаптером ----------------

    @SuppressLint("MissingPermission")
    private fun runProxy(mac: String) {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            bt.cancelDiscovery()
            val sock = bt.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SPP)
            sock.connect()
            carIn = sock.inputStream
            carOut = sock.outputStream

            for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) carCmd(c)

            thread { pollPedal() }
            serveClients()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            running = false
            stopSelf()
        }
    }

    /** Отправить команду в машину и дождаться '>' */
    private fun carCmd(cmd: String): String = synchronized(lock) {
        val o = carOut ?: return ""
        val i = carIn ?: return ""
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

    /** Читаем педаль и считаем RPM */
    private fun pollPedal() {
        val re = Regex("4149([0-9A-F]{2})")
        while (running) {
            try {
                val r = carCmd(PEDAL_PID).replace(" ", "")
                re.find(r)?.let { pedal = it.groupValues[1].toInt(16) / 255.0 }
                val target = IDLE_RPM + pedal * (MAX_RPM - IDLE_RPM)
                rpm += (target - rpm) * SMOOTH
                Thread.sleep(80)
            } catch (e: Exception) { Thread.sleep(500) }
        }
    }

    // ---------------- TCP-сервер для Rev Heads ----------------

    private fun serveClients() {
        server = ServerSocket(PORT)
        while (running) {
            val client = server!!.accept()
            thread { handleClient(client) }
        }
    }

    private fun handleClient(s: Socket) {
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
        } finally { try { s.close() } catch (_: Exception) {} }
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
        cmd == "0100" -> patchSupported(carCmd(cmd))
        else -> carCmd(cmd)
    }

    /** Гарантируем, что в списке поддерживаемых PID 01-20 отмечен 0C (RPM) */
    private fun patchSupported(resp: String): String {
        val line = resp.split("\r").firstOrNull { it.replace(" ", "").startsWith("4100") }
            ?: return "41 00 00 10 00 00"
        val hex = line.replace(" ", "")
        val bytes = hex.chunked(2).map { it.toInt(16) }.toMutableList()
        if (bytes.size >= 6) bytes[3] = bytes[3] or 0x10
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}
