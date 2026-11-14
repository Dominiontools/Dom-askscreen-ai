package com.example.data.model

enum class QueryType(val displayName: String) {
    SCREEN("Screen Reading"),
    AUDIO("Audio Listening"),
    SCREEN_AND_AUDIO("Screen & Audio"),
    REGION("Selected Region"),
    FILE("File Upload"),
    VOICE("Voice Query"),
    TRANSCRIPTION("Transcription"),
    TEXT("Follow-up Text")
}

enum class BubbleState {
    IDLE,
    CAPTURING_SCREEN,
    LISTENING_AUDIO,
    PROCESSING_AI,
    ERROR
}

enum class WindowSizePreset(val label: String, val widthDp: Int, val heightDp: Int) {
    SMALL("Small", 280, 360),
    MEDIUM("Medium", 340, 480),
    LARGE("Large", 390, 600),
    FULLSCREEN("Full", -1, -1) // -1 signifies match parent / full display
}

data class MediaItem(
    val mimeType: String,
    val base64Data: String,
    val fileName: String? = null
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" or "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val queryType: QueryType = QueryType.TEXT,
    val mediaThumbnail: String? = null
)

data class AskBackendRequest(
    val prompt: String,
    val model: String = "gemini-2.5-flash",
    val images: List<MediaPayload> = emptyList(),
    val audio: MediaPayload? = null,
    val files: List<MediaPayload> = emptyList(),
    val history: List<HistoryTurn> = emptyList(),
    val systemInstruction: String? = null
)

data class MediaPayload(
    val mimeType: String,
    val data: String // Base64
)

data class HistoryTurn(
    val role: String,
    val text: String
)

data class AskBackendResponse(
    val success: Boolean,
    val answer: String? = null,
    val model: String? = null,
    val error: String? = null,
    val finishReason: String? = null
)
