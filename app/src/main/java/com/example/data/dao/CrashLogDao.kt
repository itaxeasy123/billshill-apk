package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.CrashLog
import kotlinx.coroutines.flow.Flow

@Dao
interface CrashLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CrashLog): Long

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<CrashLog>

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): Flow<List<CrashLog>>

    @Query("DELETE FROM crash_logs")
    suspend fun deleteAllLogs()
}
