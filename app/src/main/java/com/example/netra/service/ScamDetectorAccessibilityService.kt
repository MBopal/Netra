package com.example.netra.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class ScamDetectorAccessibilityService : AccessibilityService() {

    private lateinit var interpreter: Interpreter
    private lateinit var tokenizer: Map<String, Int>
    private val maxLength = 100
    private val vocabSize = 5000
    private val scamThreshold = 0.5f

    private val targetPackages = setOf(
        "com.whatsapp",
//        "com.instagram.android",
//        "com.facebook.orca",
//        "org.telegram.messenger",
        "com.android.mms",
//        "com.google.android.gm",
//        "com.shopee.id",
//        "com.tokopedia.tkpd",
//        "com.gojek.app"
    )

    // Debounce handler: accumulate latest text and schedule processing after delay
    private val handler = Handler(Looper.getMainLooper())
    private val debounceDelay = 1500L
    private var debounceRunnable: Runnable? = null
    @Volatile private var pendingText: String = ""
    @Volatile private var pendingPackage: String = ""

    // Per-package cooldown to avoid spamming notifications
    private val lastAlertTime = ConcurrentHashMap<String, Long>()
    private val alertCooldown = 5000L

    override fun onServiceConnected() {
        super.onServiceConnected()

        System.out.println("CONNECTED")
        System.out.println("CONNECTED")
        System.out.println("CONNECTED")

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

        try {
            Log.d("ScamDetector", "Starting service...")
            loadModel()
            Log.d("ScamDetector", "✓ Model loaded")
            loadTokenizer()
            Log.d("ScamDetector", "✓ Tokenizer loaded")
            createNotificationChannel()
            showServiceStartedNotification()
            Log.d("ScamDetector", "✓ Service started successfully")
        } catch (e: Exception) {
            Log.e("ScamDetector", "❌ Error starting service", e)
            e.printStackTrace()
        }
    }

    private fun loadModel() {
        try {
            val inputStream = assets.open("scam_detector_cnn.tflite")
            val modelBytes = inputStream.use { it.readBytes() }

            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false)
            }

            interpreter = Interpreter(modelBuffer, options)
            Log.d("ScamDetector", "✓ Model loaded successfully")
        } catch (e: Exception) {
            Log.e("ScamDetector", "❌ Error loading model", e)
            e.printStackTrace()
            throw e
        }
    }

    private fun loadTokenizer() {
        try {
            val tokenizerJson = assets.open("tokenizer_cnn.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(tokenizerJson)

            // Fix: Parse config first, then get word_index as STRING
            val config = jsonObject.getJSONObject("config")
            val wordIndexString = config.getString("word_index")

            // Parse the string as JSON
            val wordIndex = JSONObject(wordIndexString)

            tokenizer = buildMap {
                wordIndex.keys().forEach { key ->
                    put(key, wordIndex.getInt(key))
                }
            }

            Log.d("ScamDetector", "✓ Tokenizer loaded: ${tokenizer.size} words")
        } catch (e: Exception) {
            Log.e("ScamDetector", "❌ Error loading tokenizer", e)
            throw e
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        Log.d("ScamDetector", "Event from: $packageName")

        if (!targetPackages.contains(packageName)) return

        Log.d("ScamDetector", "✓ Target package detected: $packageName")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // extract text quickly and debounce actual analysis
                val rootNode = rootInActiveWindow ?: return
                val textList = mutableListOf<String>()
                extractTextFromNode(rootNode, textList)
                rootNode.recycle()
                val fullText = textList.joinToString(" ")
                if (fullText.isNotBlank() && fullText.length > 10) {
                    scheduleCheck(fullText, packageName)
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notification = event.parcelableData as? Notification ?: return
                val title = notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
                val fullText = "$title $text"
                if (fullText.isNotBlank() && fullText.length > 10) {
                    scheduleCheck(fullText, packageName)
                }
            }
        }
    }

    private fun scheduleCheck(text: String, packageName: String) {
        // keep the latest text and package, reset debounce timer
        pendingText = text
        pendingPackage = packageName

        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            try {
                checkForScam(pendingText, pendingPackage)
            } catch (e: Exception) {
                Log.e("ScamDetector", "Error during debounced check", e)
            } finally {
                debounceRunnable = null
            }
        }
        handler.postDelayed(debounceRunnable!!, debounceDelay)
    }

    private fun analyzeScreenContent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val textList = mutableListOf<String>()

        extractTextFromNode(rootNode, textList)
        rootNode.recycle()

        val fullText = textList.joinToString(" ")
        if (fullText.isNotBlank() && fullText.length > 10) {
            Log.d("ScamDetector", "Text extracted: ${fullText.take(100)}...")
            checkForScam(fullText, event.packageName.toString())
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        node.text?.toString()?.let {
            if (it.length > 5) textList.add(it)
        }
        node.contentDescription?.toString()?.let {
            if (it.length > 5) textList.add(it)
        }

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

        if (fullText.isNotBlank() && fullText.length > 10) {
            Log.d("ScamDetector", "Notification text: ${fullText.take(100)}...")
            checkForScam(fullText, event.packageName.toString())
        }
    }

    private fun checkForScam(text: String, packageName: String) {
        if (text.length < 10) {
            Log.d("ScamDetector", "Text too short, skipping")
            return
        }

        try {
            Log.d("ScamDetector", "Analyzing text: ${text.take(50)}...")
            val prediction = predictScam(text)
            Log.d("ScamDetector", "Prediction score: $prediction (threshold: $scamThreshold)")

            if (prediction > scamThreshold) {
                Log.d("ScamDetector", "⚠️ SCAM DETECTED!")
                showScamWarning(text, prediction, packageName)
            } else {
                Log.d("ScamDetector", "✓ Normal message")
            }
        } catch (e: Exception) {
            Log.e("ScamDetector", "Error in prediction", e)
            e.printStackTrace()
        }
    }

    private fun predictScam(text: String): Float {
        val tokens = text.lowercase()
            .split(Regex("\\s+"))
            .mapNotNull { tokenizer[it] }
            .take(maxLength)

        val paddedTokens = IntArray(maxLength) { 0 }
        tokens.forEachIndexed { index, token ->
            if (index < maxLength) paddedTokens[index] = token
        }

        val inputArray = Array(1) { FloatArray(maxLength) }
        paddedTokens.forEachIndexed { index, token ->
            inputArray[0][index] = token.toFloat()
        }

        val outputArray = Array(1) { FloatArray(1) }
        interpreter.run(inputArray, outputArray)

        return outputArray[0][0]
    }

    private fun showScamWarning(text: String, confidence: Float, packageName: String) {
        // throttle per package
        val now = System.currentTimeMillis()
        val last = lastAlertTime[packageName] ?: 0L
        if (now - last < alertCooldown) {
            Log.d("ScamDetector", "Skipping notification due to cooldown for $packageName")
            return
        }
        lastAlertTime[packageName] = now

        val appName = getAppName(packageName)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val bigText = "Pesan mencurigakan terdeteksi:\n\n\"${text.take(400)}\"\n\n" +
                "Tingkat kecurigaan: ${(confidence * 100).toInt()}%\n" +
                "JANGAN berikan data pribadi, OTP, atau transfer uang!"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ PERINGATAN SCAM TERDETEKSI")
            // short summary visible when collapsed
            .setContentText("Kemungkinan penipuan ${(confidence * 100).toInt()}% di $appName")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        notificationManager.notify((now % Int.MAX_VALUE).toInt(), notification)
        Log.d("ScamDetector", "✓ Notification shown")
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
        Log.d("ScamDetector", "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::interpreter.isInitialized) {
            interpreter.close()
        }
        handler.removeCallbacksAndMessages(null)
        Log.d("ScamDetector", "Service destroyed")
    }

    companion object {
        private const val CHANNEL_ID = "scam_detector_channel"
        private const val ONGOING_NOTIFICATION_ID = 1001
    }
}