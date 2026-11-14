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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
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

    /**
     * Send AI requests only through the authenticated Netlify backend.
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
            val user = FirebaseAuth.getInstance().currentUser
                ?: throw Exception("SIGN_IN_REQUIRED: Open the Account tab and sign in before using AskScreen AI.")
            val backendUrl = prefs.backendUrl.trim()
            if (backendUrl.isBlank() || !backendUrl.startsWith("https://")) {
                throw Exception("BACKEND_REQUIRED: Add your existing Netlify AskScreen endpoint in Settings before continuing.")
            }
            val answerText = callCustomBackend(
                backendUrl = backendUrl, prompt = prompt, model = selectedModel,
                queryType = queryType, images = images, audioBase64 = audioBase64,
                audioMimeType = audioMimeType, files = files, chatHistory = chatHistory,
                idToken = user.getIdToken(false).await().token
                    ?: throw Exception("Could not verify your account. Please sign in again.")
            )
            val modelUsed = selectedModel

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
     * Fallback for custom Netlify backend if configured.
     */
    private fun callCustomBackend(
        backendUrl: String,
        prompt: String,
        model: String,
        queryType: QueryType,
        images: List<Bitmap>,
        audioBase64: String?,
        audioMimeType: String?,
        files: List<MediaItem>,
        chatHistory: List<ChatMessage>,
        idToken: String?
    ): String {
        val rootJson = JSONObject()
        rootJson.put("prompt", prompt)
        rootJson.put("model", model)
        rootJson.put("queryType", queryType.name)
        rootJson.put("feature", when (queryType) {
            QueryType.AUDIO, QueryType.VOICE, QueryType.SCREEN_AND_AUDIO -> "spoken"
            QueryType.TRANSCRIPTION -> "transcription"
            else -> "screen"
        })

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
            .header("Authorization", "Bearer ${idToken.orEmpty()}")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorJson = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val serverMsg = errorJson.optString("error", "")
                if (errorJson.optString("code") == "SUBSCRIPTION_REQUIRED") throw Exception("SUBSCRIPTION_REQUIRED:${errorJson.optString("feature")}")
                if (response.code == 401) throw Exception("SIGN_IN_REQUIRED: Please sign in again from the Account tab.")
                val errorCode = errorJson.optString("code", "")
                val requestId = response.header("X-Request-Id").orEmpty()
                if (response.code == 429 || errorCode == "RATE_LIMITED") {
                    val retryAfter = response.header("Retry-After")
                        ?.toLongOrNull()
                        ?: errorJson.optLong("retryAfterSeconds", 60L)
                    throw Exception("RATE_LIMITED:${retryAfter.coerceAtLeast(1)}")
                }
                val diagnostic = if (requestId.isNotBlank()) " (Request ID: $requestId)" else ""
                throw Exception(
                    "Backend error (${response.code}): " +
                        serverMsg.ifBlank { "The server could not complete the request." } + diagnostic
                )
            }

            val json = JSONObject(responseBody)
            if (json.optBoolean("success", false)) {
                return json.optString("answer", "No answer received.")
            } else {
                val error = json.optString("error", "Unknown backend error")
                throw Exception(error)
            }
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
