package com.example.rpmproxy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var pedalText: TextView
    private lateinit var rpmText: TextView
    private lateinit var debugText: TextView
    private lateinit var bar: ProgressBar
    private var devices: List<BluetoothDevice> = emptyList()

    private val prefs by lazy { getSharedPreferences("rpmproxy", MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 100)
        }
    }

    private fun label(t: String, size: Float = 16f) = TextView(this).apply {
        text = t
        textSize = size
        setPadding(0, 24, 0, 8)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(label("Адаптер ELM327 (сопряжённый по Bluetooth):"))
        spinner = Spinner(this)
        root.addView(spinner)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "Старт" }
        val stop = Button(this).apply { text = "Стоп" }
        buttons.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        status = label("", 15f)
        root.addView(status)

        pedalText = label("Педаль: --", 26f)
        root.addView(pedalText)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60))

        rpmText = label("RPM: --", 20f)
        root.addView(rpmText)

        root.addView(label("Режим резкости:"))
        val group = RadioGroup(this)
        ProxyService.modeIndex = prefs.getInt("mode", 1).coerceIn(0, ProxyService.MODES.size - 1)
        ProxyService.MODES.forEachIndexed { i, m ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = m.name
                textSize = 18f
            }
            group.addView(rb)
            if (i == ProxyService.modeIndex) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, id ->
            val idx = g.indexOfChild(g.findViewById(id))
            if (idx >= 0) {
                ProxyService.modeIndex = idx
                prefs.edit().putInt("mode", idx).apply()
            }
        }
        root.addView(group)

        root.addView(label("В Rev Heads: WiFi, IP 127.0.0.1, порт 35000", 14f))
        debugText = label("", 12f)
        root.addView(debugText)

        setContentView(ScrollView(this).apply { addView(root) })

        start.setOnClickListener {
            if (devices.isEmpty()) {
                status.text = "Нет сопряжённых устройств. Сопрягите адаптер в настройках Bluetooth."
                return@setOnClickListener
            }
            val mac = devices[spinner.selectedItemPosition].address
            prefs.edit().putString("mac", mac).apply()
            startForegroundService(Intent(this, ProxyService::class.java).putExtra("mac", mac))
        }
        stop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
        }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        val s = ProxyService
        if (!s.running) {
            status.text = "Состояние: остановлен"
            pedalText.text = "Педаль: --"
            rpmText.text = "RPM: --"
            bar.progress = 0
            debugText.text = ""
            return
        }
        val rev = if (s.clientCount.get() > 0) "Rev Heads подключён" else "Rev Heads не подключён"
        status.text = "Состояние: ${s.status}\n$rev"
        if (s.carConnected) {
            val p = Math.round(s.pedal * 100).toInt()
            pedalText.text = "Педаль: $p %"
            bar.progress = p
            rpmText.text = "RPM (виртуальный): ${s.rpm.toInt()}"
            debugText.text = "PID: ${s.pid}   ответ: ${s.lastRaw}"
        } else {
            pedalText.text = "Педаль: --"
            rpmText.text = "RPM: --"
            bar.progress = 0
            debugText.text = ""
        }
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isEmpty()) loadDevices() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        loadDevices()
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            devices = bt?.bondedDevices?.toList() ?: emptyList()
            val names = devices.map { "${it.name} (${it.address})" }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val saved = prefs.getString("mac", null)
            val idx = devices.indexOfFirst { it.address == saved }
            if (idx >= 0) spinner.setSelection(idx)
        } catch (e: SecurityException) {
            status.text = "Нужно разрешение на Bluetooth."
        }
    }
}
