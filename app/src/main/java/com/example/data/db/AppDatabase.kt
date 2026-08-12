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

/**
 * Adds `ledgers.systemCode` and repairs the split cash ledger (C2).
 *
 * The seed writes a ledger named "Cash in Hand"; the posting engine looked one up by the
 * exact, case-sensitive name "Cash-in-hand". That never matched, so every Receipt,
 * Payment and Contra created a second cash ledger — leaving the Trial Balance showing one
 * cash account holding the opening balance and never moving, and another holding every
 * movement with a **credit** balance, which is an impossibility for a cash account and
 * now renders on the face of the Balance Sheet.
 *
 * The merge has to be deterministic and silent: a Migration runs headless on a background
 * thread and cannot ask the user which account to keep. The canonical row is therefore
 * the lowest id among loose-name matches — the account the user actually set up, which
 * carries their opening balance. Postings from the runtime-created duplicates are
 * re-pointed onto it, and a duplicate is deleted only if it carried no opening balance
 * and has no postings left. Anything holding real data is left alone rather than merged
 * on a guess.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ledgers ADD COLUMN systemCode TEXT")

        // ---- Cash ----
        db.execSQL(
            """
            UPDATE ledgers SET systemCode = 'CASH' WHERE id = (
                SELECT MIN(id) FROM ledgers
                 WHERE REPLACE(REPLACE(LOWER(name), ' ', ''), '-', '') = 'cashinhand')
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE journal_entries
               SET ledgerId = (SELECT id FROM ledgers WHERE systemCode = 'CASH')
             WHERE EXISTS (SELECT 1 FROM ledgers WHERE systemCode = 'CASH')
               AND ledgerId IN (
                   SELECT id FROM ledgers
                    WHERE REPLACE(REPLACE(LOWER(name), ' ', ''), '-', '') = 'cashinhand'
                      AND systemCode IS NULL AND openingBalance = 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            DELETE FROM ledgers
             WHERE REPLACE(REPLACE(LOWER(name), ' ', ''), '-', '') = 'cashinhand'
               AND systemCode IS NULL AND openingBalance = 0
               AND NOT EXISTS (SELECT 1 FROM journal_entries je WHERE je.ledgerId = ledgers.id)
            """.trimIndent()
        )

        // ---- Bank ----
        // Older books carry the hardcoded "HDFC Bank Ltd" the posting engine invented;
        // newer ones carry the neutral seeded "Bank Account". Prefer a real bank-group
        // ledger, oldest first, so whichever exists is adopted rather than duplicated.
        db.execSQL(
            """
            UPDATE ledgers SET systemCode = 'BANK' WHERE id = (
                SELECT MIN(l.id) FROM ledgers l
                  JOIN ledger_groups lg ON l.groupId = lg.id
                 WHERE REPLACE(REPLACE(LOWER(lg.name), ' ', ''), '-', '') = 'bankaccounts'
                   AND l.systemCode IS NULL)
            """.trimIndent()
        )
    }
}

/**
 * Splits the shared GST ledgers into Output (liability) and Input (credit) — H1.
 *
 * One "CGST" ledger carried both the tax collected on sales and the credit paid on
 * purchases, so the two netted against each other inside a single balance: ₹900 payable
 * and ₹540 receivable showed as ₹360, concealing both. Unutilised ITC could not be read
 * off the Balance Sheet at all.
 *
 * Data-only — no table, column or index changes — so v11 carries the same identityHash
 * as v10 and Room's schema validation passes unchanged. Existing postings are re-pointed
 * by the voucher type that created them, which is the only reliable signal for whether a
 * given entry was output tax or input credit.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val heads = "('CGST','SGST','IGST','CESS')"

        db.execSQL(
            """
            INSERT INTO ledgers (name, groupId, groupName, category, openingBalance,
                                 balanceType, currentBalance, pincode, city, state, country, gstin)
            SELECT 'Output ' || l.name, l.groupId, l.groupName, 'LIABILITY', 0, 'CR', 0, '', '', '', 'India', ''
              FROM ledgers l
             WHERE l.name IN $heads
               AND NOT EXISTS (SELECT 1 FROM ledgers x WHERE x.name = 'Output ' || l.name)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ledgers (name, groupId, groupName, category, openingBalance,
                                 balanceType, currentBalance, pincode, city, state, country, gstin)
            SELECT 'Input ' || l.name, l.groupId, l.groupName, 'ASSET', 0, 'DR', 0, '', '', '', 'India', ''
              FROM ledgers l
             WHERE l.name IN $heads
               AND NOT EXISTS (SELECT 1 FROM ledgers x WHERE x.name = 'Input ' || l.name)
            """.trimIndent()
        )

        listOf(
            "Output" to "('SALES','SALES_RETURN')",
            "Input" to "('PURCHASE','PURCHASE_RETURN')"
        ).forEach { (side, types) ->
            db.execSQL(
                """
                UPDATE journal_entries
                   SET ledgerId = (SELECT n.id FROM ledgers n
                                    WHERE n.name = '$side ' || (SELECT o.name FROM ledgers o
                                                                 WHERE o.id = journal_entries.ledgerId))
                 WHERE ledgerId IN (SELECT id FROM ledgers WHERE name IN $heads)
                   AND (SELECT v.voucherType FROM vouchers v WHERE v.id = journal_entries.voucherId) IN $types
                """.trimIndent()
            )
        }

        // Only remove a shared ledger once nothing points at it any more. Anything still
        // referenced — a JOURNAL or CONTRA posting, whose side cannot be inferred — is
        // left in place rather than guessed at.
        db.execSQL(
            """
            DELETE FROM ledgers
             WHERE name IN $heads
               AND openingBalance = 0
               AND NOT EXISTS (SELECT 1 FROM journal_entries je WHERE je.ledgerId = ledgers.id)
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
    version = 11,
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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
