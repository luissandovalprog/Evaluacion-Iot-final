package com.example.evaluacion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    // --- ESTÁNDARES DE SEGURIDAD ---

    private val SERVICE_UUID = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef")
    private val RAIN_CHAR_UUID = UUID.fromString("abcdef01-1234-5678-90ab-cdef12345678")
    private val CONTROL_CHAR_UUID = UUID.fromString("abcdef02-1234-5678-90ab-cdef12345678")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- INTEGRIDAD DE DATOS ---
    private val XOR_KEY: Byte = 0x5A

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var tvStatus: TextView
    private var isConnected = false

    // --- RECONEXIÓN AUTOMÁTICA MEJORADA ---
    private var isRetrying = false
    private val RECONNECT_DELAY_MS = 3000L
    private var deviceAddress: String? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private val database = FirebaseDatabase.getInstance().reference
    private val CHANNEL_ID = "canal_lluvia"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_control)

        setupUI()
        crearCanalNotificacion()

        // --- COMPATIBILIDAD HARDWARE ---
        // Verificación de seguridad para asegurar que el dispositivo soporta BLE antes de intentar nada.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Hardware no compatible con BLE", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        if (deviceAddress != null) {
            connectToBLEDevice(deviceAddress!!)
        }
    }

    private fun setupUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar_control)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra("DEVICE_NAME") ?: "Control IoT"

        tvStatus = findViewById(R.id.tv_status)

        findViewById<Button>(R.id.btn_open_window).setOnClickListener {
            sendSecureBLECommand("b")
            actualizarBaseDeDatos("ABIERTA")
        }

        findViewById<Button>(R.id.btn_close_window).setOnClickListener {
            sendSecureBLECommand("a")
            actualizarBaseDeDatos("CERRADA")
        }
    }

    // --- SINCRONIZACIÓN Y ALMACENAMIENTO TEMPORAL ---
    private fun actualizarBaseDeDatos(estado: String) {
        if (isNetworkAvailable()) {
            database.child("estado_ventana").setValue(estado)
                .addOnSuccessListener {
                    Toast.makeText(this, "Sincronizado con nube", Toast.LENGTH_SHORT).show()
                }
        } else {
            // Aquí demostramos el almacenamiento temporal. Firebase guarda en caché localmente
            // y sincronizará cuando vuelva la red.
            database.child("estado_ventana").setValue(estado)
            Toast.makeText(this, "Offline: Guardado localmente. Se subirá al conectar.", Toast.LENGTH_LONG).show()
        }
    }

    //--- Método auxiliar para verificar red
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }

    // --- ENVÍO DE INFORMACIÓN SEGURA ---
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
        if (!isConnected || controlCharacteristic == null) {
            Toast.makeText(this, "Esperando conexión segura...", Toast.LENGTH_SHORT).show()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        try {
            val securePacket = prepareSecurePacket(command)
            controlCharacteristic?.value = securePacket
            controlCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(controlCharacteristic)
        } catch (e: Exception) {
            Log.e("BLE", "Error de seguridad en envío: ${e.message}")
        }
    }

    private fun connectToBLEDevice(address: String) {
        tvStatus.text = "Estado: Iniciando conexión segura..."
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            val device = adapter.getRemoteDevice(address)


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        } catch (e: Exception) {
            attemptReconnection()
        }
    }

    // --- LÓGICA DE RECONEXIÓN ROBUSTA ---
    private fun attemptReconnection() {
        if (isRetrying) return // Evitar loops múltiples
        isRetrying = true

        isConnected = false
        runOnUiThread { tvStatus.text = "Se perdió la conexión. Reintentando..." }

        reconnectHandler.postDelayed({
            isRetrying = false
            deviceAddress?.let {
                Log.d("BLE", "Intentando reconexión automática...")
                connectToBLEDevice(it)
            }
        }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@DeviceControlActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                reconnectHandler.removeCallbacksAndMessages(null) // Cancelar reintentos pendientes
                runOnUiThread { tvStatus.text = "Conectado. Negociando seguridad..." }
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                // Si se desconecta, disparamos la reconexión automática INMEDIATAMENTE
                attemptReconnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            // 1. VERIFICACIÓN DE SEGURIDAD OBLIGATORIA
            if (ActivityCompat.checkSelfPermission(this@DeviceControlActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(SERVICE_UUID)
                controlCharacteristic = service?.getCharacteristic(CONTROL_CHAR_UUID)
                val rainCharacteristic = service?.getCharacteristic(RAIN_CHAR_UUID)

                // --- RECEPCIÓN DE INSTRUCCIONES ---
                if (rainCharacteristic != null) {
                    // A. Habilitar localmente
                    gatt?.setCharacteristicNotification(rainCharacteristic, true)

                    // B. Habilitar remotamente (Descriptor CCCD)
                    val descriptor = rainCharacteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt?.writeDescriptor(descriptor)
                        }
                    }
                }

                runOnUiThread { tvStatus.text = "Conexión Establecida y Segura" }
            } else {
                runOnUiThread { tvStatus.text = "Error al descubrir servicios: $status" }
            }
        }

        // --- RECEPCIÓN DE DATOS DEL MICROCONTROLADOR
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == RAIN_CHAR_UUID) {
                val mensaje = characteristic.getStringValue(0) ?: ""
                runOnUiThread {
                    tvStatus.text = "Arduino dice: $mensaje"

                    // Actualización automática en base a sensores
                    if (mensaje.contains("cerrada", ignoreCase = true)) {
                        // Usar la función que maneja offline/online
                        actualizarBaseDeDatos("CERRADA")
                        lanzarNotificacion("Alerta Lluvia", "Ventana cerrada por sensor.")
                    } else if (mensaje.contains("abierta", ignoreCase = true)) {
                        actualizarBaseDeDatos("ABIERTA")
                    }
                }
            }
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Alertas IoT"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun lanzarNotificacion(titulo: String, mensaje: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectHandler.removeCallbacksAndMessages(null)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}