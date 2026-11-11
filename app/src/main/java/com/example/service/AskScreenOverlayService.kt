package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
        const val ACTION_READ_AND_LISTEN = "com.example.action.READ_AND_LISTEN"
        const val ACTION_TOGGLE_BUBBLE = "com.example.action.TOGGLE_BUBBLE"
        const val ACTION_AUTO_START = "com.example.action.AUTO_START"
        const val ACTION_AUTO_PAUSE = "com.example.action.AUTO_PAUSE"
        const val ACTION_AUTO_RESUME = "com.example.action.AUTO_RESUME"
        const val ACTION_AUTO_STOP = "com.example.action.AUTO_STOP"

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
    // Only one automatic audio answer may run at a time; newer callbacks are dropped while busy.
    private val audioAnswerMutex = Mutex()

    private var screenWidth = 1080
    private var screenHeight = 2400

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

        updateMetrics()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready to assist your screen"))

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                        audioCaptureManager.stopListening()
                        bubbleState.value = BubbleState.IDLE
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
                bubbleState.value = BubbleState.ERROR
                val errMsg = ChatMessage(
                    role = "model",
                    text = "⚠️ Failed to get answer: ${error.message}",
                    queryType = queryType
                )
                sessionMessages.value = sessionMessages.value + errMsg
            }
        }
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

    fun startListenAudioAction() {
        serviceScope.launch {
            bubbleState.value = BubbleState.LISTENING_AUDIO
            showAnswerWindow()
            liveCaptions.value = "Listening to audio..."

            audioCaptureManager.startContinuousListening { transcript ->
                serviceScope.launch {
                    if (!audioAnswerMutex.tryLock()) {
                        liveCaptions.value = "Processing the previous question..."
                        return@launch
                    }
                    try {
                    liveCaptions.value = ""
                    if (transcript.isBlank() || !shouldProcessAudioTranscript(transcript)) {
                        bubbleState.value = BubbleState.LISTENING_AUDIO
                        liveCaptions.value = "Listening continuously..."
                        return@launch
                    }

                    bubbleState.value = BubbleState.PROCESSING_AI
                    val userMsg = ChatMessage(
                        role = "user",
                        text = "🎧 Heard: \"$transcript\"",
                        queryType = QueryType.AUDIO
                    )
                    sessionMessages.value = sessionMessages.value + userMsg

                    val prompt = "The user captured the following spoken audio from their device: \"$transcript\". Answer any question asked, explain the concept, or summarize the spoken content clearly."
                    val result = repository.askScreenAi(
                        prompt = prompt,
                        queryType = QueryType.AUDIO,
                        chatHistory = sessionMessages.value
                    )

                    result.onSuccess { answer ->
                        bubbleState.value = BubbleState.IDLE
                        val aiMsg = ChatMessage(
                            role = "model",
                            text = answer,
                            queryType = QueryType.AUDIO
                        )
                        sessionMessages.value = sessionMessages.value + aiMsg
                        if (prefs.isTtsEnabled) {
                            ttsManager.speak(answer, prefs.ttsSpeed)
                        }
                    }.onFailure { err ->
                        bubbleState.value = BubbleState.ERROR
                        sessionMessages.value = sessionMessages.value + ChatMessage(
                            role = "model",
                            text = "Audio query failed: ${err.message}",
                            queryType = QueryType.AUDIO
                        )
                    }
                    } finally {
                        audioAnswerMutex.unlock()
                        if (audioCaptureManager.isListening()) {
                            bubbleState.value = BubbleState.LISTENING_AUDIO
                            liveCaptions.value = "Listening continuously..."
                        }
                    }
                }
            }
        }
    }

    fun startReadAndListenAction() {
        if (!screenCaptureManager.hasMediaProjection()) {
            val intent = ScreenCaptureActivity.createIntent(this, ScreenCaptureActivity.ACTION_START_BOTH)
            startActivity(intent)
            return
        }

        serviceScope.launch {
            bubbleState.value = BubbleState.CAPTURING_SCREEN
            bubbleView?.visibility = View.INVISIBLE
            answerWindowView?.visibility = View.INVISIBLE
            delay(180)

            val bitmap = screenCaptureManager.captureScreen()

            bubbleView?.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
            answerWindowView?.visibility = if (isAnswerWindowVisible) View.VISIBLE else View.GONE

            bubbleState.value = BubbleState.LISTENING_AUDIO
            showAnswerWindow()
            liveCaptions.value = "Listening to accompanying audio..."

            audioCaptureManager.startContinuousListening { transcript ->
                serviceScope.launch {
                    if (!audioAnswerMutex.tryLock()) {
                        liveCaptions.value = "Processing the previous question..."
                        return@launch
                    }
                    try {
                    liveCaptions.value = ""
                    if (transcript.isBlank() || !shouldProcessAudioTranscript(transcript)) {
                        bubbleState.value = BubbleState.LISTENING_AUDIO
                        liveCaptions.value = "Listening continuously..."
                        return@launch
                    }

                    // Refresh the screen for each new spoken segment instead of reusing
                    // the screenshot captured when the combined session first started.
                    val currentScreen = captureFreshScreenForListening()
                    bubbleState.value = BubbleState.PROCESSING_AI

                    val userMsg = ChatMessage(
                        role = "user",
                        text = "📺 Captured screen & audio: \"$transcript\"",
                        queryType = QueryType.SCREEN_AND_AUDIO
                    )
                    sessionMessages.value = sessionMessages.value + userMsg

                    val prompt = "The user is watching a video/lesson. Screen image is attached, and accompanying audio is: \"$transcript\". Combine the visual content and spoken explanation to give an exact, insightful answer."
                    val result = repository.askScreenAi(
                        prompt = prompt,
                        queryType = QueryType.SCREEN_AND_AUDIO,
                        images = if (currentScreen != null) listOf(currentScreen) else emptyList(),
                        chatHistory = sessionMessages.value
                    )

                    result.onSuccess { answer ->
                        bubbleState.value = BubbleState.IDLE
                        sessionMessages.value = sessionMessages.value + ChatMessage(
                            role = "model",
                            text = answer,
                            queryType = QueryType.SCREEN_AND_AUDIO
                        )
                        if (prefs.isTtsEnabled) {
                            ttsManager.speak(answer, prefs.ttsSpeed)
                        }
                    }.onFailure { err ->
                        bubbleState.value = BubbleState.ERROR
                        sessionMessages.value = sessionMessages.value + ChatMessage(
                            role = "model",
                            text = "Analysis failed: ${err.message}",
                            queryType = QueryType.SCREEN_AND_AUDIO
                        )
                    }
                    } finally {
                        audioAnswerMutex.unlock()
                        if (audioCaptureManager.isListening()) {
                            bubbleState.value = BubbleState.LISTENING_AUDIO
                            liveCaptions.value = "Listening continuously..."
                        }
                    }
                }
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    private fun startVoiceInput() {
        bubbleState.value = BubbleState.LISTENING_AUDIO
        liveCaptions.value = "Listening to your voice..."
        audioCaptureManager.startListening { speech ->
            liveCaptions.value = ""
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
                bubbleState.value = BubbleState.ERROR
                sessionMessages.value = sessionMessages.value + ChatMessage(
                    role = "model",
                    text = "Could not explain: ${err.message}",
                    queryType = QueryType.TEXT
                )
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
                bubbleState.value = BubbleState.ERROR
                sessionMessages.value = sessionMessages.value + ChatMessage(
                    role = "model",
                    text = "Follow-up error: ${err.message}",
                    queryType = queryType
                )
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
