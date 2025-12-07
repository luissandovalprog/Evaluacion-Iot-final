package com.example.evaluacion

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DeviceListActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var recyclerView: RecyclerView

    // 1. Lanzador para solicitar PERMISOS (Android 12+)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
            val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false

            if (connectGranted && scanGranted) {
                // Si nos dan permiso, verificamos si está encendido
                checkBluetoothEnabled()
            } else {
                Toast.makeText(this, "Se requieren permisos de Bluetooth", Toast.LENGTH_LONG).show()
            }
        }

    // 2. Lanzador para solicitar ENCENDER el Bluetooth (Si está apagado)
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // El usuario aceptó encenderlo
                loadPairedDevices()
            } else {
                // El usuario rechazó
                Toast.makeText(this, "Bluetooth debe estar activo para continuar", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Dispositivos Bluetooth"

        recyclerView = findViewById(R.id.rv_device_list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo no soporta Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // Iniciamos el proceso de verificación
        checkPermissions()
    }

    private fun checkPermissions() {
        // Paso 1: Verificar Permisos de Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
                return
            }
        }
        // Si ya tenemos permisos (o es Android < 12), pasamos a verificar si está encendido
        checkBluetoothEnabled()
    }

    private fun checkBluetoothEnabled() {
        // Paso 2: Verificar si el Bluetooth está ENCENDIDO
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            // Está encendido y con permisos: Cargar lista
            loadPairedDevices()
        }
    }

    private fun loadPairedDevices() {
        try {
            // Doble verificación de seguridad para el compilador
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return
            }

            val deviceList = ArrayList<Device>()
            val pairedDevices = bluetoothAdapter.bondedDevices

            if (pairedDevices.isNotEmpty()) {
                for (device in pairedDevices) {
                    deviceList.add(Device(device.name ?: "Desconocido", device.address))
                }
            } else {
                Toast.makeText(this, "No hay dispositivos vinculados. Ve a Ajustes > Bluetooth", Toast.LENGTH_LONG).show()
            }

            val adapter = DeviceListAdapter(deviceList) { device ->
                val intent = Intent(this, DeviceControlActivity::class.java)
                intent.putExtra("DEVICE_NAME", device.name)
                intent.putExtra("DEVICE_ADDRESS", device.address)
                startActivity(intent)
            }
            recyclerView.adapter = adapter

        } catch (e: SecurityException) {
            Toast.makeText(this, "Error de permisos", Toast.LENGTH_SHORT).show()
        }
    }
}