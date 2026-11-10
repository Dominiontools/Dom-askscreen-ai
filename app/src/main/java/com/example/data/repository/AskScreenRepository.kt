package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.HistoryDao
import com.example.data.local.HistoryEntity
import com.example.data.local.PreferencesManager
import com.example.data.model.ChatMessage
import com.example.data.model.MediaItem
import com.example.data.model.QueryType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "AskScreenRepository"

class AskScreenRepository(private val context: Context) {
    private val prefs = PreferencesManager(context)
    private val db = AppDatabase.getDatabase(context)
    private val historyDao: HistoryDao = db.historyDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val allHistory: Flow<List<HistoryEntity>> = historyDao.getAllHistory()

    fun searchHistory(query: String): Flow<List<HistoryEntity>> = historyDao.searchHistory(query)

    suspend fun deleteHistory(item: HistoryEntity) = historyDao.deleteHistory(item)

    suspend fun clearHistory() = historyDao.clearAllHistory()

    private val systemInstructionContent = content {
        text(
            "You are AskScreen AI, an on-screen reading and learning assistant. Carefully inspect the visible " +
            "content, screenshot, audio transcript, or uploaded material. For multiple-choice practice or " +
            "self-study questions, return only the option letter and its answer text (for example: 'B. Oxygen'). " +
            "Do not add an explanation unless the user explicitly asks for one or the question itself requests " +
            "an explanation. For non-multiple-choice questions, give a direct, concise answer. If several " +
            "independent questions are visible, answer them separately and in screen order. If the text or " +
            "options are unclear, say what cannot be read instead of guessing. Never claim to have clicked or submitted anything. Only provide answer text; external interaction is handled separately by an explicit user-enabled practice automation setting."
        )
    }

