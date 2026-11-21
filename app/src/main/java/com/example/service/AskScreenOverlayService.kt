package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.PreferencesManager
import com.example.data.model.BubbleState
import com.example.data.model.ChatMessage
import com.example.data.model.QueryType
import com.example.data.model.WindowSizePreset
import com.example.data.repository.AskScreenRepository
import com.example.ui.overlay.FloatingAnswerWindow
import com.example.ui.overlay.FloatingBubbleView
import com.example.ui.overlay.FloatingQuickActionMenu
import com.example.ui.overlay.OverlayLifecycleOwner
import com.example.ui.overlay.RegionSelectionOverlayView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

class AskScreenOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "askscreen_service_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_READ_SCREEN = "com.example.action.READ_SCREEN"
        const val ACTION_LISTEN_SCREEN = "com.example.action.LISTEN_SCREEN"
        const val ACTION_TRANSCRIBE_AUDIO = "com.example.action.TRANSCRIBE_AUDIO"
        const val ACTION_VOICE_INPUT = "com.example.action.VOICE_INPUT"
        const val ACTION_READ_AND_LISTEN = "com.example.action.READ_AND_LISTEN"
        const val ACTION_TOGGLE_BUBBLE = "com.example.action.TOGGLE_BUBBLE"
        const val ACTION_AUTO_START = "com.example.action.AUTO_START"
        const val ACTION_AUTO_PAUSE = "com.example.action.AUTO_PAUSE"
        const val ACTION_AUTO_RESUME = "com.example.action.AUTO_RESUME"
        const val ACTION_AUTO_STOP = "com.example.action.AUTO_STOP"

        /** Reapply secure-window flags after the user changes Invisible Presentation Mode. */
        fun refreshInvisiblePresentationMode() {
            instance?.applyInvisiblePresentationModeToWindows()
        }

        private var instance: AskScreenOverlayService? = null

        fun onMediaProjectionGranted(context: Context, resultCode: Int, data: Intent, action: String) {
            val service = instance
            if (service != null) {
                service.handleMediaProjectionGranted(resultCode, data, action)
            } else {
                val intent = Intent(context, AskScreenOverlayService::class.java).apply {
                    this.action = ACTION_START
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("targetAction", action)
                }
                context.startService(intent)
            }
        }

        fun onMediaProjectionDenied(context: Context) {
            instance?.handleMediaProjectionDenied()
        }

        fun isRunning(): Boolean = instance != null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: AskScreenRepository
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var ttsManager: TtsManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Views
    private var bubbleView: FloatingBubbleView? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    private var answerWindowView: ComposeView? = null
    private var answerWindowLayoutParams: WindowManager.LayoutParams? = null
    private var answerWindowLifecycle: OverlayLifecycleOwner? = null

    private var quickMenuComposeView: ComposeView? = null
    private var quickMenuLayoutParams: WindowManager.LayoutParams? = null
    private var quickMenuLifecycle: OverlayLifecycleOwner? = null

    private var regionOverlayView: RegionSelectionOverlayView? = null

    // State
    private val bubbleState = MutableStateFlow(BubbleState.IDLE)
    private val sessionMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val liveCaptions = MutableStateFlow("")
    private val currentPreset = MutableStateFlow(WindowSizePreset.MEDIUM)
    private var isBubbleVisible = true
    private var isAnswerWindowVisible = false
    private var isQuickMenuVisible = false
    private var autoMonitorJob: Job? = null
    @Volatile private var autoMonitorPaused = false
    @Volatile private var autoMonitorRunning = false

    // Prevent repeated speech-recognition callbacks from submitting the same phrase.
    private var lastAudioTranscriptKey = ""
    private var lastAudioTranscriptAt = 0L
    // Listening sessions collect speech and screen snapshots first; AI runs only after Stop.
    private var listeningSessionType: QueryType? = null
    private val listeningSessionSegments = mutableListOf<String>()
    private val listeningSessionFrames = mutableListOf<Bitmap>()
    private var listeningSessionFinalizing = false
    // Kept for transcription mode, which intentionally writes transcript-only entries.
    private val audioAnswerMutex = Mutex()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var activeCaptureForegroundTypes = 0

    /**
     * Keep the floating assistant alive under its declared special-use type.
     * Add microphone and media-projection types only after the user has granted
     * the corresponding permission. Android 14+ enforces each type's prerequisites.
     */
    private fun updateForegroundServiceTypes(captureTypes: Int = activeCaptureForegroundTypes) {
        activeCaptureForegroundTypes = captureTypes
        val notification = buildNotification("Ready to assist your screen")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or captureTypes
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, captureTypes)
            }
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun enableMicrophoneForegroundType(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow microphone access before starting audio listening.", Toast.LENGTH_LONG).show()
            return false
        }
        val microphoneType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        updateForegroundServiceTypes(activeCaptureForegroundTypes or microphoneType)
        return true
    }

    private fun enableMediaProjectionForegroundType() {
        val projectionType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        updateForegroundServiceTypes(activeCaptureForegroundTypes or projectionType)
    }

    private fun disableMicrophoneForegroundType() {
        val microphoneType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        updateForegroundServiceTypes(activeCaptureForegroundTypes and microphoneType.inv())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.util.configureAppCheck(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
        repository = AskScreenRepository(this)
        screenCaptureManager = ScreenCaptureManager(this)
        audioCaptureManager = AudioCaptureManager(this)
        ttsManager = TtsManager(this)
        audioCaptureManager.onFatalRecognitionError = { message ->
            serviceScope.launch {
                disableMicrophoneForegroundType()
                bubbleState.value = BubbleState.ERROR
                liveCaptions.value = message
                sessionMessages.value = sessionMessages.value + ChatMessage(
                    role = "model",
                    text = message,
                    queryType = QueryType.AUDIO
                )
            }
        }

        updateMetrics()
        createNotificationChannel()
        updateForegroundServiceTypes(0)

        createFloatingBubble()
        currentPreset.value = prefs.windowPreset
    }

    private fun updateMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenCaptureManager.updateMetrics(screenWidth, screenHeight, metrics.densityDpi)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_READ_SCREEN -> startReadScreenAction()
            ACTION_LISTEN_SCREEN -> startListenAudioAction()
            ACTION_TRANSCRIBE_AUDIO -> startTranscribeAudioAction()
            ACTION_VOICE_INPUT -> startVoiceInput()
            ACTION_READ_AND_LISTEN -> startReadAndListenAction()
            ACTION_TOGGLE_BUBBLE -> toggleBubbleVisibility()
            ACTION_AUTO_START -> startAutoMonitor()
            ACTION_AUTO_PAUSE -> pauseAutoMonitor()
            ACTION_AUTO_RESUME -> resumeAutoMonitor()
            ACTION_AUTO_STOP -> stopAutoMonitor()
        }

        // Handle MediaProjection payload if delivered via intent
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        val targetAction = intent?.getStringExtra("targetAction") ?: ScreenCaptureActivity.ACTION_START_SCREEN
        if (resultCode != 0 && data != null) {
            handleMediaProjectionGranted(resultCode, data, targetAction)
        }

        return START_STICKY
    }

    private fun invisiblePresentationSecureFlag(): Int = if (prefs.isInvisiblePresentationMode) {
        WindowManager.LayoutParams.FLAG_SECURE
    } else 0

    private fun applyPrivacyModeToWindows() {
        fun update(view: View?, params: WindowManager.LayoutParams?) {
            if (view == null || params == null) return
            params.flags = if (prefs.isClassroomPrivacyMode) {
                params.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        update(bubbleView, bubbleLayoutParams)
        update(quickMenuComposeView, quickMenuLayoutParams)
        update(answerWindowView, answerWindowLayoutParams)
        update(regionOverlayView, regionOverlayView?.layoutParams as? WindowManager.LayoutParams)
    }

    private fun createFloatingBubble() {
        if (bubbleView != null) return

        val sizeDp = prefs.bubbleSizeDp
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or privacySecureFlag(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubblePosX.coerceIn(0, screenWidth - sizePx)
            y = prefs.bubblePosY.coerceIn(60, screenHeight - sizePx - 100)
        }
        bubbleLayoutParams = params

        val bubble = FloatingBubbleView(
            context = this,
            windowManager = windowManager,
            layoutParams = params,
            prefs = prefs,
            onTap = {
                // Tap: Instant "Read Screen"
                startReadScreenAction()
            },
            onLongPress = {
                // Long press: Quick action menu
                showQuickActionMenu()
            }
        )
        bubbleView = bubble

        try {
            windowManager.addView(bubble, params)
            isBubbleVisible = true
        } catch (e: Exception) {
            Log.e("AskScreenOverlayService", "Failed to add floating bubble to WindowManager", e)
        }

        // Observe bubble state changes
        serviceScope.launch {
            bubbleState.collect { state ->
                bubble.bubbleState = state
            }
        }
    }

    private fun showQuickActionMenu() {
        if (quickMenuComposeView != null) return
        hideQuickActionMenu()

        val bubbleP = bubbleLayoutParams ?: return
        val density = resources.displayMetrics.density
        val menuWidth = (230 * density).toInt()
        val menuHeight = (320 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or privacySecureFlag(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleP.x + 80).coerceIn(20, screenWidth - menuWidth - 20)
            y = (bubbleP.y - 20).coerceIn(60, screenHeight - menuHeight - 60)
        }
        quickMenuLayoutParams = params

        val lifecycle = OverlayLifecycleOwner()
        quickMenuLifecycle = lifecycle

        val composeView = ComposeView(this).apply {
            lifecycle.attachToView(this)
            setContent {
                FloatingQuickActionMenu(
                    onReadScreen = {
                        hideQuickActionMenu()
                        startReadScreenAction()
                    },
                    onListenScreen = {
                        hideQuickActionMenu()
                        startListenAudioAction()
                    },
                    onTranscribeAudio = {
                        hideQuickActionMenu()
                        startTranscribeAudioAction()
                    },
                    onReadAndListen = {
                        hideQuickActionMenu()
                        startReadAndListenAction()
                    },
                    onSelectRegion = {
                        hideQuickActionMenu()
                        startSelectRegionAction()
                    },
                    onOpenMainApp = {
                        hideQuickActionMenu()
                        val intent = Intent(this@AskScreenOverlayService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    },
                    onHideBubble = {
                        hideQuickActionMenu()
                        toggleBubbleVisibility()
                    },
                    onStopAssistant = {
                        hideQuickActionMenu()
                        stopSelf()
                    },
                    onDismiss = {
                        hideQuickActionMenu()
                    }
                )
            }
        }
        quickMenuComposeView = composeView

        try {
            windowManager.addView(composeView, params)
            isQuickMenuVisible = true
        } catch (e: Exception) {
            Log.e("AskScreenOverlayService", "Failed to add quick menu", e)
        }
    }

    private fun hideQuickActionMenu() {
        quickMenuComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        quickMenuLifecycle?.destroy()
        quickMenuComposeView = null
        quickMenuLifecycle = null
        isQuickMenuVisible = false
    }

    private fun showAnswerWindow() {
        if (answerWindowView != null) {
            answerWindowView?.visibility = View.VISIBLE
            isAnswerWindowVisible = true
            return
        }

        val density = resources.displayMetrics.density
        val preset = currentPreset.value
        val (initW, initH) = if (preset == WindowSizePreset.FULLSCREEN) {
            Pair(screenWidth, screenHeight)
        } else {
            val savedW = prefs.windowWidth
            val savedH = prefs.windowHeight
            if (savedW > 0 && savedH > 0) Pair(savedW, savedH)
            else Pair((preset.widthDp * density).toInt(), (preset.heightDp * density).toInt())
        }

        val params = WindowManager.LayoutParams(
            initW,
            initH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or privacySecureFlag(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.windowPosX.coerceIn(0, screenWidth - initW)
            y = prefs.windowPosY.coerceIn(80, screenHeight - initH - 80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        answerWindowLayoutParams = params

        val lifecycle = OverlayLifecycleOwner()
        answerWindowLifecycle = lifecycle

        val composeView = ComposeView(this).apply {
            lifecycle.attachToView(this)
            setContent {
                val msgs by sessionMessages.collectAsState()
                val bState by bubbleState.collectAsState()
                val captions by liveCaptions.collectAsState()
                val isSpeaking by ttsManager.isSpeaking.collectAsState()
                val presetState by currentPreset.collectAsState()

                FloatingAnswerWindow(
                    messages = msgs,
                    bubbleState = bState,
                    liveCaptions = captions,
                    isTtsSpeaking = isSpeaking,
                    currentPreset = presetState,
                    onPresetChange = { newPreset ->
                        currentPreset.value = newPreset
                        prefs.windowPreset = newPreset
                        applyPreset(newPreset)
                    },
                    onResizeDelta = { dx, dy ->
                        val lp = answerWindowLayoutParams ?: return@FloatingAnswerWindow
                        val minW = (240 * density).toInt()
                        val minH = (280 * density).toInt()
                        lp.width = (lp.width + dx.toInt()).coerceIn(minW, screenWidth)
                        lp.height = (lp.height + dy.toInt()).coerceIn(minH, screenHeight - 60)
                        prefs.windowWidth = lp.width
                        prefs.windowHeight = lp.height
                        try {
                            windowManager.updateViewLayout(answerWindowView, lp)
                        } catch (e: Exception) {
                            // ignore
                        }
                    },
                    onDragWindowDelta = { dx, dy ->
                        val lp = answerWindowLayoutParams ?: return@FloatingAnswerWindow
                        lp.x = (lp.x + dx.toInt()).coerceIn(0, screenWidth - lp.width)
                        lp.y = (lp.y + dy.toInt()).coerceIn(40, screenHeight - lp.height)
                        prefs.windowPosX = lp.x
                        prefs.windowPosY = lp.y
                        try {
                            windowManager.updateViewLayout(answerWindowView, lp)
                        } catch (e: Exception) {
                            // ignore
                        }
                    },
                    onClose = { hideAnswerWindow() },
                    onMinimize = {
                        answerWindowView?.visibility = View.GONE
                        isAnswerWindowVisible = false
                    },
                    onSendQuestion = { question ->
                        sendFollowUpQuestion(question)
                    },
                    onStartVoiceInput = {
                        startVoiceInput()
                    },
                    onStopVoiceInput = {
                        finishListeningSession()
                    },
                    onActionClick = { action ->
                        handleQuickChipAction(action)
                    },
                    onSpeakToggle = {
                        if (ttsManager.isSpeaking.value) {
                            ttsManager.stop()
                        } else {
                            val lastAnswer = sessionMessages.value.lastOrNull { it.role == "model" }?.text
                            if (!lastAnswer.isNullOrEmpty()) {
                                ttsManager.speak(lastAnswer, prefs.ttsSpeed)
                            }
                        }
                    },
                    onPickFile = {
                        val intent = Intent(this@AskScreenOverlayService, MainActivity::class.java).apply {
                            action = "com.example.action.PICK_FILE"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    },
                    onExplainAnswer = { answerText ->
                        explainAnswer(answerText)
                    }
                )
            }
        }
        answerWindowView = composeView

        try {
            windowManager.addView(composeView, params)
            isAnswerWindowVisible = true
        } catch (e: Exception) {
            Log.e("AskScreenOverlayService", "Failed to add answer window", e)
        }
    }

    private fun applyPreset(preset: WindowSizePreset) {
        val lp = answerWindowLayoutParams ?: return
        val density = resources.displayMetrics.density
        if (preset == WindowSizePreset.FULLSCREEN) {
            lp.width = screenWidth
            lp.height = screenHeight
            lp.x = 0
            lp.y = 0
        } else {
            lp.width = (preset.widthDp * density).toInt()
            lp.height = (preset.heightDp * density).toInt()
        }
        try {
            windowManager.updateViewLayout(answerWindowView, lp)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun hideAnswerWindow() {
        answerWindowView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        answerWindowLifecycle?.destroy()
        answerWindowView = null
        answerWindowLifecycle = null
        isAnswerWindowVisible = false
    }

    private fun toggleBubbleVisibility() {
        bubbleView?.let {
            isBubbleVisible = !isBubbleVisible
            it.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
            val msg = if (isBubbleVisible) "Floating bubble visible" else "Floating bubble hidden (tap notification to restore)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Automatic monitoring is opt-in and remains active only while this service/session is alive.
    private fun startAutoMonitor() {
        if (!screenCaptureManager.hasMediaProjection()) {
            val intent = ScreenCaptureActivity.createIntent(this, ScreenCaptureActivity.ACTION_AUTO_MONITOR)
            startActivity(intent)
            return
        }
        if (autoMonitorJob?.isActive == true) {
            autoMonitorPaused = false
            return
        }
        autoMonitorPaused = false
        autoMonitorRunning = true
        autoMonitorJob = serviceScope.launch {
            var previousSignature: Long? = null
            var candidateSignature: Long? = null
            var stableCount = 0
            updateAssistantNotification("Auto monitor running — waiting for a new question")
            while (isActive && autoMonitorRunning) {
                if (!screenCaptureManager.hasMediaProjection()) {
                    autoMonitorRunning = false
                    updateAssistantNotification("Screen session ended — tap Auto Start to grant again")
                    break
                }
                if (autoMonitorPaused) {
                    delay(500)
                    continue
                }
                if (bubbleState.value == BubbleState.PROCESSING_AI || bubbleState.value == BubbleState.CAPTURING_SCREEN) {
                    delay(700)
                    continue
                }
                bubbleView?.visibility = View.INVISIBLE
                answerWindowView?.visibility = View.INVISIBLE
                delay(120)
                val frame = screenCaptureManager.captureScreen()
                bubbleView?.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
                answerWindowView?.visibility = if (isAnswerWindowVisible) View.VISIBLE else View.GONE
                if (frame != null) {
                    val signature = screenSignature(frame)
                    frame.recycle()
                    if (previousSignature == null) {
                        previousSignature = signature
                        updateAssistantNotification("Initial screen detected — checking for a question")
                        startReadScreenAction(onlyIfQuestion = true)
                    } else if (signature == candidateSignature) {
                        stableCount++
                        if (stableCount >= 2 && signature != previousSignature) {
                            previousSignature = signature
                            stableCount = 0
                            updateAssistantNotification("New screen detected — analysing")
                            startReadScreenAction(onlyIfQuestion = true)
                        }
                    } else {
                        candidateSignature = signature
                        stableCount = 1
                    }
                }
                delay(prefs.autoMonitorIntervalMs)
            }
            updateAssistantNotification("Auto monitor paused")
        }
    }

    private fun screenSignature(bitmap: Bitmap): Long {
        // Sample a small grayscale grid to ignore tiny rendering noise and avoid storing screenshots.
        var hash = 1125899906842597L
        val width = bitmap.width
        val height = bitmap.height
        for (gy in 0 until 24) for (gx in 0 until 24) {
            val x = ((gx + 0.5f) * width / 24f).toInt().coerceIn(0, width - 1)
            val y = (height * 0.06f + (gy + 0.5f) * (height * 0.86f) / 24f).toInt().coerceIn(0, height - 1)
            val pixel = bitmap.getPixel(x, y)
            val gray = (((pixel shr 16) and 0xff) * 30 + ((pixel shr 8) and 0xff) * 59 + (pixel and 0xff) * 11) / 100
            hash = hash * 31 + (gray / 24)
        }
        return hash
    }

    private fun pauseAutoMonitor() {
        autoMonitorPaused = true
        updateAssistantNotification("Auto monitor paused")
    }

    private fun resumeAutoMonitor() {
        if (!screenCaptureManager.hasMediaProjection()) {
            startAutoMonitor()
            return
        }
        if (autoMonitorJob?.isActive != true) startAutoMonitor() else {
            autoMonitorPaused = false
            updateAssistantNotification("Auto monitor resumed")
        }
    }

    private fun stopAutoMonitor() {
        autoMonitorRunning = false
        autoMonitorPaused = false
        autoMonitorJob?.cancel()
        autoMonitorJob = null
        updateAssistantNotification("Auto monitor stopped")
    }

    private fun updateAssistantNotification(message: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (e: Exception) { Log.w("AskScreenOverlayService", "Notification update failed", e) }
    }

    // ACTIONS
    fun startReadScreenAction(regionCrop: Rect? = null, onlyIfQuestion: Boolean = false) {
        if (!screenCaptureManager.hasMediaProjection()) {
            val target = if (onlyIfQuestion) ScreenCaptureActivity.ACTION_AUTO_MONITOR else ScreenCaptureActivity.ACTION_START_SCREEN
            val intent = ScreenCaptureActivity.createIntent(this, target)
            startActivity(intent)
            return
        }

        serviceScope.launch {
            bubbleState.value = BubbleState.CAPTURING_SCREEN
            // Temporarily hide overlays to take a clean screenshot of the user's screen
            bubbleView?.visibility = View.INVISIBLE
            answerWindowView?.visibility = View.INVISIBLE
            delay(180)

            val bitmap = screenCaptureManager.captureScreen(regionCrop)

            // Restore overlay visibility immediately
            bubbleView?.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
            answerWindowView?.visibility = if (isAnswerWindowVisible) View.VISIBLE else View.GONE

            if (bitmap == null) {
                bubbleState.value = BubbleState.ERROR
                Toast.makeText(this@AskScreenOverlayService, "Could not capture screen. Please check permission.", Toast.LENGTH_SHORT).show()
                delay(1500)
                bubbleState.value = BubbleState.IDLE
                return@launch
            }

            bubbleState.value = BubbleState.PROCESSING_AI
            showAnswerWindow()

            val queryType = if (regionCrop != null) QueryType.REGION else QueryType.SCREEN
            val userMsg = ChatMessage(
                role = "user",
                text = if (regionCrop != null) "Reading selected screen region..." else "Reading this screen...",
                queryType = queryType
            )
            sessionMessages.value = sessionMessages.value + userMsg

            val answerStyle = "Reply in exactly one of these formats. The very first thing in your reply must be the tag. " +
                "[OBJ] = multiple-choice question(s). After the tag give ONLY the correct option for each question, one per line, as 'A. option text', in screen order. No explanation at all. " +
                "[THEORY] = a question that needs a written answer (theory, essay, short answer, fill in the blank). After the tag give ONLY the direct answer, straight to the point, nothing else. " +
                "[INFO] = anything else. After the tag start with 'I see: ' and say briefly what is on the screen, then answer any question or task you notice. " +
                "Do not guess unreadable text."
            val prompt = if (onlyIfQuestion) {
                "Check this changed screen for a new visible question or learning task. If there is no new question or actionable task, reply exactly NO_NEW_QUESTION. Otherwise: $answerStyle"
            } else if (regionCrop != null) {
                "Read the selected screen region carefully. $answerStyle"
            } else {
                "Look at the visible screen. $answerStyle"
            }

            val result = repository.askScreenAi(
                prompt = prompt,
                queryType = queryType,
                images = listOf(bitmap),
                chatHistory = sessionMessages.value
            )
            // Keep a copy of the last screenshot so the Explain button can look at the question again.
            lastScreenshot?.let { if (!it.isRecycled) it.recycle() }
            lastScreenshot = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { null }
            if (!bitmap.isRecycled) bitmap.recycle()

            result.onSuccess { answer ->
                bubbleState.value = BubbleState.IDLE
                if (!(onlyIfQuestion && answer.trim().equals("NO_NEW_QUESTION", ignoreCase = true))) {
                    // The AI starts with a tag: [OBJ], [THEORY] or [INFO]. Remove it before showing the answer.
                    val tagMatch = Regex("^\\s*\\[(OBJ|THEORY|INFO)]\\s*", RegexOption.IGNORE_CASE).find(answer)
                    val kind = tagMatch?.groupValues?.get(1)?.uppercase()
                    val cleanAnswer = if (tagMatch != null) answer.removeRange(tagMatch.range).trim() else answer.trim()

                    val aiMsg = ChatMessage(role = "model", text = cleanAnswer, queryType = queryType)
                    sessionMessages.value = sessionMessages.value + aiMsg

                    // Auto-submit only when it applies, and stay silent when it does not.
                    if (prefs.isAutoSubmitEnabled) {
                        val firstLine = cleanAnswer.lineSequence().firstOrNull()?.trim().orEmpty()
                        val looksLikeOption = Regex("^[A-Za-z]\\s*[.)\\-]\\s*\\S").containsMatchIn(firstLine)
                        when {
                            kind == "OBJ" || (kind == null && looksLikeOption) ->
                                PracticeAccessibilityService.selectAndSubmit(cleanAnswer)
                            kind == "THEORY" ->
                                PracticeAccessibilityService.typeAndSubmit(cleanAnswer)
                            else -> Unit
                        }
                    }
                    if (prefs.isTtsEnabled) ttsManager.speak(cleanAnswer, prefs.ttsSpeed)
                }
            }.onFailure { error ->
                appendRequestFailure(error, queryType, "I could not complete that request.")
            }
        }
    }

    private fun appendRequestFailure(error: Throwable, queryType: QueryType, fallback: String) {
        val waitSeconds = error.message
            ?.takeIf { it.startsWith("RATE_LIMITED:") }
            ?.substringAfter(":")
            ?.toLongOrNull()
            ?.coerceAtLeast(1)
        val message = if (waitSeconds != null) {
            bubbleState.value = BubbleState.IDLE
            val waitText = if (waitSeconds == 1L) "1 second" else "$waitSeconds seconds"
            "You have reached the temporary request limit. Please wait $waitText before asking again. This question has not been answered yet."
        } else {
            bubbleState.value = BubbleState.ERROR
            "$fallback ${error.message ?: "Please try again."}"
        }
        sessionMessages.value = sessionMessages.value + ChatMessage(
            role = "model", text = message, queryType = queryType
        )
    }

    private fun shouldProcessAudioTranscript(transcript: String): Boolean {
        val key = transcript.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (key.length < 3) return false
        val now = System.currentTimeMillis()
        if (key == lastAudioTranscriptKey && now - lastAudioTranscriptAt < 12_000L) {
            Log.d("AskScreenOverlayService", "Ignoring duplicate audio transcript")
            return false
        }
        lastAudioTranscriptKey = key
        lastAudioTranscriptAt = now
        return true
    }

    private suspend fun captureFreshScreenForListening(): Bitmap? {
        if (!screenCaptureManager.hasMediaProjection()) return null
        bubbleView?.visibility = View.INVISIBLE
        answerWindowView?.visibility = View.INVISIBLE
        return try {
            delay(180)
            screenCaptureManager.captureScreen()
        } finally {
            bubbleView?.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
            answerWindowView?.visibility = if (isAnswerWindowVisible) View.VISIBLE else View.GONE
        }
    }

    private fun beginListeningSession(type: QueryType) {
        listeningSessionFrames.forEach { if (!it.isRecycled) it.recycle() }
        listeningSessionFrames.clear()
        listeningSessionSegments.clear()
        listeningSessionType = type
        listeningSessionFinalizing = false
        lastAudioTranscriptKey = ""
        lastAudioTranscriptAt = 0L
    }

    private fun mergeListeningTranscript(existing: String, incoming: String): String {
        val oldWords = existing.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val newWords = incoming.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (oldWords.isEmpty()) return incoming.trim()
        if (newWords.isEmpty()) return existing
        val oldNormal = oldWords.map { it.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "") }
        val newNormal = newWords.map { it.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "") }
        if (oldNormal.joinToString(" ").contains(newNormal.joinToString(" "))) return existing
        val maxOverlap = minOf(12, oldWords.size, newWords.size)
        var overlap = 0
        for (count in maxOverlap downTo 1) {
            if (oldNormal.takeLast(count) == newNormal.take(count)) {
                overlap = count
                break
            }
        }
        return (oldWords + newWords.drop(overlap)).joinToString(" ")
    }

    private suspend fun collectListeningSegment(transcript: String, type: QueryType) {
        // The recognizer may deliver one final result after Stop is pressed; keep accepting
        // that callback during the short flush window before the session is submitted.
        if (listeningSessionType != type) return
        if (transcript.isBlank() || !shouldProcessAudioTranscript(transcript)) return
        val previous = listeningSessionSegments.joinToString(" ")
        val merged = mergeListeningTranscript(previous, transcript).take(24000)
        listeningSessionSegments.clear()
        if (merged.isNotBlank()) listeningSessionSegments.add(merged)
        if (type == QueryType.SCREEN_AND_AUDIO) {
            val frame = captureFreshScreenForListening()
            if (frame != null) {
                // Keep the opening frame and the three most recent frames for a long session.
                if (listeningSessionFrames.size >= 4) {
                    val replaced = listeningSessionFrames.removeAt(1)
                    if (!replaced.isRecycled) replaced.recycle()
                }
                listeningSessionFrames.add(frame)
            }
        }
        val wordCount = merged.split(Regex("\\s+")).count { it.isNotBlank() }
        liveCaptions.value = "Captured $wordCount words. Keep playing, or tap the microphone to finish."
        bubbleState.value = BubbleState.LISTENING_AUDIO
    }

    /**
     * Stops the capture and analyses the collected session once. Raw transcript fragments
     * are deliberately not displayed as chat messages.
     */
    private fun finishListeningSession() {
        if (listeningSessionFinalizing) return
        if (listeningSessionType == null) {
            audioCaptureManager.stopListening()
            disableMicrophoneForegroundType()
            bubbleState.value = BubbleState.IDLE
            return
        }
        listeningSessionFinalizing = true
        audioCaptureManager.stopContinuousListeningAndFlush()
        disableMicrophoneForegroundType()
        liveCaptions.value = "Finishing the recording..."
        bubbleState.value = BubbleState.PROCESSING_AI
        serviceScope.launch {
            // Give Android's recognizer time to deliver the final words from its active request.
            delay(1600)
            val type = listeningSessionType ?: QueryType.AUDIO
            val transcript = listeningSessionSegments.joinToString(" ").trim()
            val frames = listeningSessionFrames.toList()
            listeningSessionType = null
            listeningSessionSegments.clear()
            listeningSessionFrames.clear()
            try {
                if (transcript.isBlank()) {
                    bubbleState.value = BubbleState.ERROR
                    liveCaptions.value = "No speech was captured. The session was not analysed."
                    return@launch
                }
                val wordCount = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
                sessionMessages.value = sessionMessages.value + ChatMessage(
                    role = "user",
                    text = "Completed listening session captured ($wordCount words).",
                    queryType = type
                )
                val screenContext = if (type == QueryType.SCREEN_AND_AUDIO && frames.isNotEmpty()) {
                    "The attached screen captures were collected during the listening session. Read visible questions, answer choices, instructions, headings, and relevant on-screen text. Combine them with the full spoken recording."
                } else if (type == QueryType.SCREEN_AND_AUDIO) {
                    "A screen capture was not available. Do not claim to have read screen content; use the spoken recording only."
                } else {
                    "This is an audio-only session. Use the full spoken recording."
                }
                val prompt = """
You are analysing one completed listening session, not a live fragment.
$screenContext

FULL SESSION TRANSCRIPT:
$transcript

Instructions:
- Wait until the complete session transcript above; do not answer only the last sentence.
- Identify every question asked in the session and answer them in their original order.
- Use headings exactly like "Answer for Question 1", "Answer for Question 2", and so on. Do not repeat the full question unless needed to identify an ambiguous reference.
- For multiple-choice questions, include both the option letter and its wording, for example "A. Photosynthesis".
- Give clear, accurate, professional answers in natural language. Do not invent missing information.
- After all answers, add a "Summary" section with one separate line for each question, using exactly "Question 1: [short summary]", "Question 2: [short summary]", and so on.
- If the recording contains no questions, provide a concise, useful summary of the complete session.
- Do not expose a raw transcript in the final response.
""".trimIndent()
                liveCaptions.value = "Analysing the complete $wordCount-word session..."
                val result = repository.askScreenAi(
                    prompt = prompt,
                    queryType = type,
                    images = frames,
                    chatHistory = sessionMessages.value
                )
                result.onSuccess { answer ->
                    sessionMessages.value = sessionMessages.value + ChatMessage(
                        role = "model",
                        text = answer,
                        queryType = type
                    )
                    bubbleState.value = BubbleState.IDLE
                    liveCaptions.value = "Complete session analysed."
                    if (prefs.isTtsEnabled) ttsManager.speak(answer, prefs.ttsSpeed)
                }.onFailure { error ->
                    appendRequestFailure(
                        error,
                        type,
                        "The complete listening session could not be analysed. Please try again."
                    )
                }
            } finally {
                frames.forEach { if (!it.isRecycled) it.recycle() }
                listeningSessionFinalizing = false
            }
        }
    }

    fun startListenAudioAction() {
        if (!enableMicrophoneForegroundType()) return
        serviceScope.launch {
            beginListeningSession(QueryType.AUDIO)
            bubbleState.value = BubbleState.LISTENING_AUDIO
            showAnswerWindow()
            liveCaptions.value = "Listening through the microphone. Speaker playback may be heard; headphones or protected apps may block it. Tap the microphone when finished."
            audioCaptureManager.startContinuousListening(
                languageTag = prefs.audioLanguageTag,
                onFatalError = { message ->
                    liveCaptions.value = message
                    bubbleState.value = BubbleState.ERROR
                }
            ) { transcript ->
                serviceScope.launch { collectListeningSegment(transcript, QueryType.AUDIO) }
            }
        }
    }

    /**
     * Transcribes microphone speech into chat without sending it to the AI.
     * SpeechRecognizer uses the device language by default; availability of Yoruba
     * and other languages depends on the installed Android recognition provider.
     */
    fun startTranscribeAudioAction() {
        if (!enableMicrophoneForegroundType()) return
        serviceScope.launch {
            bubbleState.value = BubbleState.LISTENING_AUDIO
            showAnswerWindow()
            liveCaptions.value = "Transcribing speech only — no AI answers..."
            audioCaptureManager.startContinuousListening(
                languageTag = prefs.audioLanguageTag,
                onFatalError = { message ->
                    liveCaptions.value = message
                    bubbleState.value = BubbleState.ERROR
                }
            ) { transcript ->
                serviceScope.launch {
                    if (transcript.isBlank() || !shouldProcessAudioTranscript(transcript)) return@launch
                    if (!audioAnswerMutex.tryLock()) return@launch
                    try {
                        val usageResult = repository.recordTranscriptionUse()
                        usageResult.onSuccess {
                            sessionMessages.value = sessionMessages.value + ChatMessage(
                                role = "user",
                                text = "📝 Transcript: $transcript",
                                queryType = QueryType.TRANSCRIPTION
                            )
                            liveCaptions.value = "Transcript added. Listening for more speech..."
                            bubbleState.value = if (audioCaptureManager.isContinuousListening) {
                                BubbleState.LISTENING_AUDIO
                            } else {
                                BubbleState.IDLE
                            }
                        }.onFailure { error ->
                            audioCaptureManager.stopListening()
                            disableMicrophoneForegroundType()
                            val message = error.message.orEmpty()
                            liveCaptions.value = when {
                                message.startsWith("SUBSCRIPTION_REQUIRED") ->
                                    "Your free transcription uses are finished. The subscription page is opening; choose DOM AI Core in Account to continue."
                                message.startsWith("SIGN_IN_REQUIRED") ->
                                    "Please sign in to continue transcription."
                                else -> "Transcription could not be verified. Please check your connection and try again."
                            }
                            bubbleState.value = BubbleState.ERROR
                            Toast.makeText(this@AskScreenOverlayService, liveCaptions.value, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        audioAnswerMutex.unlock()
                    }
                }
            }
        }
    }

    fun startReadAndListenAction() {
        if (!enableMicrophoneForegroundType()) return
        if (!screenCaptureManager.hasMediaProjection()) {
            val intent = ScreenCaptureActivity.createIntent(this, ScreenCaptureActivity.ACTION_START_BOTH)
            startActivity(intent)
            return
        }

        serviceScope.launch {
            beginListeningSession(QueryType.SCREEN_AND_AUDIO)
            bubbleState.value = BubbleState.LISTENING_AUDIO
            showAnswerWindow()
            liveCaptions.value = "Capturing the opening screen and listening through the microphone. Headphones or protected apps may block audio. Tap the microphone when finished."
            captureFreshScreenForListening()?.let { listeningSessionFrames.add(it) }
            audioCaptureManager.startContinuousListening(
                languageTag = prefs.audioLanguageTag,
                onFatalError = { message ->
                    liveCaptions.value = message
                    bubbleState.value = BubbleState.ERROR
                }
            ) { transcript ->
                serviceScope.launch { collectListeningSegment(transcript, QueryType.SCREEN_AND_AUDIO) }
            }
        }
    }

    fun startSelectRegionAction() {
        if (!screenCaptureManager.hasMediaProjection()) {
            val intent = ScreenCaptureActivity.createIntent(this, ScreenCaptureActivity.ACTION_SELECT_REGION)
            startActivity(intent)
            return
        }

        if (regionOverlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or privacySecureFlag(),
            PixelFormat.TRANSLUCENT
        )

        val overlay = RegionSelectionOverlayView(
            context = this,
            onRegionSelected = { rect ->
                removeRegionOverlay()
                startReadScreenAction(rect)
            },
            onCancelled = {
                removeRegionOverlay()
            }
        )
        regionOverlayView = overlay

        try {
            windowManager.addView(overlay, params)
        } catch (e: Exception) {
            Log.e("AskScreenOverlayService", "Failed to add region selection overlay", e)
        }
    }

    private fun removeRegionOverlay() {
        regionOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        regionOverlayView = null
    }

    fun startVoiceInput() {
        if (!enableMicrophoneForegroundType()) return
        bubbleState.value = BubbleState.LISTENING_AUDIO
        liveCaptions.value = "Listening to your voice..."
        audioCaptureManager.startListening(prefs.audioLanguageTag) { speech ->
            liveCaptions.value = ""
            disableMicrophoneForegroundType()
            bubbleState.value = BubbleState.IDLE
            if (speech.isNotBlank()) {
                sendFollowUpQuestion(speech, QueryType.VOICE)
            }
        }
    }

    private var lastScreenshot: Bitmap? = null

    /** Called by the Explain button under an answer. Short, simple and human-sounding. */
    private fun explainAnswer(answerText: String) {
        serviceScope.launch {
            bubbleState.value = BubbleState.PROCESSING_AI
            sessionMessages.value = sessionMessages.value + ChatMessage(
                role = "user",
                text = "Explain this answer",
                queryType = QueryType.TEXT
            )
            val shot = lastScreenshot?.takeIf { !it.isRecycled }
            val prompt = "Explain this the way a real person would explain it to a friend. " +
                "Use plain, simple words and short sentences. Go straight to the point and keep it to a few sentences. " +
                "No headings, no bullet points, no emojis, no filler like 'Great question' or 'Certainly', and never say you are an AI. " +
                "The answer was: \"$answerText\". " +
                (if (shot != null) "The question is on the screenshot. " else "") +
                "Tell me why that answer is right, or what it means."

            val result = repository.askScreenAi(
                prompt = prompt,
                queryType = QueryType.TEXT,
                images = if (shot != null) listOf(shot) else emptyList(),
                chatHistory = sessionMessages.value
            )

            result.onSuccess { answer ->
                bubbleState.value = BubbleState.IDLE
                sessionMessages.value = sessionMessages.value + ChatMessage(
                    role = "model",
                    text = answer.trim(),
                    queryType = QueryType.TEXT
                )
                if (prefs.isTtsEnabled) ttsManager.speak(answer, prefs.ttsSpeed)
            }.onFailure { err ->
                appendRequestFailure(err, QueryType.TEXT, "I could not explain that answer.")
            }
        }
    }

    private fun sendFollowUpQuestion(text: String, queryType: QueryType = QueryType.TEXT) {
        serviceScope.launch {
            bubbleState.value = BubbleState.PROCESSING_AI
            val userMsg = ChatMessage(role = "user", text = text, queryType = queryType)
            sessionMessages.value = sessionMessages.value + userMsg

            val result = repository.askScreenAi(
                prompt = text,
                queryType = queryType,
                chatHistory = sessionMessages.value
            )

            result.onSuccess { answer ->
                bubbleState.value = BubbleState.IDLE
                val aiMsg = ChatMessage(role = "model", text = answer, queryType = queryType)
                sessionMessages.value = sessionMessages.value + aiMsg
                if (prefs.isTtsEnabled) {
                    ttsManager.speak(answer, prefs.ttsSpeed)
                }
            }.onFailure { err ->
                appendRequestFailure(err, queryType, "I could not complete that request.")
            }
        }
    }

    private fun handleQuickChipAction(action: String) {
        when (action) {
            "READ_SCREEN" -> startReadScreenAction()
            "LISTEN_SCREEN" -> startListenAudioAction()
            "READ_AND_LISTEN" -> startReadAndListenAction()
            "SELECT_REGION" -> startSelectRegionAction()
            "EXPLAIN_MORE" -> sendFollowUpQuestion("Explain the previous answer the way a real person would explain it to a friend: simple words, short, straight to the point, no lists and no filler.")
            "SUMMARIZE" -> sendFollowUpQuestion("Summarize the main takeaway in 2 crisp bullet points.")
            "TRANSLATE" -> sendFollowUpQuestion("Translate the question and answer into Spanish and French.")
            "EXTRACT_TEXT" -> sendFollowUpQuestion("Extract all the visible text verbatim from the screen image.")
            "COPY_ANSWER" -> {
                val lastAnswer = sessionMessages.value.lastOrNull { it.role == "model" }?.text
                if (!lastAnswer.isNullOrEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AskScreen AI", lastAnswer))
                    Toast.makeText(this, "Answer copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            "CLEAR_CHAT" -> {
                sessionMessages.value = emptyList()
                Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleMediaProjectionGranted(resultCode: Int, data: Intent, targetAction: String) {
        enableMediaProjectionForegroundType()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection != null) {
            screenCaptureManager.setMediaProjection(projection)
            when (targetAction) {
                ScreenCaptureActivity.ACTION_START_SCREEN -> startReadScreenAction()
                ScreenCaptureActivity.ACTION_START_AUDIO -> startListenAudioAction()
                ScreenCaptureActivity.ACTION_START_BOTH -> startReadAndListenAction()
                ScreenCaptureActivity.ACTION_SELECT_REGION -> startSelectRegionAction()
                ScreenCaptureActivity.ACTION_AUTO_MONITOR -> startAutoMonitor()
            }
        } else {
            handleMediaProjectionDenied()
        }
    }

    fun handleMediaProjectionDenied() {
        Toast.makeText(this, "Screen capture permission was not granted.", Toast.LENGTH_SHORT).show()
        bubbleState.value = BubbleState.IDLE
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AskScreen AI Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps AskScreen AI floating overlay active over other apps"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenApp = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        val readScreenIntent = Intent(this, AskScreenOverlayService::class.java).apply {
            action = ACTION_READ_SCREEN
        }
        val pReadScreen = PendingIntent.getService(this, 1, readScreenIntent, PendingIntent.FLAG_IMMUTABLE)

        val toggleBubbleIntent = Intent(this, AskScreenOverlayService::class.java).apply {
            action = ACTION_TOGGLE_BUBBLE
        }
        val pToggle = PendingIntent.getService(this, 2, toggleBubbleIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, AskScreenOverlayService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val autoStartIntent = Intent(this, AskScreenOverlayService::class.java).apply { action = ACTION_AUTO_START }
        val pAutoStart = PendingIntent.getService(this, 4, autoStartIntent, PendingIntent.FLAG_IMMUTABLE)
        val autoPauseIntent = Intent(this, AskScreenOverlayService::class.java).apply { action = ACTION_AUTO_PAUSE }
        val pAutoPause = PendingIntent.getService(this, 5, autoPauseIntent, PendingIntent.FLAG_IMMUTABLE)
        val autoResumeIntent = Intent(this, AskScreenOverlayService::class.java).apply { action = ACTION_AUTO_RESUME }
        val pAutoResume = PendingIntent.getService(this, 6, autoResumeIntent, PendingIntent.FLAG_IMMUTABLE)
        val autoStopIntent = Intent(this, AskScreenOverlayService::class.java).apply { action = ACTION_AUTO_STOP }
        val pAutoStop = PendingIntent.getService(this, 7, autoStopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AskScreen AI Active")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pOpenApp)
            .addAction(0, "Read Screen", pReadScreen)
            .addAction(0, "Auto Start", pAutoStart)
            .addAction(0, "Pause", pAutoPause)
            .addAction(0, "Resume", pAutoResume)
            .addAction(0, "Stop Auto", pAutoStop)
            .addAction(0, "Show/Hide Bubble", pToggle)
            .addAction(0, "Stop Assistant", pStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopAutoMonitor()
        serviceScope.cancel()
        hideQuickActionMenu()
        hideAnswerWindow()
        removeRegionOverlay()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        bubbleView = null
        screenCaptureManager.stop()
        audioCaptureManager.destroy()
        ttsManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
