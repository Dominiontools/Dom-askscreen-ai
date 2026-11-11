package com.example.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

class AudioCaptureManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _liveCaptions = MutableStateFlow("")
    val liveCaptions: StateFlow<String> = _liveCaptions

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb

    private var onSpeechResultCallback: ((String) -> Unit)? = null
    @Volatile private var continuousMode = false
    private var restartRunnable: Runnable? = null
    @Volatile private var recognitionLanguageTag: String? = null

    private fun scheduleRecognitionRestart(delayMs: Long = 650L) {
        if (!continuousMode) return
        restartRunnable?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            if (continuousMode) startRecognitionRequest()
        }
        restartRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun startRecognitionRequest() {
        if (!continuousMode && onSpeechResultCallback == null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _isListening.value = false
            _liveCaptions.value = "Speech recognition is not available on this device."
            return
        }
        try {
            if (speechRecognizer == null) initSpeechRecognizer()
            val recognizer = speechRecognizer
                ?: throw IllegalStateException("Speech recognition could not be initialized.")
            val languageTag = recognitionLanguageTag ?: Locale.getDefault().toLanguageTag()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e("AudioCaptureManager", "Error starting recognition request", e)
            _isListening.value = false
            _liveCaptions.value = "Could not start speech recognition. Retrying..."
            if (continuousMode) scheduleRecognitionRestart(1400L)
        }
    }
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordedAudioStream: ByteArrayOutputStream? = null

    init {
        mainHandler.post {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                initSpeechRecognizer()
            }
        }
    }

    private fun initSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        _audioRmsDb.value = rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.w("AudioCaptureManager", "Speech recognition error code: $error")
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out."
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
                            else -> "Audio capture error ($error)."
                        }
                        if (_liveCaptions.value.isEmpty()) {
                            _liveCaptions.value = errorMessage
                        }
                        _isListening.value = false
                        if (continuousMode) scheduleRecognitionRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 700L)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _liveCaptions.value = text
                        _isListening.value = false
                        if (text.isNotBlank()) onSpeechResultCallback?.invoke(text)
                        if (continuousMode) scheduleRecognitionRestart(500L)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotEmpty()) {
                            _liveCaptions.value = text
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e("AudioCaptureManager", "Failed to create SpeechRecognizer", e)
        }
    }

    /**
     * Start live speech listening for voice questions or audio transcription.
     */
    fun startListening(languageTag: String? = null, onResult: (String) -> Unit) {
        recognitionLanguageTag = languageTag?.takeIf { it.isNotBlank() }
        continuousMode = false
        restartRunnable?.let(mainHandler::removeCallbacks)
        onSpeechResultCallback = onResult
        _liveCaptions.value = "Listening to audio..."
        _isListening.value = true
        mainHandler.post { startRecognitionRequest() }
    }

    /** Keeps microphone speech recognition active by starting a fresh request after each result. */
    fun startContinuousListening(languageTag: String? = null, onResult: (String) -> Unit) {
        recognitionLanguageTag = languageTag?.takeIf { it.isNotBlank() }
        continuousMode = true
        onSpeechResultCallback = onResult
        _liveCaptions.value = "Listening continuously..."
        _isListening.value = true
        mainHandler.post {
            restartRunnable?.let(mainHandler::removeCallbacks)
            try { speechRecognizer?.cancel() } catch (_: Exception) { }
            startRecognitionRequest()
        }
    }

    /**
     * Stop active audio capture / listening session.
     */
    fun stopListening() {
        continuousMode = false
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
        onSpeechResultCallback = null
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                // ignore
            }
            _isListening.value = false
        }
    }

    fun destroy() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
