package com.example.netra

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var testButton: Button

    private val REQ_POST_NOTIFICATIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableButton)
        testButton = findViewById(R.id.testButton)

        enableButton.setOnClickListener {
            openAccessibilitySettings()
        }

        testButton.setOnClickListener {
            showTestDialog()
        }

        // Request notification permission at runtime for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            // no UI required here; permission enables notifications on Android 13+
        }
    }

    private fun updateServiceStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "✅ Scam Protector AKTIF\n\nAnda terlindungi dari penipuan digital"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            enableButton.text = "Buka Pengaturan"
            testButton.isEnabled = true
        } else {
            statusText.text = "⚠️ Scam Protector TIDAK AKTIF\n\nAktifkan untuk melindungi dari penipuan"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            enableButton.text = "Aktifkan Sekarang"
            testButton.isEnabled = false
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        return enabledServices.any {
            it.id.contains(packageName)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun showTestDialog() {
        val testMessages = arrayOf(
            "Selamat! Anda menang undian 100 juta. Klik link ini",
            "Pesanan anda sedang dalam pengiriman",
            "URGENT! Akun anda diblokir. Verifikasi sekarang",
            "Terima kasih telah berbelanja di toko kami"
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("Test Deteksi Scam")
            .setMessage("Pilih pesan untuk ditest:")
            .setItems(testMessages) { _, which ->
                testScamDetection(testMessages[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun testScamDetection(message: String) {
        // Simulate detection by showing result dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hasil Test")
            .setMessage("Pesan yang ditest:\n\n\"$message\"\n\n" +
                    "Dalam penggunaan nyata, sistem akan otomatis mendeteksi " +
                    "pesan mencurigakan dan menampilkan peringatan.")
            .setPositiveButton("OK", null)
            .show()
    }
}