    /**
     * Lazily get Firebase AI model based on configured name.
     */
    private fun getFirebaseModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = when (modelName) {
            "gemini-3.5-flash-lite" -> "gemini-3.5-flash-lite"
            "gemini-4-argon" -> "gemini-4-argon"
            else -> "gemini-3.8-flash" // Default recommended model
        },
        systemInstruction = systemInstructionContent
    )

    /**
     * Ask Gemini via Firebase AI Logic (server-side authenticated) or optional backend.
     */
    suspend fun askScreenAi(
        prompt: String,
        queryType: QueryType,
        images: List<Bitmap> = emptyList(),
        audioBase64: String? = null,
        audioMimeType: String? = null,
        files: List<MediaItem> = emptyList(),
        chatHistory: List<ChatMessage> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val selectedModel = prefs.geminiModel.ifEmpty { "gemini-3.8-flash" }
            val backendUrl = prefs.backendUrl.trim()

            val answerText: String
            val modelUsed: String

            if (backendUrl.isNotEmpty() && backendUrl.startsWith("http")) {
                // Route through custom serverless backend if user entered one
                answerText = callCustomBackend(
                    backendUrl = backendUrl,
                    prompt = prompt,
                    model = selectedModel,
                    images = images,
                    audioBase64 = audioBase64,
                    audioMimeType = audioMimeType,
                    files = files,
                    chatHistory = chatHistory
                )
                modelUsed = selectedModel
            } else {
                // Primary Path: Firebase AI Logic (No client API keys needed!)
                answerText = try {
                    callFirebaseAiLogic(
                        modelName = selectedModel,
                        prompt = prompt,
                        images = images,
                        audioBase64 = audioBase64,
                        files = files,
                        chatHistory = chatHistory
                    )
                } catch (e: TimeoutCancellationException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw Exception(
                        "No backend link is saved and Firebase AI Logic failed (" + (e.message ?: "unknown error") +
                            "). Open Settings, paste your Netlify link and tap Save & Test Connection.",
                        e
                    )
                }
                modelUsed = selectedModel
            }

            // Save to local database if history is enabled
            if (prefs.isPermanentHistoryEnabled) {
                var thumbnailPath: String? = null
                if (images.isNotEmpty()) {
                    thumbnailPath = saveThumbnailLocally(images.first())
                }
                historyDao.insertHistory(
                    HistoryEntity(
                        queryType = queryType.name,
                        questionText = prompt,
                        answerText = answerText,
                        thumbnailUri = thumbnailPath,
                        modelName = modelUsed
                    )
                )
            }

            Result.success(answerText)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI generation timed out", e)
            Result.failure(Exception("Generation timed out after 60 seconds. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error asking AI", e)
            Result.failure(e)
        }
    }

    /**
     * Executes multimodal inference using Firebase AI Logic.
     */
    private suspend fun callFirebaseAiLogic(
        modelName: String,
        prompt: String,
        images: List<Bitmap>,
        audioBase64: String?,
        files: List<MediaItem>,
        chatHistory: List<ChatMessage>
    ): String {
        val model = getFirebaseModel(modelName)

        val promptContent = content {
            // Attach captured screen images
            for (bitmap in images) {
                val scaled = scaleBitmapIfNeeded(bitmap, 1440)
                image(scaled)
            }

            // Attach uploaded file images if any
            for (file in files) {
                if (file.mimeType.startsWith("image/")) {
                    try {
                        val bytes = Base64.decode(file.base64Data, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            image(scaleBitmapIfNeeded(bmp, 1440))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode attached file bitmap", e)
                    }
                } else {
                    text("[Attached Document: ${file.fileName ?: "File"} (${file.mimeType})]")
                }
            }

            // Attach user prompt and context
            text(prompt)
        }

        val response = withTimeout(60_000L) {
            if (chatHistory.isNotEmpty()) {
                val historyContents = chatHistory.takeLast(6).map { turn ->
                    content(if (turn.role == "model") "model" else "user") {
                        text(turn.text)
                    }
                }
                val chat = model.startChat(history = historyContents)
                chat.sendMessage(promptContent)
            } else {
                model.generateContent(promptContent)
            }
        }

        val text = response.text
        if (!text.isNullOrBlank()) {
            return text
        } else {
            throw Exception("Model returned an empty response.")
        }
    }

    /**
     * Fallback for custom Netlify backend if configured.
     */
    private fun callCustomBackend(
        backendUrl: String,
        prompt: String,
        model: String,
        images: List<Bitmap>,
        audioBase64: String?,
        audioMimeType: String?,
        files: List<MediaItem>,
        chatHistory: List<ChatMessage>
    ): String {
        val rootJson = JSONObject()
        rootJson.put("prompt", prompt)
        rootJson.put("model", model)

        val imagesArray = JSONArray()
        for (img in images) {
            val stream = ByteArrayOutputStream()
            val scaled = scaleBitmapIfNeeded(img, 1440)
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            val item = JSONObject()
            item.put("mimeType", "image/jpeg")
            item.put("data", base64)
            imagesArray.put(item)
        }
        rootJson.put("images", imagesArray)

        if (!audioBase64.isNullOrEmpty() && !audioMimeType.isNullOrEmpty()) {
            val audioObj = JSONObject()
            audioObj.put("mimeType", audioMimeType)
            audioObj.put("data", audioBase64)
            rootJson.put("audio", audioObj)
        }

        val filesArray = JSONArray()
        for (f in files) {
            val item = JSONObject()
            item.put("mimeType", f.mimeType)
            item.put("data", f.base64Data)
            filesArray.put(item)
        }
        rootJson.put("files", filesArray)

        val historyArray = JSONArray()
        for (h in chatHistory.takeLast(6)) {
            val item = JSONObject()
            item.put("role", if (h.role == "model") "model" else "user")
            item.put("text", h.text)
            historyArray.put(item)
        }
        rootJson.put("history", historyArray)

        val requestBody = rootJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(backendUrl)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val serverMsg = try { JSONObject(responseBody).optString("error", "") } catch (e: Exception) { "" }
            throw Exception("Backend error (" + response.code + "): " + serverMsg.ifBlank { responseBody.take(200) })
        }

        val json = JSONObject(responseBody)
        if (json.optBoolean("success", false)) {
            return json.optString("answer", "No answer received.")
        } else {
            val error = json.optString("error", "Unknown backend error")
            throw Exception(error)
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (width > height) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).toInt()
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun saveThumbnailLocally(bitmap: Bitmap): String? {
        return try {
            val thumbDir = File(context.filesDir, "thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()
            val file = File(thumbDir, "thumb_${System.currentTimeMillis()}.jpg")
            val thumb = Bitmap.createScaledBitmap(bitmap, 200, (200f * bitmap.height / bitmap.width).toInt(), true)
            val fos = FileOutputStream(file)
            thumb.compress(Bitmap.CompressFormat.JPEG, 75, fos)
            fos.flush()
            fos.close()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
