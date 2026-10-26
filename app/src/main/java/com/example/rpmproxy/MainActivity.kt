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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "1. Выберите ваш ELM327 (он должен быть сопряжён по Bluetooth):"
            textSize = 16f
        })

        spinner = Spinner(this)
        root.addView(spinner)

        val start = Button(this).apply { text = "Старт" }
        val stop = Button(this).apply { text = "Стоп" }
        root.addView(start)
        root.addView(stop)

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 32, 0, 0)
            text = "2. В Rev Heads выберите WiFi и укажите:\nIP: 127.0.0.1\nПорт: 35000"
        }
        root.addView(status)

        setContentView(root)

        start.setOnClickListener {
            if (devices.isEmpty()) {
                status.text = "Нет сопряжённых устройств. Сначала сопрягите адаптер в настройках Bluetooth."
                return@setOnClickListener
            }
            val mac = devices[spinner.selectedItemPosition].address
            startForegroundService(Intent(this, ProxyService::class.java).putExtra("mac", mac))
            status.text = "Сервис запущен.\nВ Rev Heads: WiFi, IP 127.0.0.1, порт 35000."
        }
        stop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
            status.text = "Сервис остановлен."
        }

        requestNeededPermissions()
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
        } catch (e: SecurityException) {
            status.text = "Нужно разрешение на Bluetooth."
        }
    }
}
