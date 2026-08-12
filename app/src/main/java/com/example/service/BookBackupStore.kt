package com.example.service

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.repository.AccountingRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one place backups are written and read on disk.
 *
 * Manual and automatic backups previously wrote different, mutually unreadable things —
 * the worker wrote a Moshi map of two tables, the manual button wrote nothing at all and
 * reported success anyway, and neither produced a file the restore path could consume.
 * Both now go through here, so anything the app writes, the app can restore.
 */
object BookBackupStore {

    private const val DIR = "backups"
    private const val LATEST = "auto_backup_latest.json"
    private const val KEEP = 10

    fun backupDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun latestBackupFile(context: Context): File = File(backupDir(context), LATEST)

    /** All backups, newest first. */
    fun listBackups(context: Context): List<File> =
        backupDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Writes a full-fidelity backup and returns the file.
     *
     * Throws on failure rather than swallowing it — the caller is responsible for
     * telling the user, and a backup that fails silently is worse than no backup at all
     * because it removes the reason to make another one.
     */
    suspend fun writeBackup(context: Context, nowMillis: Long = System.currentTimeMillis()): File {
        val repository = AppDatabase.getDatabase(context).let { AccountingRepository(it.accountingDao(), it) }
        val json = repository.exportBooksToJson(nowMillis)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date(nowMillis))
        val file = File(backupDir(context), "Backup_$stamp.json")
        file.writeText(json)
        latestBackupFile(context).writeText(json)

        pruneOldBackups(context)
        return file
    }

    /** Restores from a backup file. Returns the number of rows restored. */
    suspend fun restoreFrom(context: Context, file: File): Int {
        val repository = AppDatabase.getDatabase(context).let { AccountingRepository(it.accountingDao(), it) }
        return repository.restoreBooksFromJson(file.readText())
    }

    /** Keeps the most recent [KEEP] timestamped backups so the directory cannot grow without bound. */
    private fun pruneOldBackups(context: Context) {
        listBackups(context)
            .filter { it.name != LATEST }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    fun humanReadableSize(file: File): String {
        val bytes = file.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
