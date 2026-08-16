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

/** Adds the GST filing scheme so deadlines can follow QRMP rather than assuming monthly. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN filingScheme TEXT NOT NULL DEFAULT 'MONTHLY'")
    }
}

/**
 * Records how each voucher settled, and back-derives it for existing books.
 *
 * The cash-or-bank decision was inferred from a substring of the party's name (H3). The
 * postings already made that decision, so every existing voucher's real mode can be read
 * off its own journal entries: whichever of the CASH or BANK system ledgers it touched.
 * A voucher that touched neither settled on account, i.e. CREDIT.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vouchers ADD COLUMN paymentMode TEXT NOT NULL DEFAULT 'CASH'")

        listOf("BANK", "CASH").forEach { code ->
            db.execSQL(
                """
                UPDATE vouchers SET paymentMode = '$code'
                 WHERE EXISTS (
                     SELECT 1 FROM journal_entries je
                       JOIN ledgers l ON je.ledgerId = l.id
                      WHERE je.voucherId = vouchers.id AND l.systemCode = '$code')
                """.trimIndent()
            )
        }
        db.execSQL(
            """
            UPDATE vouchers SET paymentMode = 'CREDIT'
             WHERE NOT EXISTS (
                 SELECT 1 FROM journal_entries je
                   JOIN ledgers l ON je.ledgerId = l.id
                  WHERE je.voucherId = vouchers.id AND l.systemCode IN ('CASH', 'BANK'))
            """.trimIndent()
        )

        // A Contra touches BOTH cash and bank, so the passes above would settle it on
        // whichever ran last — every existing deposit would have come out as CASH, and
        // the posting branch reads CASH as "withdrawal". Amending an old bank deposit
        // would then have reversed it: Dr Cash / Cr Bank instead of Dr Bank / Cr Cash.
        // For a transfer the mode is where the money ENDED UP, i.e. the debited side.
        db.execSQL(
            """
            UPDATE vouchers SET paymentMode = (
                SELECT l.systemCode FROM journal_entries je
                  JOIN ledgers l ON je.ledgerId = l.id
                 WHERE je.voucherId = vouchers.id AND je.debitAmount > 0
                   AND l.systemCode IN ('CASH', 'BANK') LIMIT 1)
             WHERE voucherType = 'CONTRA'
               AND EXISTS (
                   SELECT 1 FROM journal_entries je
                     JOIN ledgers l ON je.ledgerId = l.id
                    WHERE je.voucherId = vouchers.id AND je.debitAmount > 0
                      AND l.systemCode IN ('CASH', 'BANK'))
            """.trimIndent()
        )
    }
}

/**
 * Freezes the cost basis on each stock line.
 *
 * Historical movement was valued at the item's CURRENT `avgCostPrice`, so a single
 * purchase at a different price retroactively revalued every past movement — the derived
 * opening-stock figure drifted from the posted one and the Balance Sheet went out by the
 * difference. Backfilling to the current average reproduces today's behaviour exactly for
 * existing rows, so nothing regresses; only future postings become exact.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE voucher_items ADD COLUMN costRate REAL NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE voucher_items SET costRate = COALESCE(
                (SELECT i.avgCostPrice FROM inventory_items i WHERE i.id = voucher_items.itemId), 0)
            """.trimIndent()
        )
    }
}

/**
 * Adds the business's own UPI ID.
 *
 * There was nowhere to record one, so the invoice and the share-payment link both
 * fabricated a payee as "<the user's mobile number>@upi". "@upi" is a live NPCI handle,
 * so that address is syntactically valid and may well resolve — to whoever registered
 * that number on BHIM, which the app cannot verify and the business may not be. A
 * customer following it could pay a stranger. The empty-state copy compounded it by
 * telling users to "Add a UPI ID in Settings", where no such field existed.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN upiId TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Adds the business's declared aggregate turnover for the PRECEDING financial year.
 *
 * GSTR-1's `gt` is that figure; `cur_gt` is the current FY to date. Both were assigned the
 * single month's taxable value, which is how the error is visible in the payload — two
 * fields with different definitions carrying an identical number.
 *
 * Declared, not derived. s.2(6) aggregate turnover is PAN-level and all-India, spanning
 * every GSTIN under the same PAN and including exempt, nil-rated, non-GST and export
 * supplies; this book holds one GSTIN's taxable outward supplies and cannot tell whether
 * the two coincide. It may also not span the previous FY at all — a book opened in October
 * has no April data, and deriving from it would DECLARE a turnover of zero rather than
 * leave the field unanswered. -1.0 means "not declared" and is distinguishable from 0.0.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE users ADD COLUMN previousFyAggregateTurnover REAL NOT NULL DEFAULT -1.0")
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
    version = 16,
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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
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
