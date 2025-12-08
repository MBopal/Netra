package com.example.netra.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.json.JSONObject
import java.nio.FloatBuffer

class ScamDetectorAccessibilityService : AccessibilityService() {

    private lateinit var interpreter: Interpreter
    private lateinit var tokenizer: Map<String, Int>
    private val maxLength = 100  // Updated to match training
    private val vocabSize = 5000  // Updated to match training
    private val scamThreshold = 0.5f  // Balanced threshold

    private val targetPackages = setOf(
        "com.whatsapp",
//        "com.instagram.android",
//        "com.facebook.orca", // Messenger
//        "org.telegram.messenger",
        "com.android.mms", // SMS
//        "com.google.android.gm", // Gmail
//        "com.shopee.id",
//        "com.tokopedia.tkpd",
//        "com.gojek.app"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info

        // Load model and tokenizer
        try {
            loadModel()
            loadTokenizer()
            createNotificationChannel()
            showServiceStartedNotification()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModel() {
        val modelFile = assets.open("scam_detector_cnn.tflite")
        val modelBuffer = modelFile.use {
            val fileChannel = (it as FileInputStream).channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }

        // Configure interpreter options for LSTM support
        val options = Interpreter.Options().apply {
            setNumThreads(4)  // Multi-threading for better performance
            setUseNNAPI(false)  // Disable NNAPI as it may not support SELECT_TF_OPS
        }

        interpreter = Interpreter(modelBuffer, options)
    }

    private fun loadTokenizer() {
        val tokenizerJson = assets.open("tokenizer_cnn.json").bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(tokenizerJson)
        val config = jsonObject.getJSONObject("config")
        val wordIndex = config.getJSONObject("word_index")

        tokenizer = buildMap {
            wordIndex.keys().forEach { key ->
                put(key, wordIndex.getInt(key))
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Check if event is from target app
        val packageName = event.packageName?.toString() ?: return
        if (!targetPackages.contains(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                analyzeScreenContent(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                analyzeNotification(event)
            }
        }
    }

    private fun analyzeScreenContent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val textList = mutableListOf<String>()

        extractTextFromNode(rootNode, textList)
        rootNode.recycle()

        val fullText = textList.joinToString(" ")
        if (fullText.isNotBlank()) {
            checkForScam(fullText, event.packageName.toString())
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        // Get text from current node
        node.text?.toString()?.let {
            if (it.length > 5) textList.add(it)
        }
        node.contentDescription?.toString()?.let {
            if (it.length > 5) textList.add(it)
        }

        // Recursively check child nodes
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractTextFromNode(child, textList)
                child.recycle()
            }
        }
    }

    private fun analyzeNotification(event: AccessibilityEvent) {
        val notification = event.parcelableData as? Notification ?: return

        val title = notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        val fullText = "$title $text"

        if (fullText.isNotBlank()) {
            checkForScam(fullText, event.packageName.toString())
        }
    }

    private fun checkForScam(text: String, packageName: String) {
        if (text.length < 10) return

        try {
            val prediction = predictScam(text)

            if (prediction > scamThreshold) {
                showScamWarning(text, prediction, packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun predictScam(text: String): Float {
        // Tokenize text
        val tokens = text.lowercase()
            .split(Regex("\\s+"))
            .mapNotNull { tokenizer[it] }
            .take(maxLength)

        // Pad sequence
        val paddedTokens = IntArray(maxLength) { 0 }
        tokens.forEachIndexed { index, token ->
            if (index < maxLength) paddedTokens[index] = token
        }

        // Convert to float array for model input
        val inputArray = Array(1) { FloatArray(maxLength) }
        paddedTokens.forEachIndexed { index, token ->
            inputArray[0][index] = token.toFloat()
        }

        // Run inference
        val outputArray = Array(1) { FloatArray(1) }
        interpreter.run(inputArray, outputArray)

        return outputArray[0][0]
    }

    private fun showScamWarning(text: String, confidence: Float, packageName: String) {
        val appName = getAppName(packageName)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ PERINGATAN SCAM TERDETEKSI")
            .setContentText("Kemungkinan penipuan ${(confidence * 100).toInt()}% di $appName")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Pesan mencurigakan terdeteksi:\n\n\"${text.take(200)}...\"\n\n" +
                        "Tingkat kecurigaan: ${(confidence * 100).toInt()}%\n" +
                        "JANGAN berikan data pribadi, OTP, atau transfer uang!"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
//            "com.instagram.android" -> "Instagram"
//            "com.facebook.orca" -> "Messenger"
//            "org.telegram.messenger" -> "Telegram"
            "com.android.mms" -> "SMS"
//            "com.google.android.gm" -> "Gmail"
//            "com.shopee.id" -> "Shopee"
//            "com.tokopedia.tkpd" -> "Tokopedia"
//            "com.gojek.app" -> "Gojek"
            else -> "Aplikasi"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scam Detector Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi peringatan penipuan digital"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showServiceStartedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Scam Protector Aktif")
            .setContentText("Melindungi Anda dari penipuan digital")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() {
        // Handle service interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        interpreter.close()
    }

    companion object {
        private const val CHANNEL_ID = "scam_detector_channel"
        private const val ONGOING_NOTIFICATION_ID = 1001
    }
}