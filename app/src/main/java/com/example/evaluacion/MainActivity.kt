package com.example.evaluacion

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            
        }
        setContentView(R.layout.activity_main)

        val etUsername = findViewById<EditText>(R.id.et_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnRegister = findViewById<Button>(R.id.btn_register)

        // --- LÓGICA BOTÓN INGRESAR CON HASHING ---
        btnLogin.setOnClickListener {
            val username = etUsername.text.toString()
            val password = etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Escribe usuario y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database = FirebaseDatabase.getInstance().reference
            val userRef = database.child("usuarios").child(username)

            userRef.get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val storedHash = snapshot.child("password").value.toString()
                    val inputHash = hashPassword(password)

                    if (storedHash == inputHash) {
                        Toast.makeText(this, "Acceso Correcto", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this, DeviceListActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Usuario no encontrado", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Error de conexión", Toast.LENGTH_SHORT).show()
            }
        }

        // --- LÓGICA BOTÓN REGISTRAR CON HASHING ---
        btnRegister.setOnClickListener {
            val username = etUsername.text.toString()
            val password = etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Escribe usuario y contraseña para registrarte", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database = FirebaseDatabase.getInstance().reference
            val userRef = database.child("usuarios").child(username)

            userRef.get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    Toast.makeText(this, "El usuario YA existe, prueba otro nombre", Toast.LENGTH_SHORT).show()
                } else {
                    // Hasheamos la contraseña antes de guardarla
                    val hashedPassword = hashPassword(password)
                    userRef.child("password").setValue(hashedPassword).addOnSuccessListener {
                        Toast.makeText(this, "¡Usuario Creado! Ahora dale a Ingresar", Toast.LENGTH_SHORT).show()
                    }.addOnFailureListener {
                        Toast.makeText(this, "Error al guardar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Función para hashear contraseñas usando SHA-256
     * @param password Contraseña en texto plano
     * @return Hash SHA-256 en formato hexadecimal
     */
    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(password.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}