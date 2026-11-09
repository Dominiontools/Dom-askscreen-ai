package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val queryType: String,
    val questionText: String,
    val answerText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val thumbnailUri: String? = null,
    val modelName: String = "gemini-2.5-flash",
    val isFavorite: Boolean = false
)
