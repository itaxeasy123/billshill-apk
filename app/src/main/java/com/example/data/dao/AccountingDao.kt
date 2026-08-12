package com.example.data.dao

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

data class LedgerWithBalance(
    val id: Long,
    val name: String,
    val groupName: String,
    val category: LedgerCategory,
    val totalDebit: Double,
    val totalCredit: Double,
    val currentBalance: Double
)

data class GstSummaryReport(
    val totalOutputCgst: Double,
    val totalOutputSgst: Double,
    val totalOutputIgst: Double,
    val totalInputCgst: Double,
    val totalInputSgst: Double,
    val totalInputIgst: Double
)

data class LedgerTxEntry(
    val entryId: Long,
    val voucherId: Long,
    val voucherNo: String,
    val voucherType: VoucherType,
    val date: Long,
    val partyName: String,
    val narration: String,
    val debitAmount: Double,
    val creditAmount: Double
)

/**
 * One (month, category) bucket of real posted journal activity, used by the
 * Analytics "Monthly P&L Trend" chart. Aggregated from journal_entries against
 * ledger group category (accrual basis), NOT from voucher.totalAmount — that
 * field is GST-inclusive and would book output tax as revenue.
 */
data class MonthlyPnlRow(
    val monthKey: String,           // "yyyy-MM", device-local calendar month
    val category: LedgerCategory,   // REVENUE or EXPENSE only
    val totalDebit: Double,
    val totalCredit: Double
)

/** Net cash/bank movement for a single local calendar day. */
data class DailyNetMovement(
    val dayBucket: String,          // "yyyy-MM-dd", device-local calendar day
    val netMovement: Double
)

@Dao
interface AccountingDao {

