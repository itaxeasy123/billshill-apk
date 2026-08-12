package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_archives")
data class MonthlyArchive(
    @PrimaryKey
    val monthYearKey: String, // 'yyyy-MM' string index pattern
    val archivedText: String,
    val archivedAt: Long = System.currentTimeMillis()
)
