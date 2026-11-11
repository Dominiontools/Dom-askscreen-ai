package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.WindowSizePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("askscreen_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BUBBLE_SIZE = "bubble_size" // 48, 60, 72 dp
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity" // 40 to 100
        private const val KEY_BUBBLE_POS_X = "bubble_pos_x"
        private const val KEY_BUBBLE_POS_Y = "bubble_pos_y"
        private const val KEY_ASSISTANT_ENABLED = "assistant_enabled"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_API_KEY_OVERRIDE = "api_key_override"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_WINDOW_PRESET = "window_preset"
        private const val KEY_WINDOW_POS_X = "window_pos_x"
        private const val KEY_WINDOW_POS_Y = "window_pos_y"
        private const val KEY_WINDOW_WIDTH = "window_width"
        private const val KEY_WINDOW_HEIGHT = "window_height"
        private const val KEY_TEXT_SIZE_SCALE = "text_size_scale"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_PERMANENT_HISTORY = "permanent_history"
        private const val KEY_AUTO_SUBMIT = "auto_submit_practice"
        private const val KEY_AUTO_MONITOR_INTERVAL = "auto_monitor_interval_ms"
        private const val KEY_AUDIO_LANGUAGE_TAG = "audio_language_tag"
    }

    private val _assistantEnabledFlow = MutableStateFlow(isAssistantEnabled)
    val assistantEnabledFlow: StateFlow<Boolean> = _assistantEnabledFlow

    var isAssistantEnabled: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASSISTANT_ENABLED, value).apply()
            _assistantEnabledFlow.value = value
        }

    var bubbleSizeDp: Int
        get() = prefs.getInt(KEY_BUBBLE_SIZE, 60) // default 60dp Medium
        set(value) = prefs.edit().putInt(KEY_BUBBLE_SIZE, value).apply()

    var bubbleOpacityPercent: Int
        get() = prefs.getInt(KEY_BUBBLE_OPACITY, 90) // default 90%
        set(value) = prefs.edit().putInt(KEY_BUBBLE_OPACITY, value).apply()

    var bubblePosX: Int
        get() = prefs.getInt(KEY_BUBBLE_POS_X, 20)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_POS_X, value).apply()

    var bubblePosY: Int
        get() = prefs.getInt(KEY_BUBBLE_POS_Y, 200)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_POS_Y, value).apply()

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim()).apply()

    var apiKeyOverride: String
        get() = prefs.getString(KEY_API_KEY_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY_OVERRIDE, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    var windowPreset: WindowSizePreset
        get() {
            val name = prefs.getString(KEY_WINDOW_PRESET, WindowSizePreset.MEDIUM.name)
            return try {
                WindowSizePreset.valueOf(name ?: WindowSizePreset.MEDIUM.name)
            } catch (e: Exception) {
                WindowSizePreset.MEDIUM
            }
        }
        set(value) = prefs.edit().putString(KEY_WINDOW_PRESET, value.name).apply()

    var windowPosX: Int
        get() = prefs.getInt(KEY_WINDOW_POS_X, 40)
        set(value) = prefs.edit().putInt(KEY_WINDOW_POS_X, value).apply()

    var windowPosY: Int
        get() = prefs.getInt(KEY_WINDOW_POS_Y, 120)
        set(value) = prefs.edit().putInt(KEY_WINDOW_POS_Y, value).apply()

    var windowWidth: Int
        get() = prefs.getInt(KEY_WINDOW_WIDTH, 0)
        set(value) = prefs.edit().putInt(KEY_WINDOW_WIDTH, value).apply()

    var windowHeight: Int
        get() = prefs.getInt(KEY_WINDOW_HEIGHT, 0)
        set(value) = prefs.edit().putInt(KEY_WINDOW_HEIGHT, value).apply()

    var textSizeScale: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SIZE_SCALE, value).apply()

    var isTtsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    var isPermanentHistoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERMANENT_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_PERMANENT_HISTORY, value).apply()

    /** Explicit opt-in; disabled by default. Use only on practice pages where automation is allowed. */
    var isAutoSubmitEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SUBMIT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SUBMIT, value).apply()

    /** Speech recognition language: device default, English (Nigeria), or Yoruba (Nigeria). */
    var audioLanguageTag: String
        get() = prefs.getString(KEY_AUDIO_LANGUAGE_TAG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUDIO_LANGUAGE_TAG, value).apply()

    var autoMonitorIntervalMs: Long
        get() = prefs.getLong(KEY_AUTO_MONITOR_INTERVAL, 2200L).coerceIn(1500L, 10000L)
        set(value) = prefs.edit().putLong(KEY_AUTO_MONITOR_INTERVAL, value.coerceIn(1500L, 10000L)).apply()
}