    // User Operations
    @Query("SELECT * FROM users WHERE id = 'primary_user' LIMIT 1")
    fun getUserFlow(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = 'primary_user' LIMIT 1")
    suspend fun getUserSync(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUser(user: UserEntity)

    @Query("UPDATE users SET isLoggedIn = 0 WHERE id = 'primary_user'")
    suspend fun logoutUser()

    @Query("UPDATE users SET businessType = :type, enableInventory = :enableInventory WHERE id = 'primary_user'")
    suspend fun updateBusinessSettings(type: BusinessType, enableInventory: Boolean)

    // Ledger Group Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerGroup(group: LedgerGroupEntity): Long

    @Query("SELECT * FROM ledger_groups ORDER BY name ASC")
    fun getAllLedgerGroups(): Flow<List<LedgerGroupEntity>>

    @Query("SELECT * FROM ledger_groups WHERE name = :name LIMIT 1")
    suspend fun getLedgerGroupByName(name: String): LedgerGroupEntity?

    // Ledger Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedger(ledger: LedgerEntity): Long

    @Update
    suspend fun updateLedger(ledger: LedgerEntity)

    @Query("DELETE FROM ledgers WHERE id = :id")
    suspend fun deleteLedgerById(id: Long)

    @Query("DELETE FROM journal_entries WHERE ledgerId = :id")
    suspend fun deleteJournalEntriesByLedgerId(id: Long)

    @Query("SELECT * FROM ledgers ORDER BY name ASC")
    fun getAllLedgers(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledgers ORDER BY name ASC")
    suspend fun getAllLedgersSync(): List<LedgerEntity>

    @Query("SELECT * FROM ledgers WHERE name = :name LIMIT 1")
    suspend fun getLedgerByName(name: String): LedgerEntity?

    /** Resolves a system ledger by its stable code rather than by a spelling. */
    @Query("SELECT * FROM ledgers WHERE systemCode = :code LIMIT 1")
    suspend fun getLedgerBySystemCode(code: String): LedgerEntity?

    /**
     * Name match ignoring case, spaces and hyphens, so "Cash in Hand", "Cash-in-Hand"
     * and "Cash-in-hand" all collide. Used once, when adopting an existing ledger into a
     * system code, so an upgrading book keeps the account it already has.
     */
    @Query(
        """
        SELECT * FROM ledgers
        WHERE REPLACE(REPLACE(LOWER(name), ' ', ''), '-', '') = REPLACE(REPLACE(LOWER(:name), ' ', ''), '-', '')
        ORDER BY id ASC LIMIT 1
        """
    )
    suspend fun getLedgerByLooseName(name: String): LedgerEntity?

    @Query("SELECT * FROM ledgers WHERE id = :id LIMIT 1")
    suspend fun getLedgerById(id: Long): LedgerEntity?

    // Voucher Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucher(voucher: VoucherEntity): Long

    @Query("SELECT * FROM vouchers ORDER BY date DESC")
    fun getAllVouchers(): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers ORDER BY date DESC")
    suspend fun getAllVouchersSync(): List<VoucherEntity>

    @Query("SELECT * FROM vouchers WHERE id = :id LIMIT 1")
    suspend fun getVoucherById(id: Long): VoucherEntity?

    @Query("DELETE FROM vouchers WHERE id = :id")
    suspend fun deleteVoucherById(id: Long)

    /**
     * Updates the voucher row in place.
     *
     * Deliberately NOT `insertVoucher`, which is `@Insert(onConflict = REPLACE)`: with
     * foreign keys on, REPLACE deletes the conflicting row first, and journal_entries,
     * voucher_items and gst_tax_details all declare ON DELETE CASCADE — so using it to
     * "update" a voucher would silently wipe every posting attached to it.
     */
    @Update
    suspend fun updateVoucherRow(voucher: VoucherEntity)

    @Query("SELECT COUNT(*) FROM vouchers WHERE voucherNo = :voucherNo")
    suspend fun countVouchersWithNumber(voucherNo: String): Int

    /**
     * Atomically reads and advances the voucher counter.
     *
     * The read and the increment used to be two independent suspending DAO calls with
     * the coroutine suspended in between, so two concurrent posts could both read 1005
     * and both write 1006 — issuing the same invoice number twice. That is not
     * theoretical: the XML import's "Commit Import" fires one independent coroutine per
     * row, so N rows race on a single `voucher_type_configs` row. Room opens this with
     * BEGIN IMMEDIATE, which serialises the read-modify-write.
     */
    /**
     * Deletes a voucher and everything attached to it, atomically.
     *
     * Was five sequential unguarded writes: a failure part-way left a voucher with some
     * of its postings gone. [stockReversals] are (itemId, delta) pairs computed by the
     * caller from the voucher's OWN type, so returns reverse correctly too — the old
     * code only handled SALES and PURCHASE, so deleting a credit note never gave the
     * stock back.
     */
    @Transaction
    suspend fun deleteVoucherAtomic(voucherId: Long, stockReversals: List<Pair<Long, Double>>) {
        stockReversals.forEach { (itemId, delta) -> updateStockQty(itemId, delta) }
        deleteJournalEntriesForVoucher(voucherId)
        deleteVoucherItemsForVoucher(voucherId)
        deleteGstDetailForVoucher(voucherId)
        deleteVoucherById(voucherId)
    }

    /** Puts a deleted voucher back with its original id, number and date. */
    @Transaction
    suspend fun restoreVoucherAtomic(
        voucher: VoucherEntity,
        journalEntries: List<JournalEntryEntity>,
        items: List<VoucherItemEntity>,
        gstDetail: GstTaxDetailEntity?,
        stockDeltas: List<Pair<Long, Double>>
    ) {
        insertVoucher(voucher)
        if (journalEntries.isNotEmpty()) insertJournalEntries(journalEntries)
        if (items.isNotEmpty()) insertVoucherItems(items)
        gstDetail?.let { insertGstTaxDetail(it) }
        stockDeltas.forEach { (itemId, delta) -> updateStockQty(itemId, delta) }
    }

    /**
     * Clears a voucher's children and rewrites its header in place, keeping the row.
     *
     * The voucher id, number and creation identity survive — an amendment must not burn
     * a new number, since Rule 46(b) requires a consecutive series and every edit
     * previously left a permanent gap in it.
     *
     * Children are deleted before being re-posted because `insertGstTaxDetail` and
     * `insertVoucherItems` are REPLACE inserts over autoGenerate primary keys: with
     * id = 0 they APPEND rather than replace, and the GST summary would double-count.
     */
    @Transaction
    suspend fun clearVoucherChildrenAtomic(
        voucher: VoucherEntity,
        stockReversals: List<Pair<Long, Double>>
    ) {
        stockReversals.forEach { (itemId, delta) -> updateStockQty(itemId, delta) }
        deleteJournalEntriesForVoucher(voucher.id)
        deleteVoucherItemsForVoucher(voucher.id)
        deleteGstDetailForVoucher(voucher.id)
        updateVoucherRow(voucher)
    }

    /**
     * Resolves a ledger by name, creating it only if absent — atomically.
     *
     * The repository did this as a read followed by an insert with no transaction and no
     * unique index on `ledgers.name`. The XML import launches one coroutine per row and
     * `addVoucher` suspends at every DAO call, so two rows importing into a fresh book
     * could each find "Sales Account" missing and each create it — the same TOCTOU shape
     * that `reserveNextVoucherNumber` was hardened against, left open on the ledger side.
     */
    @Transaction
    suspend fun resolveOrCreateLedger(ledger: LedgerEntity): LedgerEntity {
        getLedgerByName(ledger.name)?.let { return it }
        val id = insertLedger(ledger)
        return getLedgerById(id) ?: ledger.copy(id = id)
    }

    /** Rewrites a manual voucher's amounts against the ledgers it already posts to. */
    @Transaction
    suspend fun amendCustomVoucherAtomic(
        voucher: VoucherEntity,
        journalEntries: List<JournalEntryEntity>
    ) {
        updateVoucherRow(voucher)
        deleteJournalEntriesForVoucher(voucher.id)
        insertJournalEntries(journalEntries.map { it.copy(id = 0) })
    }

    /**
     * Reserves a voucher number and skips any already in use — all inside one transaction.
     *
     * The skip used to run OUTSIDE the transaction, which reintroduced exactly the race
     * it was added for: with the counter rewound by a restore, A reserves 1001, finds it
     * taken and advances to 1002, while B — whose row A has not inserted yet — checks
     * 1002, sees it free, and keeps it. Both post the same number. Done here, the whole
     * read-scan-advance is serialised by BEGIN IMMEDIATE.
     */
    @Transaction
    suspend fun reserveNextVoucherNumber(voucherType: String): String? {
        val config = getVoucherConfigByType(voucherType) ?: return null
        var seq = config.nextNumber
        var candidate = config.prefix + String.format(java.util.Locale.ENGLISH, "%04d", seq)
        var guard = 0
        while (countVouchersWithNumber(candidate) > 0 && guard < 10_000) {
            seq += 1
            candidate = config.prefix + String.format(java.util.Locale.ENGLISH, "%04d", seq)
            guard++
        }
        if (config.autoIncrement) {
            insertOrUpdateVoucherConfig(config.copy(nextNumber = seq + 1))
        }
        return candidate
    }

    // Journal Entry Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournalEntries(entries: List<JournalEntryEntity>)

    @Query("SELECT * FROM journal_entries WHERE voucherId = :voucherId")
    suspend fun getJournalEntriesForVoucher(voucherId: Long): List<JournalEntryEntity>

    // Inventory Item Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryItem(item: InventoryItemEntity): Long

    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    fun getAllInventoryItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items WHERE id = :id LIMIT 1")
    suspend fun getInventoryItemById(id: Long): InventoryItemEntity?

    @Query("UPDATE inventory_items SET stockQty = stockQty + :qtyDelta WHERE id = :id")
    suspend fun updateStockQty(id: Long, qtyDelta: Double)

    @Query("UPDATE inventory_items SET avgCostPrice = :newAvgCost WHERE id = :id")
    suspend fun updateAvgCostPrice(id: Long, newAvgCost: Double)

    /**
     * Receives stock at a purchase price and rolls the weighted-average cost forward.
     *
     * `avgCostPrice` was written once at item creation and never recomputed (H21), so
     * every closing-stock valuation used the price typed on the day the item was set up,
     * however long ago and whatever had been paid since. That figure feeds
     * `SUM(stockQty * avgCostPrice)` into the Balance Sheet and the Trading Account.
     *
     * Standard weighted average:
     *   newAvg = (oldQty x oldAvg + inQty x inRate) / (oldQty + inQty)
     * Guarded so a nil or negative resulting quantity leaves the cost alone rather than
     * dividing by zero — stock is allowed to go negative here (C9).
     */
    /**
     * Reverses a stock receipt, unwinding the weighted average as well as the quantity.
     *
     * `receiveStockAtCost` rolls the average forward on purchase, but the reversal paths
     * only moved quantity — so amending or deleting a purchase left the inflated average
     * behind. 100 units at Rs 50 plus 10 at Rs 100 gives 110 at Rs 54.545; reversing only
     * the quantity leaves 100 units still valued at Rs 54.545, or Rs 5,454.55 against a
     * true Rs 5,000, and that phantom Rs 454.55 flows into closing stock and gross profit.
     */
    @Transaction
    suspend fun reverseStockAtCost(itemId: Long, quantity: Double, unitCost: Double) {
        val item = getInventoryItemById(itemId) ?: return
        val newQty = item.stockQty - quantity
        if (quantity > 0.0 && newQty > 0.0) {
            val remainingValue = item.stockQty * item.avgCostPrice - quantity * unitCost
            updateAvgCostPrice(itemId, (remainingValue / newQty).coerceAtLeast(0.0))
        }
        updateStockQty(itemId, -quantity)
    }

    @Transaction
    suspend fun receiveStockAtCost(itemId: Long, quantity: Double, unitCost: Double) {
        val item = getInventoryItemById(itemId) ?: return
        val newQty = item.stockQty + quantity
        if (quantity > 0.0 && unitCost > 0.0 && newQty > 0.0) {
            val oldValue = if (item.stockQty > 0.0) item.stockQty * item.avgCostPrice else 0.0
            updateAvgCostPrice(itemId, (oldValue + quantity * unitCost) / newQty)
        }
        updateStockQty(itemId, quantity)
    }

    /**
     * Items carrying a negative quantity (C9).
     *
     * Nothing prevented stock from going below zero and nothing reported it, so a book
     * could drift silently. Negative stock stays permitted — refusing a sale because the
     * purchase has not been keyed in yet is worse — but it is now visible.
     */
    @Query("SELECT * FROM inventory_items WHERE stockQty < 0 ORDER BY stockQty ASC")
    fun getNegativeStockItemsFlow(): Flow<List<InventoryItemEntity>>

    // Voucher Items
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherItems(items: List<VoucherItemEntity>)

    @Query("SELECT * FROM voucher_items WHERE voucherId = :voucherId")
    suspend fun getVoucherItemsForVoucher(voucherId: Long): List<VoucherItemEntity>

    // GST Tax Details
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGstTaxDetail(gstDetail: GstTaxDetailEntity)

    @Query("SELECT * FROM gst_tax_details WHERE voucherId = :voucherId LIMIT 1")
    suspend fun getGstTaxDetailForVoucher(voucherId: Long): GstTaxDetailEntity?

    // Sync Logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncLog(syncLog: SyncLogEntity)

    @Query("SELECT * FROM sync_logs WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLogs(): List<SyncLogEntity>

    @Query("UPDATE sync_logs SET isSynced = 1 WHERE id = :id")
    suspend fun markLogSynced(id: Long)

    // Report Calculations: Trial Balance
    @Query("""
        SELECT 
            l.id as id,
            l.name as name,
            lg.name as groupName,
            lg.category as category,
            COALESCE(SUM(je.debitAmount), 0) + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END as totalDebit,
            COALESCE(SUM(je.creditAmount), 0) + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END as totalCredit,
            (COALESCE(SUM(je.debitAmount), 0) + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END) - 
            (COALESCE(SUM(je.creditAmount), 0) + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END) as currentBalance
        FROM ledgers l
        JOIN ledger_groups lg ON l.groupId = lg.id
        LEFT JOIN journal_entries je ON l.id = je.ledgerId
        GROUP BY l.id
        ORDER BY lg.category, l.name
    """)
    fun getTrialBalanceFlow(): Flow<List<LedgerWithBalance>>

    // GST Statutory Compliance Calculations
    @Query("""
        SELECT 
            COALESCE(SUM(CASE v.voucherType WHEN 'SALES' THEN g.cgstAmount WHEN 'SALES_RETURN' THEN -g.cgstAmount ELSE 0 END), 0) as totalOutputCgst,
            COALESCE(SUM(CASE v.voucherType WHEN 'SALES' THEN g.sgstAmount WHEN 'SALES_RETURN' THEN -g.sgstAmount ELSE 0 END), 0) as totalOutputSgst,
            COALESCE(SUM(CASE v.voucherType WHEN 'SALES' THEN g.igstAmount WHEN 'SALES_RETURN' THEN -g.igstAmount ELSE 0 END), 0) as totalOutputIgst,
            COALESCE(SUM(CASE v.voucherType WHEN 'PURCHASE' THEN g.cgstAmount WHEN 'PURCHASE_RETURN' THEN -g.cgstAmount ELSE 0 END), 0) as totalInputCgst,
            COALESCE(SUM(CASE v.voucherType WHEN 'PURCHASE' THEN g.sgstAmount WHEN 'PURCHASE_RETURN' THEN -g.sgstAmount ELSE 0 END), 0) as totalInputSgst,
            COALESCE(SUM(CASE v.voucherType WHEN 'PURCHASE' THEN g.igstAmount WHEN 'PURCHASE_RETURN' THEN -g.igstAmount ELSE 0 END), 0) as totalInputIgst
        FROM gst_tax_details g
        JOIN vouchers v ON g.voucherId = v.id
    """)
    fun getGstSummaryFlow(): Flow<GstSummaryReport>

    @Query("DELETE FROM journal_entries WHERE voucherId = :voucherId")
    suspend fun deleteJournalEntriesForVoucher(voucherId: Long)

    @Query("DELETE FROM voucher_items WHERE voucherId = :voucherId")
    suspend fun deleteVoucherItemsForVoucher(voucherId: Long)

    @Query("DELETE FROM gst_tax_details WHERE voucherId = :voucherId")
    suspend fun deleteGstDetailForVoucher(voucherId: Long)

    // Quick Stats
    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM vouchers WHERE voucherType = 'SALES'")
    fun getTotalSalesFlow(): Flow<Double>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM vouchers WHERE voucherType = 'PURCHASE'")
    fun getTotalPurchasesFlow(): Flow<Double>

    @Query("SELECT COALESCE(SUM(debitAmount - creditAmount), 0) FROM journal_entries je JOIN ledgers l ON je.ledgerId = l.id WHERE l.name LIKE '%Cash%' OR l.name LIKE '%Bank%'")
    fun getNetCashFlow(): Flow<Double>

    @Query("SELECT COALESCE(SUM(debitAmount - creditAmount), 0) FROM journal_entries je JOIN ledgers l ON je.ledgerId = l.id WHERE l.name LIKE '%Cash%' OR l.name LIKE '%Bank%'")
    suspend fun getCashAndBankBalanceSync(): Double

    // NOTE: opening balances and journal movement are summed in SEPARATE scalar
    // subqueries on purpose. The previous single-statement LEFT JOIN form repeated
    // l.openingBalance once per joined journal row, so a ledger with N postings had
    // its opening balance counted N times. Keep these independent.
    @Query("""
        SELECT
            COALESCE((
                SELECT SUM(l.openingBalance)
                FROM ledgers l JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Cash%' OR lg.name LIKE '%Cash%'
            ), 0)
            +
            COALESCE((
                SELECT SUM(je.debitAmount - je.creditAmount)
                FROM journal_entries je
                JOIN ledgers l ON je.ledgerId = l.id
                JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Cash%' OR lg.name LIKE '%Cash%'
            ), 0)
    """)
    fun getCashBalanceFlow(): Flow<Double>

    @Query("""
        SELECT
            COALESCE((
                SELECT SUM(l.openingBalance)
                FROM ledgers l JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Bank%' OR lg.name LIKE '%Bank%'
            ), 0)
            +
            COALESCE((
                SELECT SUM(je.debitAmount - je.creditAmount)
                FROM journal_entries je
                JOIN ledgers l ON je.ledgerId = l.id
                JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Bank%' OR lg.name LIKE '%Bank%'
            ), 0)
    """)
    fun getBankBalanceFlow(): Flow<Double>

    // ---- Analytics: real, date-bucketed report data (no fabrication) ----

    /**
     * Monthly revenue/expense buckets on an accrual basis, keyed off the voucher date.
     * 'localtime' is required: voucher.date is UTC epoch millis, but an entry made at
     * 11:30pm IST on 31 March belongs to that FY, not the next one.
     */
    @Query("""
        SELECT
            strftime('%Y-%m', v.date / 1000, 'unixepoch', 'localtime') as monthKey,
            lg.category as category,
            COALESCE(SUM(je.debitAmount), 0) as totalDebit,
            COALESCE(SUM(je.creditAmount), 0) as totalCredit
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE lg.category IN ('REVENUE', 'EXPENSE')
          AND v.date BETWEEN :fromMillis AND :toMillis
        GROUP BY monthKey, lg.category
        ORDER BY monthKey ASC
    """)
    fun getMonthlyPnlFlow(fromMillis: Long, toMillis: Long): Flow<List<MonthlyPnlRow>>

    /** Cash balance as it stood immediately BEFORE the trend window opens. */
    @Query("""
        SELECT
            COALESCE((
                SELECT SUM(l.openingBalance)
                FROM ledgers l JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Cash%' OR lg.name LIKE '%Cash%'
            ), 0)
            +
            COALESCE((
                SELECT SUM(je.debitAmount - je.creditAmount)
                FROM journal_entries je
                JOIN vouchers v ON je.voucherId = v.id
                JOIN ledgers l ON je.ledgerId = l.id
                JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE (l.name LIKE '%Cash%' OR lg.name LIKE '%Cash%')
                  AND v.date < :windowStartMillis
            ), 0)
    """)
    fun getCashBaselineBalanceFlow(windowStartMillis: Long): Flow<Double>

    /** Bank balance as it stood immediately BEFORE the trend window opens. */
    @Query("""
        SELECT
            COALESCE((
                SELECT SUM(l.openingBalance)
                FROM ledgers l JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE l.name LIKE '%Bank%' OR lg.name LIKE '%Bank%'
            ), 0)
            +
            COALESCE((
                SELECT SUM(je.debitAmount - je.creditAmount)
                FROM journal_entries je
                JOIN vouchers v ON je.voucherId = v.id
                JOIN ledgers l ON je.ledgerId = l.id
                JOIN ledger_groups lg ON l.groupId = lg.id
                WHERE (l.name LIKE '%Bank%' OR lg.name LIKE '%Bank%')
                  AND v.date < :windowStartMillis
            ), 0)
    """)
    fun getBankBaselineBalanceFlow(windowStartMillis: Long): Flow<Double>

    @Query("""
        SELECT
            strftime('%Y-%m-%d', v.date / 1000, 'unixepoch', 'localtime') AS dayBucket,
            SUM(je.debitAmount - je.creditAmount) AS netMovement
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE (l.name LIKE '%Cash%' OR lg.name LIKE '%Cash%')
          AND v.date >= :windowStartMillis AND v.date <= :windowEndMillis
        GROUP BY dayBucket
        ORDER BY dayBucket ASC
    """)
    fun getCashDailyMovementFlow(windowStartMillis: Long, windowEndMillis: Long): Flow<List<DailyNetMovement>>

    @Query("""
        SELECT
            strftime('%Y-%m-%d', v.date / 1000, 'unixepoch', 'localtime') AS dayBucket,
            SUM(je.debitAmount - je.creditAmount) AS netMovement
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE (l.name LIKE '%Bank%' OR lg.name LIKE '%Bank%')
          AND v.date >= :windowStartMillis AND v.date <= :windowEndMillis
        GROUP BY dayBucket
        ORDER BY dayBucket ASC
    """)
    fun getBankDailyMovementFlow(windowStartMillis: Long, windowEndMillis: Long): Flow<List<DailyNetMovement>>

    @Query("""
        SELECT 
            l.id as id,
            l.name as name,
            lg.name as groupName,
            lg.category as category,
            COALESCE(SUM(je.debitAmount), 0) + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END as totalDebit,
            COALESCE(SUM(je.creditAmount), 0) + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END as totalCredit,
            (COALESCE(SUM(je.debitAmount), 0) + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END) - 
            (COALESCE(SUM(je.creditAmount), 0) + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END) as currentBalance
        FROM ledgers l
        JOIN ledger_groups lg ON l.groupId = lg.id
        LEFT JOIN journal_entries je ON l.id = je.ledgerId
        WHERE l.name LIKE '%Cash%' OR l.name LIKE '%Bank%' OR lg.name LIKE '%Cash%' OR lg.name LIKE '%Bank%'
        GROUP BY l.id
        ORDER BY l.name ASC
    """)
    fun getCashAndBankLedgersFlow(): Flow<List<LedgerWithBalance>>

    @Query("SELECT * FROM vouchers WHERE voucherType = 'SALES' ORDER BY date DESC")
    fun getSalesVouchersFlow(): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers WHERE voucherType = 'PURCHASE' ORDER BY date DESC")
    fun getPurchaseVouchersFlow(): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers ORDER BY date DESC")
    suspend fun getAllVouchersList(): List<VoucherEntity>

    @Query("SELECT * FROM ledgers ORDER BY name ASC")
    suspend fun getAllLedgersList(): List<LedgerEntity>

    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    suspend fun getAllInventoryItemsList(): List<InventoryItemEntity>

    @Query("SELECT * FROM journal_entries ORDER BY id ASC")
    suspend fun getAllJournalEntriesList(): List<JournalEntryEntity>

    @Query("SELECT * FROM ledger_groups ORDER BY name ASC")
    suspend fun getAllLedgerGroupsList(): List<LedgerGroupEntity>

    // ---- Full-fidelity backup / restore ----
    // The books live across ten tables that reference each other by primary key. A
    // backup that captures only four of them, and a restore that re-posts vouchers
    // through createVoucher, cannot reproduce the original set of books: voucher
    // numbers are reissued, dates restamped to now, GST rates back-derived from
    // rounded amounts, and every ledger recreated as a Sundry Debtor. These read the
    // rows as they actually are, and the inserts below put them back with their IDs
    // intact so every foreign key still resolves.

    // ---- Financial statements (Balance Sheet / Profit & Loss) ----
    //
    // A Balance Sheet is an as-on snapshot and a P&L is a between-dates movement.
    // getTrialBalanceFlow() takes no date bound at all, so building the statements from
    // it would print lifetime figures under a period heading. These are the date-aware
    // equivalents.
    //
    // No window functions anywhere below: SUM() OVER needs SQLite 3.25+, and minSdk 24
    // means Android 7 ships 3.9.2. Same constraint the cash/bank trend was written under.

    /**
     * Per-ledger debit/credit totals as on a date, including opening balances.
     *
     * The date filter sits INSIDE `SUM(CASE ...)`, not in the LEFT JOIN's ON clause. In
     * the ON-clause form a journal row whose voucher is filtered out still survives the
     * join with a NULL voucher and is summed anyway. The opening balance is added
     * OUTSIDE the SUM so that `GROUP BY l.id` applies it exactly once — adding it inside
     * would repeat it per posting, which is the bug that once made a cash ledger with
     * two postings report 1,01,500 instead of 51,500.
     */
    @Query(
        """
        SELECT l.id AS id, l.name AS name,
            COALESCE(lg.name, l.groupName) AS groupName,
            -- The LEDGER's own category takes precedence over its group's. They are kept
            -- in step by createLedger/updateLedgerDetails and repaired by MIGRATION_8_9,
            -- and they legitimately differ for input GST: an ASSET inside the liability
            -- group "Duties & Taxes". Reading the group's category put unutilised ITC on
            -- the liabilities face and understated both sides.
            COALESCE(l.category, lg.category) AS category,
            COALESCE(SUM(CASE WHEN v.date <= :asOnMillis THEN je.debitAmount ELSE 0 END), 0)
              + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END AS totalDebit,
            COALESCE(SUM(CASE WHEN v.date <= :asOnMillis THEN je.creditAmount ELSE 0 END), 0)
              + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END AS totalCredit,
            (COALESCE(SUM(CASE WHEN v.date <= :asOnMillis THEN je.debitAmount ELSE 0 END), 0)
              + CASE WHEN l.balanceType = 'DR' THEN l.openingBalance ELSE 0 END)
            - (COALESCE(SUM(CASE WHEN v.date <= :asOnMillis THEN je.creditAmount ELSE 0 END), 0)
              + CASE WHEN l.balanceType = 'CR' THEN l.openingBalance ELSE 0 END) AS currentBalance
        FROM ledgers l
        LEFT JOIN ledger_groups lg ON l.groupId = lg.id
        LEFT JOIN journal_entries je ON l.id = je.ledgerId
        LEFT JOIN vouchers v ON je.voucherId = v.id
        GROUP BY l.id
        ORDER BY COALESCE(lg.category, l.category), l.name
        """
    )
    fun getBalancesAsOnFlow(asOnMillis: Long): Flow<List<LedgerWithBalance>>

    /**
     * Real cash and bank movement in a period, split into money in and money out.
     *
     * The Cash Flow tab classified by voucher-type substring, so "SALES_RETURN" matched
     * "SALE" and a refund counted as an INFLOW, while a credit sale and the receipt that
     * settled it were both counted — double-counting the same rupee. Cash flow is not a
     * property of a voucher's type; it is what actually moved through cash and bank.
     */
    @Query(
        """
        SELECT COALESCE(SUM(je.debitAmount), 0)
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        WHERE l.systemCode IN ('CASH', 'BANK') AND v.date BETWEEN :fromMillis AND :toMillis
          -- A Contra debits one of these and credits the other, so counting it would
          -- inflate both inflow and outflow by the same amount. It moves money between
          -- two accounts the business already holds; no cash enters or leaves.
          AND v.voucherType != 'CONTRA'
        """
    )
    fun getCashInflowFlow(fromMillis: Long, toMillis: Long): Flow<Double>

    @Query(
        """
        SELECT COALESCE(SUM(je.creditAmount), 0)
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        WHERE l.systemCode IN ('CASH', 'BANK') AND v.date BETWEEN :fromMillis AND :toMillis
          AND v.voucherType != 'CONTRA'
        """
    )
    fun getCashOutflowFlow(fromMillis: Long, toMillis: Long): Flow<Double>

    /**
     * Ledgers whose group row is missing. Should always be zero.
     *
     * Room disables foreign keys during migrations, so a ledger can end up with a
     * dangling groupId. Such a ledger would silently disappear from the Balance Sheet,
     * and neither the opening-balance nor the journal-imbalance check would notice —
     * the sheet would simply be wrong by that ledger's balance.
     */
    @Query(
        """
        SELECT COUNT(*) FROM ledgers l
        LEFT JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE lg.id IS NULL
        """
    )
    fun getOrphanedLedgerCountFlow(): Flow<Int>

    /**
     * Revenue/expense movement within a period.
     *
     * Inner joins on purpose: a nominal ledger with no movement in the period is absent
     * rather than a zero row, so the statement renders no empty scaffolding. Opening
     * balances are excluded because an opening balance carries no date and cannot be
     * attributed to a period — [getNominalOpeningBalance] surfaces them separately.
     */
    @Query(
        """
        SELECT l.id AS id, l.name AS name, lg.name AS groupName, lg.category AS category,
            COALESCE(SUM(je.debitAmount), 0) AS totalDebit,
            COALESCE(SUM(je.creditAmount), 0) AS totalCredit,
            COALESCE(SUM(je.debitAmount), 0) - COALESCE(SUM(je.creditAmount), 0) AS currentBalance
        FROM ledgers l
        JOIN ledger_groups lg ON l.groupId = lg.id
        JOIN journal_entries je ON l.id = je.ledgerId
        JOIN vouchers v ON je.voucherId = v.id
        WHERE lg.category IN ('REVENUE', 'EXPENSE')
          AND v.date BETWEEN :fromMillis AND :toMillis
        GROUP BY l.id
        ORDER BY lg.category, l.name
        """
    )
    fun getNominalMovementFlow(fromMillis: Long, toMillis: Long): Flow<List<LedgerWithBalance>>

    /** Accumulated profit before the period — the Balance Sheet's P&L A/c opening line. */
    @Query(
        """
        SELECT COALESCE(SUM(je.creditAmount - je.debitAmount), 0)
        FROM journal_entries je
        JOIN vouchers v ON je.voucherId = v.id
        JOIN ledgers l ON je.ledgerId = l.id
        JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE lg.category IN ('REVENUE', 'EXPENSE') AND v.date < :fromMillis
        """
    )
    fun getPriorNominalProfitFlow(fromMillis: Long): Flow<Double>

    /** Opening balances parked on revenue/expense ledgers — retained earnings from before the app. */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN l.balanceType = 'CR' THEN l.openingBalance
                                 ELSE -l.openingBalance END), 0)
        FROM ledgers l JOIN ledger_groups lg ON l.groupId = lg.id
        WHERE lg.category IN ('REVENUE', 'EXPENSE')
        """
    )
    fun getNominalOpeningBalanceFlow(): Flow<Double>

    /**
     * Net of all opening balances. Non-zero means the user's opening balances disagree.
     *
     * Opening balances are written straight onto the ledger row with no contra posting,
     * so nothing forces them to net to zero. Positive = opening debits exceed credits.
     * Tally shows this as a "Difference in Opening Balances" line rather than hiding it.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN balanceType = 'DR' THEN openingBalance
                                 ELSE -openingBalance END), 0)
        FROM ledgers
        """
    )
    fun getOpeningBalanceDifferenceFlow(): Flow<Double>

    /** Must be zero. Anything else means the journal itself is corrupt. */
    @Query(
        """
        SELECT COALESCE(SUM(je.debitAmount - je.creditAmount), 0)
        FROM journal_entries je JOIN vouchers v ON je.voucherId = v.id
        WHERE v.date <= :asOnMillis
        """
    )
    fun getJournalImbalanceFlow(asOnMillis: Long): Flow<Double>

    /** Stock at recorded cost. Note avgCostPrice is set at item creation and not recomputed (H21). */
    @Query("SELECT COALESCE(SUM(stockQty * avgCostPrice), 0) FROM inventory_items")
    fun getStockValueAtCostFlow(): Flow<Double>

    /**
     * Value of item movement after a date, used to roll the current stock value back to
     * an as-on date. Only sees vouchers that carry line items.
     *
     * Uses the cost FROZEN on each line, not the item's live average. Valuing history at
     * the current average meant one purchase at a new price retroactively revalued every
     * past movement, so the derived opening stock drifted from what was actually posted
     * and the Balance Sheet went out by the drift.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE v.voucherType
            WHEN 'PURCHASE'        THEN  vi.quantity * vi.costRate
            WHEN 'SALES_RETURN'    THEN  vi.quantity * vi.costRate
            WHEN 'STOCK_OPENING'   THEN  vi.quantity * vi.costRate
            WHEN 'SALES'           THEN -vi.quantity * vi.costRate
            WHEN 'PURCHASE_RETURN' THEN -vi.quantity * vi.costRate
            ELSE 0 END), 0)
        FROM voucher_items vi
        JOIN vouchers v ON vi.voucherId = v.id
        WHERE v.date > :asOnMillis
        """
    )
    fun getStockMovementValueAfterFlow(asOnMillis: Long): Flow<Double>

    @Query("SELECT * FROM voucher_items ORDER BY id ASC")
    suspend fun getAllVoucherItemsList(): List<VoucherItemEntity>

    @Query("SELECT * FROM gst_tax_details ORDER BY id ASC")
    suspend fun getAllGstTaxDetailsList(): List<GstTaxDetailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerGroups(groups: List<LedgerGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgers(ledgers: List<LedgerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVouchers(vouchers: List<VoucherEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryItems(items: List<InventoryItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGstTaxDetails(details: List<GstTaxDetailEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherConfigs(configs: List<VoucherTypeConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReconciliationDiscrepancies(items: List<ReconciliationDiscrepancyEntity>)

    // Wipe order matters: children before parents, or RESTRICT/CASCADE constraints fire.
    @Query("DELETE FROM gst_tax_details")
    suspend fun wipeGstTaxDetails()

    @Query("DELETE FROM voucher_items")
    suspend fun wipeVoucherItems()

    @Query("DELETE FROM journal_entries")
    suspend fun wipeJournalEntries()

    @Query("DELETE FROM vouchers")
    suspend fun wipeVouchers()

    @Query("DELETE FROM ledgers")
    suspend fun wipeLedgers()

    @Query("DELETE FROM ledger_groups")
    suspend fun wipeLedgerGroups()

    @Query("DELETE FROM inventory_items")
    suspend fun wipeInventoryItems()

    @Query("DELETE FROM voucher_type_configs")
    suspend fun wipeVoucherConfigs()

    /**
     * Replaces the entire set of books atomically.
     *
     * Restore has to be all-or-nothing. Without a transaction, a failure partway through
     * — a malformed row, a killed process, a full disk — leaves the user with the old
     * books already deleted and the new ones half-written: no ledgers but all the
     * vouchers, or journal entries pointing at ledger IDs that no longer exist. Room
     * rolls the whole thing back if anything in here throws.
     *
     * Order is dictated by the foreign keys: children are deleted before their parents,
     * then parents are inserted before their children.
     */
    @Transaction
    suspend fun replaceAllBooks(
        user: UserEntity?,
        ledgerGroups: List<LedgerGroupEntity>,
        ledgers: List<LedgerEntity>,
        inventoryItems: List<InventoryItemEntity>,
        vouchers: List<VoucherEntity>,
        journalEntries: List<JournalEntryEntity>,
        voucherItems: List<VoucherItemEntity>,
        gstTaxDetails: List<GstTaxDetailEntity>,
        voucherConfigs: List<VoucherTypeConfigEntity>,
        reconciliations: List<ReconciliationDiscrepancyEntity>
    ) {
        wipeGstTaxDetails()
        wipeVoucherItems()
        wipeJournalEntries()
        wipeVouchers()
        wipeLedgers()
        wipeLedgerGroups()
        wipeInventoryItems()
        wipeVoucherConfigs()
        clearAllReconciliationDiscrepancies()

        insertLedgerGroups(ledgerGroups)
        insertLedgers(ledgers)
        insertInventoryItems(inventoryItems)
        insertVouchers(vouchers)
        insertJournalEntries(journalEntries)
        insertVoucherItems(voucherItems)
        insertGstTaxDetails(gstTaxDetails)
        insertVoucherConfigs(voucherConfigs)
        insertReconciliationDiscrepancies(reconciliations)
        user?.let { saveUser(it) }
    }

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getUserDirect(): UserEntity?

    @Query("""
        SELECT 
            je.entryId as entryId,
            v.id as voucherId,
            v.voucherNo as voucherNo,
            v.voucherType as voucherType,
            v.date as date,
            v.partyName as partyName,
            v.narration as narration,
            je.debitAmount as debitAmount,
            je.creditAmount as creditAmount
        FROM (
            SELECT id as entryId, voucherId, debitAmount, creditAmount
            FROM journal_entries
            WHERE ledgerId = :ledgerId
        ) je
        JOIN vouchers v ON je.voucherId = v.id
        ORDER BY v.date ASC, je.entryId ASC
    """)
    suspend fun getLedgerTransactions(ledgerId: Long): List<LedgerTxEntry>

    // Voucher Type Configuration Operations
    @Query("SELECT * FROM voucher_type_configs ORDER BY voucherType ASC")
    fun getAllVoucherConfigsFlow(): Flow<List<VoucherTypeConfigEntity>>

    @Query("SELECT * FROM voucher_type_configs ORDER BY voucherType ASC")
    suspend fun getAllVoucherConfigsSync(): List<VoucherTypeConfigEntity>

    @Query("SELECT * FROM voucher_type_configs WHERE voucherType = :type LIMIT 1")
    suspend fun getVoucherConfigByType(type: String): VoucherTypeConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateVoucherConfig(config: VoucherTypeConfigEntity)

    // Ledger Reconciliation Operations
    @Query("SELECT * FROM reconciliation_discrepancies ORDER BY isResolved ASC, detectedAt DESC")
    fun getAllReconciliationDiscrepanciesFlow(): Flow<List<ReconciliationDiscrepancyEntity>>

    @Query("SELECT * FROM reconciliation_discrepancies ORDER BY detectedAt DESC")
    suspend fun getAllReconciliationDiscrepanciesSync(): List<ReconciliationDiscrepancyEntity>

    @Query("SELECT COUNT(*) FROM reconciliation_discrepancies WHERE isResolved = 0")
    fun getUnresolvedDiscrepancyCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reconciliation_discrepancies WHERE isResolved = 0")
    suspend fun getUnresolvedDiscrepancyCountSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReconciliationDiscrepancy(discrepancy: ReconciliationDiscrepancyEntity): Long

    @Query("UPDATE reconciliation_discrepancies SET isResolved = 1, status = 'RESOLVED', resolutionNotes = :notes WHERE id = :id")
    suspend fun markDiscrepancyResolved(id: Long, notes: String)

    @Query("DELETE FROM reconciliation_discrepancies WHERE isResolved = 0")
    suspend fun clearUnresolvedReconciliationDiscrepancies()

    @Query("DELETE FROM reconciliation_discrepancies")
    suspend fun clearAllReconciliationDiscrepancies()
}
