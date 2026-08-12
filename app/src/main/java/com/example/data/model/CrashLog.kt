package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "crash_logs")
data class CrashLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val logContent: String,
    val timestamp: Long = System.currentTimeMillis()
)
