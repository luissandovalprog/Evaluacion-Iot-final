package com.example.evaluacion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

class DeviceControlActivity : AppCompatActivity() {

    // UUIDs
    private val SERVICE_UUID = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef")
    private val CONTROL_CHAR_UUID = UUID.fromString("abcdef02-1234-5678-90ab-cdef12345678")

    // Clave de encriptación XOR
    private val XOR_KEY: Byte = 0x5A

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var tvStatus: TextView
    private var isConnected = false

    // Variables para reconexión automática
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 3
    private val RECONNECT_DELAY_MS = 2000L
    private var deviceAddress: String? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private val database = FirebaseDatabase.getInstance().reference
    private val CHANNEL_ID = "canal_lluvia"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_control)

        setupUI()
        crearCanalNotificacion()
        lanzarNotificacion("App Lluvia", "¡Bienvenido al sistema de control!")

        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        if (deviceAddress != null) {
            connectToBLEDevice(deviceAddress!!)
        }
    }

    private fun setupUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar_control)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra("DEVICE_NAME") ?: "Control BLE"

        tvStatus = findViewById(R.id.tv_status)

        findViewById<Button>(R.id.btn_open_window).setOnClickListener {
            sendSecureBLECommand("b")
            database.child("estado_ventana").setValue("ABIERTA")
        }

        findViewById<Button>(R.id.btn_close_window).setOnClickListener {
            sendSecureBLECommand("a")
            database.child("estado_ventana").setValue("CERRADA")
        }
    }

    // --- FUNCIONES DE SEGURIDAD ---
    private fun encryptCommand(command: String): ByteArray {
        val commandByte = command.toByteArray()[0]
        val encrypted = (commandByte.toInt() xor XOR_KEY.toInt()).toByte()
        return byteArrayOf(encrypted)
    }

    private fun calculateChecksum(data: ByteArray): Byte {
        var sum = 0
        for (byte in data) {
            sum += byte.toInt() and 0xFF
        }
        return (sum % 256).toByte()
    }

    private fun prepareSecurePacket(command: String): ByteArray {
        val encrypted = encryptCommand(command)
        val checksum = calculateChecksum(encrypted)
        return encrypted + checksum
    }

    private fun sendSecureBLECommand(command: String) {
        if (!isConnected) {
            tvStatus.text = "Estado: No conectado. Reconectando..."
            Toast.makeText(this, "Dispositivo no conectado", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothGatt == null || controlCharacteristic == null) {
            tvStatus.text = "Estado: Servicio no disponible"
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val securePacket = prepareSecurePacket(command)
            controlCharacteristic?.value = securePacket
            controlCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = bluetoothGatt?.writeCharacteristic(controlCharacteristic) ?: false

            if (success) {
                val accion = if(command == "a") "Cerrar" else "Abrir"
                tvStatus.text = "Comando seguro enviado: $accion"
            } else {
                tvStatus.text = "Error al enviar comando"
            }
        } catch (e: Exception) {
            tvStatus.text = "Error de seguridad: ${e.message}"
        }
    }

    // --- FUNCIONES DE CONEXIÓN Y RECONEXIÓN ---
    private fun connectToBLEDevice(address: String) {
        tvStatus.text = "Estado: Conectando a BLE..."
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            val device = adapter.getRemoteDevice(address)

            // Cerrar conexión anterior si existe
            bluetoothGatt?.close()

            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            tvStatus.text = "Error de conexión: ${e.message}"
            attemptReconnection()
        }
    }

    /**
     * Intenta reconectar automáticamente con delay exponencial
     */
    private fun attemptReconnection() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            runOnUiThread {
                tvStatus.text = "Estado: Conexión perdida. Máximo de intentos alcanzado."
                Toast.makeText(this, "No se pudo reconectar. Verifica el dispositivo.", Toast.LENGTH_LONG).show()
                lanzarNotificacion("Error de Conexión", "No se pudo reconectar al dispositivo BLE")
            }
            reconnectAttempts = 0
            return
        }

        reconnectAttempts++
        runOnUiThread {
            tvStatus.text = "Estado: Reconectando... Intento $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS"
        }

        reconnectHandler.postDelayed({
            deviceAddress?.let { address ->
                connectToBLEDevice(address)
            }
        }, RECONNECT_DELAY_MS * reconnectAttempts) // Delay incremental
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0 // Resetear contador al conectar exitosamente

                    runOnUiThread {
                        tvStatus.text = "Estado: Conectado [SEGURO]"
                        Toast.makeText(this@DeviceControlActivity, "Conexión establecida", Toast.LENGTH_SHORT).show()
                    }

                    if (ActivityCompat.checkSelfPermission(this@DeviceControlActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt?.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false

                    runOnUiThread {
                        tvStatus.text = "Estado: Desconectado"

                        // Solo intentar reconectar si fue una desconexión inesperada (status != 0)
                        if (status != BluetoothGatt.GATT_SUCCESS && deviceAddress != null) {
                            Toast.makeText(this@DeviceControlActivity, "Conexión perdida. Intentando reconectar...", Toast.LENGTH_SHORT).show()
                            attemptReconnection()
                        }
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    runOnUiThread {
                        tvStatus.text = "Estado: Conectando..."
                    }
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    runOnUiThread {
                        tvStatus.text = "Estado: Desconectando..."
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(SERVICE_UUID)
                controlCharacteristic = service?.getCharacteristic(CONTROL_CHAR_UUID)

                if (controlCharacteristic != null) {
                    runOnUiThread {
                        tvStatus.text = "Estado: Listo (Modo Seguro)"
                        Toast.makeText(this@DeviceControlActivity, "Servicios descubiertos. Sistema listo.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Estado: Característica no encontrada"
                        Toast.makeText(this@DeviceControlActivity, "Error: Servicio no compatible", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "Estado: Error al descubrir servicios"
                }
            }
        }
    }

    // --- FUNCIONES DE NOTIFICACIÓN ---
    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Alertas Lluvia"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun lanzarNotificacion(titulo: String, mensaje: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                return
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancelar cualquier reconexión pendiente
        reconnectHandler.removeCallbacksAndMessages(null)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        deviceAddress = null
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}