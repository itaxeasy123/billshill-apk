package com.example.data.dao

import androidx.room.*
import com.example.data.model.CrashLog
import com.example.data.model.MonthlyArchive
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    // --- CrashLog Operations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrashLog(log: CrashLog): Long

    @Query("SELECT * FROM crash_logs WHERE timestamp < :cutoffTimestamp ORDER BY timestamp ASC")
    suspend fun getLogsOlderThan(cutoffTimestamp: Long): List<CrashLog>

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC")
    suspend fun getAllCrashLogs(): List<CrashLog>

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC")
    fun getAllCrashLogsFlow(): Flow<List<CrashLog>>

    @Query("DELETE FROM crash_logs WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteLogsOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM crash_logs")
    suspend fun deleteAllCrashLogs()

    // --- MonthlyArchive Operations ---
    @Query("SELECT * FROM monthly_archives WHERE monthYearKey = :monthYearKey")
    suspend fun getMonthlyArchive(monthYearKey: String): MonthlyArchive?

    @Query("SELECT * FROM monthly_archives ORDER BY monthYearKey DESC")
    suspend fun getAllMonthlyArchives(): List<MonthlyArchive>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMonthlyArchive(archive: MonthlyArchive)
}
