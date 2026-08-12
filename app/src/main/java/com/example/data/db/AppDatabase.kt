package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.AccountingDao
import com.example.data.dao.CrashLogDao
import com.example.data.dao.TelemetryDao
import com.example.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `crash_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logContent` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `monthly_archives` (`monthYearKey` TEXT NOT NULL, `archivedText` TEXT NOT NULL, `archivedAt` INTEGER NOT NULL, PRIMARY KEY(`monthYearKey`))"
        )
    }
}

/**
 * Data-only repair. The schema is byte-identical to v8 — no table, column or index
 * changes — so the exported 9.json matches 8.json structurally and Room's schema
 * validation passes unchanged.
 *
 * `ledgers.groupName` and `ledgers.category` are denormalised copies of the ledger's
 * group. Neither the seed nor `createLedger` ever set them, so on every existing device
 * they hold the entity defaults "General Ledgers" / EXPENSE while `groupId` points at
 * the correct group. Reports join `ledger_groups` and were unaffected — but the Edit
 * Ledger dialog pre-fills from these columns, so opening any ledger and saving resolved
 * the name "General Ledgers", created that group, and reassigned `groupId` to it. One
 * edit of a customer's opening balance moved their receivable out of Assets and into
 * expenses, and the Balance Sheet still balanced because net profit absorbed it exactly.
 *
 * This realigns the copies with the group each ledger actually belongs to.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE ledgers
               SET groupName = (SELECT lg.name FROM ledger_groups lg WHERE lg.id = ledgers.groupId),
                   category  = (SELECT lg.category FROM ledger_groups lg WHERE lg.id = ledgers.groupId)
             WHERE EXISTS (SELECT 1 FROM ledger_groups lg WHERE lg.id = ledgers.groupId)
            """.trimIndent()
        )
    }
}

class AccountingTypeConverters {
    @TypeConverter
    fun fromBusinessType(value: BusinessType): String = value.name

    @TypeConverter
    fun toBusinessType(value: String): BusinessType = enumValueOf(value)

    @TypeConverter
    fun fromVoucherType(value: VoucherType): String = value.name

    @TypeConverter
    fun toVoucherType(value: String): VoucherType = enumValueOf(value)

    @TypeConverter
    fun fromLedgerCategory(value: LedgerCategory): String = value.name

    @TypeConverter
    fun toLedgerCategory(value: String): LedgerCategory = enumValueOf(value)

    @TypeConverter
    fun fromBalanceType(value: BalanceType): String = value.name

    @TypeConverter
    fun toBalanceType(value: String): BalanceType = enumValueOf(value)

    @TypeConverter
    fun fromReconciliationStatus(value: ReconciliationStatus): String = value.name

    @TypeConverter
    fun toReconciliationStatus(value: String): ReconciliationStatus = enumValueOf(value)
}

@Database(
    entities = [
        UserEntity::class,
        LedgerGroupEntity::class,
        LedgerEntity::class,
        VoucherEntity::class,
        JournalEntryEntity::class,
        InventoryItemEntity::class,
        VoucherItemEntity::class,
        GstTaxDetailEntity::class,
        SyncLogEntity::class,
        VoucherTypeConfigEntity::class,
        ReconciliationDiscrepancyEntity::class,
        CrashLog::class,
        MonthlyArchive::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(AccountingTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountingDao(): AccountingDao
    abstract fun crashLogDao(): CrashLogDao
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "indian_mobile_accounting.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    // Deliberately NOT fallbackToDestructiveMigration(): with it enabled, any
                    // schema bump lacking a matching Migration silently drops every table and
                    // recreates it — destroying the user's books with no warning and no backup.
                    // Failing loudly on a missing migration is strictly safer for an accounting
                    // app. Downgrades stay destructive because there is no forward-compatible
                    // way to read a newer schema with older entity definitions.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .addCallback(PrepopulateCallback(context))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class PrepopulateCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = getDatabase(context).accountingDao()
                    com.example.service.DatabaseSeedEngine.seedDefaultData(dao)
                }
            }
        }
    }
}
