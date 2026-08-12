package com.example.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.data.dao.AccountingDao
import com.example.data.dao.GstSummaryReport
import com.example.data.dao.LedgerTxEntry
import com.example.data.dao.LedgerWithBalance
import com.example.data.dao.MonthlyPnlRow
import com.example.data.model.*
import com.example.data.report.BalanceSheet
import com.example.data.report.FinancialStatementEngine
import com.example.data.report.ProfitAndLoss
import com.example.utils.BookBackupSerializer
import com.example.utils.FiscalYearUtils
import com.example.utils.GstCalculationService
import com.example.utils.GstTaxBreakdown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One day of the Cash & Bank trend, carrying genuine running balances. */
data class BalanceTrendPoint(
    val dayBucket: String,      // "yyyy-MM-dd"
    val displayLabel: String,   // "12 Aug"
    val cashBalance: Double,    // running Cash-in-Hand balance at end of this day
    val bankBalance: Double     // running Bank balance at end of this day
)

/** Stable identities for the ledgers the posting engine must always resolve. */
const val SYSTEM_LEDGER_CASH = "CASH"
const val SYSTEM_LEDGER_BANK = "BANK"

/**
 * @param database optional, and only used to wrap multi-step writes in a single
 *   transaction. Passing it is strongly preferred: without it an amendment commits its
 *   clear-and-reverse step and then re-posts as loose writes, so a failure in between
 *   leaves a voucher carrying its new amount with zero journal entries — invisible to
 *   the Trial Balance while still counted by SUM(totalAmount), which makes the dashboard
 *   and the P&L disagree permanently with nothing reported. Left nullable so the
 *   existing `AccountingRepository(dao)` call sites keep compiling.
 */
class AccountingRepository(
    private val dao: AccountingDao,
    private val database: RoomDatabase? = null
) {

    /** Runs [block] in one transaction when a database handle was supplied. */
    private suspend fun <T> inTransaction(block: suspend () -> T): T {
        val db = database ?: return block()
        return db.withTransaction { block() }
    }


    val userFlow: Flow<UserEntity?> = dao.getUserFlow()
    val allVouchers: Flow<List<VoucherEntity>> = dao.getAllVouchers()
    val allLedgers: Flow<List<LedgerEntity>> = dao.getAllLedgers()
    val allInventoryItems: Flow<List<InventoryItemEntity>> = dao.getAllInventoryItems()
    val trialBalanceFlow: Flow<List<LedgerWithBalance>> = dao.getTrialBalanceFlow()
    val gstSummaryFlow: Flow<GstSummaryReport> = dao.getGstSummaryFlow()
    val totalSalesFlow: Flow<Double> = dao.getTotalSalesFlow()
    val totalPurchasesFlow: Flow<Double> = dao.getTotalPurchasesFlow()
    val netCashFlow: Flow<Double> = dao.getNetCashFlow()
    val cashBalanceFlow: Flow<Double> = dao.getCashBalanceFlow()
    val bankBalanceFlow: Flow<Double> = dao.getBankBalanceFlow()
    val cashAndBankLedgersFlow: Flow<List<LedgerWithBalance>> = dao.getCashAndBankLedgersFlow()
    val salesVouchersFlow: Flow<List<VoucherEntity>> = dao.getSalesVouchersFlow()
    val purchaseVouchersFlow: Flow<List<VoucherEntity>> = dao.getPurchaseVouchersFlow()

    // ---- Analytics: real report data, parameterised by date range ----

    /** Accrual monthly revenue/expense buckets for the given inclusive range. */
    fun monthlyPnlFlow(fromMillis: Long, toMillis: Long): Flow<List<MonthlyPnlRow>> =
        dao.getMonthlyPnlFlow(fromMillis, toMillis)

    /**
     * Genuine Cash & Bank balance history over a rolling window.
     *
     * Built the way accounting systems actually do it: opening balance plus the
     * cumulative sum of dated postings. The running total is accumulated in Kotlin
     * rather than via a SQL window function on purpose — `SUM(...) OVER (...)` needs
     * SQLite 3.25+, but minSdk here is 24 and Android ships 3.9.2 on API 24-25,
     * 3.18.2 on 26-27 and 3.22 on 28, so a window function would crash on Android 7-9.
     *
     * Days with no postings contribute 0.0, so the balance carries forward naturally —
     * no separate last-observation-carried-forward pass needed.
     */
    fun cashBankTrendFlow(windowDays: Int = 30): Flow<List<BalanceTrendPoint>> {
        val (windowStart, windowEnd) = FiscalYearUtils.rollingWindowBounds(windowDays)

        return combine(
            dao.getCashBaselineBalanceFlow(windowStart),
            dao.getBankBaselineBalanceFlow(windowStart),
            dao.getCashDailyMovementFlow(windowStart, windowEnd),
            dao.getBankDailyMovementFlow(windowStart, windowEnd)
        ) { cashBase, bankBase, cashDaily, bankDaily ->
            val cashByDay = cashDaily.associate { it.dayBucket to it.netMovement }
            val bankByDay = bankDaily.associate { it.dayBucket to it.netMovement }

            val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
            val labelFmt = SimpleDateFormat("d MMM", Locale.ENGLISH)
            val cal = Calendar.getInstance().apply { timeInMillis = windowStart }

            var runningCash = cashBase
            var runningBank = bankBase
            val points = ArrayList<BalanceTrendPoint>(windowDays)

            repeat(windowDays) {
                val key = dayKeyFmt.format(cal.time)
                runningCash += cashByDay[key] ?: 0.0
                runningBank += bankByDay[key] ?: 0.0
                points.add(
                    BalanceTrendPoint(
                        dayBucket = key,
                        displayLabel = labelFmt.format(cal.time),
                        cashBalance = runningCash,
                        bankBalance = runningBank
                    )
                )
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            points
        }
    }

    // ---- Financial statements ----

    /**
     * Stock value at a point in time: current valuation rolled back by the movement
     * since. Only sees vouchers carrying line items, and values at the cost recorded
     * when the item was created (avgCostPrice is never recomputed on purchase — H21),
     * so the figure is captioned in the UI rather than presented as a valuation.
     */
    private fun stockValueAsOnFlow(asOnMillis: Long): Flow<Double> =
        combine(
            dao.getStockValueAtCostFlow(),
            dao.getStockMovementValueAfterFlow(asOnMillis)
        ) { total, movedAfter -> total - movedAfter }

    fun profitAndLossFlow(
        fromMillis: Long,
        toMillis: Long,
        inventoryEnabled: Boolean
    ): Flow<ProfitAndLoss> =
        combine(
            dao.getNominalMovementFlow(fromMillis, toMillis),
            stockValueAsOnFlow(fromMillis - 1),
            stockValueAsOnFlow(toMillis)
        ) { movement, openingStock, closingStock ->
            FinancialStatementEngine.profitAndLoss(
                movement = movement,
                openingStockValue = if (inventoryEnabled) openingStock else 0.0,
                closingStockValue = if (inventoryEnabled) closingStock else 0.0,
                inventoryEnabled = inventoryEnabled
            )
        }

    /**
     * The Balance Sheet is an as-on snapshot; the profit it carries is the movement over
     * the same period the P&L tab shows. Both go through
     * [FinancialStatementEngine.profitAndLoss], so the two statements cannot disagree
     * about net profit — there is one computation, rendered twice.
     */
    fun balanceSheetFlow(
        asOnMillis: Long,
        fromMillis: Long,
        toMillis: Long,
        inventoryEnabled: Boolean
    ): Flow<BalanceSheet> =
        combine(
            dao.getBalancesAsOnFlow(asOnMillis),
            profitAndLossFlow(fromMillis, toMillis, inventoryEnabled),
            combine(
                dao.getPriorNominalProfitFlow(fromMillis),
                dao.getNominalOpeningBalanceFlow()
            ) { prior, nominalOpening -> prior to nominalOpening },
            combine(
                dao.getOpeningBalanceDifferenceFlow(),
                dao.getJournalImbalanceFlow(asOnMillis)
            ) { openingDiff, imbalance -> openingDiff to imbalance },
            stockValueAsOnFlow(asOnMillis)
        ) { balances, pnl, priors, checks, closingStock ->
            FinancialStatementEngine.balanceSheet(
                balances = balances,
                priorProfit = priors.first,
                nominalOpeningBalance = priors.second,
                openingDifference = checks.first,
                journalImbalance = checks.second,
                closingStockValue = closingStock,
                currentPeriodProfit = pnl.nettProfit,
                inventoryEnabled = inventoryEnabled
            )
        }

    /** Should always be zero; non-zero means a ledger has a dangling groupId. */
    val orphanedLedgerCountFlow: Flow<Int> = dao.getOrphanedLedgerCountFlow()

    suspend fun loginWithOtp(phoneNumber: String, otp: String): Boolean {
        // Frictionless OTP verification simulation (accepts any 6 digit OTP e.g. 123456 or auto-verified)
        val token = "jwt_token_user_${System.currentTimeMillis()}"

        // Preserve the existing profile. This used to build a brand-new UserEntity and
        // save it -- and because the primary key is the constant "primary_user" with
        // @Insert(onConflict = REPLACE), every login REPLACED the whole row. A business
        // name, GSTIN and address the user had carefully entered in Settings were wiped
        // on their next login and overwritten with "Indian Enterprise" and a fabricated
        // GSTIN. Nothing about verifying an OTP should touch the user's business
        // details, so it no longer does.
        val existing = dao.getUserDirect()
        val user = existing?.copy(
            phoneNumber = phoneNumber,
            token = token,
            isLoggedIn = true
        ) ?: UserEntity(
            phoneNumber = phoneNumber,
            token = token,
            isLoggedIn = true
        )
        dao.saveUser(user)
        return true
    }

    suspend fun logout() {
        dao.logoutUser()
    }

    suspend fun updateBusinessSettings(type: BusinessType, enableInventory: Boolean) {
        dao.updateBusinessSettings(type, enableInventory)
    }

    suspend fun updateUserProfile(updatedUser: UserEntity) {
        dao.saveUser(updatedUser)
    }

    suspend fun updateLedgerLocationDetails(
        ledgerId: Long,
        pincode: String,
        city: String,
        state: String,
        country: String,
        gstin: String
    ) {
        val ledger = dao.getLedgerById(ledgerId) ?: return
        val updated = ledger.copy(
            pincode = pincode,
            city = city,
            state = state,
            country = country,
            gstin = gstin
        )
        dao.insertLedger(updated)
    }

    suspend fun createLedger(
        name: String,
        groupName: String,
        category: LedgerCategory,
        openingBalance: Double = 0.0,
        balanceType: BalanceType = BalanceType.DR,
        gstin: String = "",
        city: String = "",
        state: String = ""
    ): Long {
        var group = dao.getLedgerGroupByName(groupName)
        if (group == null) {
            val groupId = dao.insertLedgerGroup(LedgerGroupEntity(name = groupName, category = category))
            group = LedgerGroupEntity(id = groupId, name = groupName, category = category)
        }

        val ledger = LedgerEntity(
            name = name,
            groupId = group.id,
            // These two were omitted, so every manually created ledger fell back to the
            // entity defaults "General Ledgers" / EXPENSE (H22). Reports read the joined
            // ledger_groups row and were unaffected, but the Edit dialog pre-fills from
            // these columns -- so opening any ledger and saving resolved the group name
            // "General Ledgers", created it if absent, and REASSIGNED groupId. Editing a
            // customer's opening balance silently moved their receivable out of Assets
            // and into expenses, and the Balance Sheet still tied because net profit
            // absorbed it exactly.
            groupName = group.name,
            category = group.category,
            openingBalance = openingBalance,
            balanceType = balanceType,
            gstin = gstin,
            city = city,
            state = state
        )
        return dao.insertLedger(ledger)
    }

    suspend fun updateLedgerDetails(
        id: Long,
        name: String,
        groupName: String,
        category: LedgerCategory,
        openingBalance: Double,
        balanceType: BalanceType,
        gstin: String = "",
        city: String = "",
        state: String = ""
    ) {
        var group = dao.getLedgerGroupByName(groupName)
        if (group == null) {
            val groupId = dao.insertLedgerGroup(LedgerGroupEntity(name = groupName, category = category))
            group = LedgerGroupEntity(id = groupId, name = groupName, category = category)
        }

        val existing = dao.getLedgerById(id) ?: return
        val updated = existing.copy(
            name = name,
            groupId = group.id,
            // Kept in step with groupId. Updating one without the other left the ledger
            // pointing at a new group while still reporting the old group's name, so the
            // next edit re-resolved the stale name and moved it again.
            groupName = group.name,
            category = group.category,
            openingBalance = openingBalance,
            balanceType = balanceType,
            gstin = gstin,
            city = city,
            state = state
        )
        dao.updateLedger(updated)
    }

    suspend fun deleteLedger(id: Long) {
        dao.deleteJournalEntriesByLedgerId(id)
        dao.deleteLedgerById(id)
    }

    /**
     * Posts a voucher against the two ledgers the user actually chose.
     *
     * The only generic Dr/Cr poster in the app. [createVoucher]'s per-type branches pick
     * both ledgers themselves from hardcoded names, which is correct for guided entry
     * (Sales, Purchase) and wrong for any form where the user names the accounts.
     *
     * [dateMillis] and [tags] are defaulted so existing callers are unaffected.
     */
    suspend fun createCustomVoucher(
        voucherType: VoucherType,
        debitLedgerName: String,
        creditLedgerName: String,
        amount: Double,
        narration: String = "",
        dateMillis: Long = System.currentTimeMillis(),
        tags: String = ""
    ): Long {
        val debitLedger = getLedgerByNameOrCreate(debitLedgerName, "General Ledgers", LedgerCategory.EXPENSE)
        val creditLedger = getLedgerByNameOrCreate(creditLedgerName, "Cash/Bank Accounts", LedgerCategory.ASSET)

        val voucherNo = generateNextVoucherNo(voucherType)
        val voucherEntity = VoucherEntity(
            voucherNo = voucherNo,
            voucherType = voucherType,
            date = dateMillis,
            partyName = "$debitLedgerName / $creditLedgerName",
            totalAmount = amount,
            gstAmount = 0.0,
            isInterstate = false,
            narration = narration.ifBlank { "Dr $debitLedgerName, Cr $creditLedgerName" },
            isSynced = false,
            tags = tags
        )
        val voucherId = dao.insertVoucher(voucherEntity)

        val journalEntries = listOf(
            JournalEntryEntity(voucherId = voucherId, ledgerId = debitLedger.id, debitAmount = amount, creditAmount = 0.0),
            JournalEntryEntity(voucherId = voucherId, ledgerId = creditLedger.id, debitAmount = 0.0, creditAmount = amount)
        )
        dao.insertJournalEntries(journalEntries)
        return voucherId
    }

    /**
     * Creates a double-entry voucher with automated credit/debit journal balancing and GST splitting.
     */
    /**
     * Posts a voucher's children: journal lines, inventory movement and line items.
     *
     * Extracted from `createVoucher` so `amendVoucher` reuses the identical ledger
     * resolution and posting rules. Duplicating them is how the three disagreeing GST
     * classifiers happened; one body, two callers.
     *
     * Assumes the caller has already cleared any existing children for [voucherId].
     */
    private suspend fun postVoucherChildren(
        voucherId: Long,
        voucherType: VoucherType,
        partyName: String,
        partyLedger: LedgerEntity,
        amount: Double,
        gstRate: Double,
        isInterstate: Boolean,
        narration: String,
        breakdown: GstTaxBreakdown,
        selectedItemId: Long?,
        itemQuantity: Double,
        itemRate: Double,
        inventoryEnabled: Boolean
    ) {
        val taxableValue = breakdown.taxableValue
        val totalGstAmount = breakdown.totalGstAmount
        val cgstAmount = breakdown.cgstAmount
        val sgstAmount = breakdown.sgstAmount
        val igstAmount = breakdown.igstAmount
        val journalEntries = mutableListOf<JournalEntryEntity>()

        when (voucherType) {
            VoucherType.SALES -> {
                // Debit: Party or Cash/Bank
                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = amount, creditAmount = 0.0)
                )
                // Credit: Sales Account
                val salesLedger = getLedgerByNameOrCreate("Sales Account", "Sales Accounts", LedgerCategory.REVENUE)
                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = salesLedger.id, debitAmount = 0.0, creditAmount = taxableValue)
                )
                // Credit: GST
                if (!isInterstate && totalGstAmount > 0) {
                    val cgst = getLedgerByNameOrCreate("Output CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("Output SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = 0.0, creditAmount = cgstAmount))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = 0.0, creditAmount = sgstAmount))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("Output IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = igst.id, debitAmount = 0.0, creditAmount = igstAmount))
                }

                // Inventory Update
                if (selectedItemId != null) {
                    dao.insertVoucherItems(
                        listOf(
                            VoucherItemEntity(
                                voucherId = voucherId,
                                itemId = selectedItemId,
                                quantity = itemQuantity,
                                rate = if (itemRate > 0) itemRate else taxableValue / itemQuantity,
                                amount = taxableValue,
                                gstRate = gstRate,
                                cgstAmount = cgstAmount,
                                sgstAmount = sgstAmount,
                                igstAmount = igstAmount
                            )
                        )
                    )
                    dao.updateStockQty(selectedItemId, -itemQuantity)
                }
            }

            VoucherType.SALES_RETURN -> {
                // Debit: Sales Return Account
                val salesReturnLedger = getLedgerByNameOrCreate("Sales Return Account", "Sales Accounts", LedgerCategory.REVENUE)
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = salesReturnLedger.id, debitAmount = taxableValue, creditAmount = 0.0))

                // Debit: GST
                if (!isInterstate && totalGstAmount > 0) {
                    val cgst = getLedgerByNameOrCreate("Output CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("Output SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = cgstAmount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = sgstAmount, creditAmount = 0.0))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("Output IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = igst.id, debitAmount = igstAmount, creditAmount = 0.0))
                }

                // Credit: Party or Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = 0.0, creditAmount = amount))

                // Inventory Update (Reverse - Add back to stock)
                if (selectedItemId != null) {
                    dao.insertVoucherItems(
                        listOf(
                            VoucherItemEntity(
                                voucherId = voucherId,
                                itemId = selectedItemId,
                                quantity = itemQuantity,
                                rate = if (itemRate > 0) itemRate else taxableValue / itemQuantity,
                                amount = taxableValue,
                                gstRate = gstRate,
                                cgstAmount = cgstAmount,
                                sgstAmount = sgstAmount,
                                igstAmount = igstAmount
                            )
                        )
                    )
                    dao.updateStockQty(selectedItemId, itemQuantity)
                }
            }

            VoucherType.PURCHASE -> {
                // What was bought decides the debit, not who sold it.
                //
                // This used to test the PARTY name for asset words, so buying an air
                // conditioner from "Carlson Traders" was booked as an expense while
                // anything at all bought from a vendor called "Car Motors" was booked as
                // a fixed asset. Worse, the asset ledger was resolved by the party's own
                // name — and getOrCreatePartyLedger had already created that name as a
                // LIABILITY under Sundry Creditors, so getLedgerByNameOrCreate found it
                // and returned it unchanged: the asset was debited into the vendor's
                // creditor account, which then carried a debit balance and no fixed asset
                // existed anywhere (H2).
                val assetWords = listOf("asset", "machinery", "equipment", "furniture", "vehicle", "computer")
                val isAssetPurchase = assetWords.any { narration.lowercase().contains(it) }

                val destinationLedger = if (isAssetPurchase) {
                    // Named for the thing bought, never for the vendor.
                    val assetName = narration.trim().ifBlank { "Fixed Asset" }.take(60)
                    getLedgerByNameOrCreate(assetName, "Fixed Assets", LedgerCategory.ASSET)
                } else if (inventoryEnabled) {
                    getLedgerByNameOrCreate("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE)
                } else {
                    // A service business has no Purchase Account — goods-for-resale is not
                    // a concept it has. Its buying is expense, and routing it through
                    // Purchase Accounts put it in a Trading Account it should never have.
                    getLedgerByNameOrCreate("Consumables & Direct Expenses", "Direct Expenses", LedgerCategory.EXPENSE)
                }

                // Credit the actual vendor. This used to credit a literal ledger called
                // "Sundry Creditors / Cash" for asset purchases — an invented account
                // name that lumped every asset vendor together.
                val vendorLedger = partyLedger

                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = vendorLedger.id, debitAmount = 0.0, creditAmount = amount)
                )

                // Debit: Purchase Account OR Fixed Asset Ledger
                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = destinationLedger.id, debitAmount = taxableValue, creditAmount = 0.0)
                )

                // Debit: GST
                if (!isInterstate && totalGstAmount > 0) {
                    val cgst = getLedgerByNameOrCreate("Input CGST", "Duties & Taxes", LedgerCategory.ASSET)
                    val sgst = getLedgerByNameOrCreate("Input SGST", "Duties & Taxes", LedgerCategory.ASSET)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = cgstAmount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = sgstAmount, creditAmount = 0.0))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("Input IGST", "Duties & Taxes", LedgerCategory.ASSET)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = igst.id, debitAmount = igstAmount, creditAmount = 0.0))
                }

                // Inventory Update
                if (selectedItemId != null) {
                    dao.insertVoucherItems(
                        listOf(
                            VoucherItemEntity(
                                voucherId = voucherId,
                                itemId = selectedItemId,
                                quantity = itemQuantity,
                                rate = if (itemRate > 0) itemRate else taxableValue / itemQuantity,
                                amount = taxableValue,
                                gstRate = gstRate,
                                cgstAmount = cgstAmount,
                                sgstAmount = sgstAmount,
                                igstAmount = igstAmount
                            )
                        )
                    )
                    // Receives at the purchase price and rolls the weighted-average cost
                    // forward. avgCostPrice was set once at item creation and never
                    // updated, so stock was valued at the price typed on setup day
                    // regardless of what had been paid since (H21).
                    dao.receiveStockAtCost(
                        itemId = selectedItemId,
                        quantity = itemQuantity,
                        unitCost = if (itemQuantity > 0) taxableValue / itemQuantity else 0.0
                    )
                }
            }

            VoucherType.PURCHASE_RETURN -> {
                // Debit: Vendor or Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = amount, creditAmount = 0.0))

                // Credit: Purchase Return Account
                val purchaseReturnLedger = getLedgerByNameOrCreate("Purchase Return Account", "Purchase Accounts", LedgerCategory.EXPENSE)
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = purchaseReturnLedger.id, debitAmount = 0.0, creditAmount = taxableValue))

                // Credit: GST
                if (!isInterstate && totalGstAmount > 0) {
                    val cgst = getLedgerByNameOrCreate("Input CGST", "Duties & Taxes", LedgerCategory.ASSET)
                    val sgst = getLedgerByNameOrCreate("Input SGST", "Duties & Taxes", LedgerCategory.ASSET)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = 0.0, creditAmount = cgstAmount))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = 0.0, creditAmount = sgstAmount))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("Input IGST", "Duties & Taxes", LedgerCategory.ASSET)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = igst.id, debitAmount = 0.0, creditAmount = igstAmount))
                }

                // Inventory Update (Reduce stock)
                if (selectedItemId != null) {
                    dao.insertVoucherItems(
                        listOf(
                            VoucherItemEntity(
                                voucherId = voucherId,
                                itemId = selectedItemId,
                                quantity = itemQuantity,
                                rate = if (itemRate > 0) itemRate else taxableValue / itemQuantity,
                                amount = taxableValue,
                                gstRate = gstRate,
                                cgstAmount = cgstAmount,
                                sgstAmount = sgstAmount,
                                igstAmount = igstAmount
                            )
                        )
                    )
                    dao.updateStockQty(selectedItemId, -itemQuantity)
                }
            }

            VoucherType.RECEIPT -> {
                val bankOrCashLedger = if (partyName.lowercase().contains("cash")) {
                    cashLedger()
                } else {
                    bankLedger()
                }

                // Debit: Bank/Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankOrCashLedger.id, debitAmount = amount, creditAmount = 0.0))
                // Credit: Customer Party / Payer
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = 0.0, creditAmount = amount))
            }

            VoucherType.PAYMENT -> {
                val bankOrCashLedger = if (partyName.lowercase().contains("cash")) {
                    cashLedger()
                } else {
                    bankLedger()
                }

                // Debit: Vendor / Payee / Asset
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = amount, creditAmount = 0.0))
                // Credit: Bank/Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankOrCashLedger.id, debitAmount = 0.0, creditAmount = amount))
            }

            VoucherType.CONTRA -> {
                val cashLedger = cashLedger()
                val bankLedger = bankLedger()

                if (partyName.lowercase().contains("withdrawal") || partyName.lowercase().contains("cash to bank")) {
                    // Debit Cash, Credit Bank
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cashLedger.id, debitAmount = amount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankLedger.id, debitAmount = 0.0, creditAmount = amount))
                } else {
                    // Debit Bank, Credit Cash (Deposit)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankLedger.id, debitAmount = amount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cashLedger.id, debitAmount = 0.0, creditAmount = amount))
                }
            }

            VoucherType.JOURNAL -> {
                val expLedger = getLedgerByNameOrCreate("General Expenses", "Expenses", LedgerCategory.EXPENSE)
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = expLedger.id, debitAmount = amount, creditAmount = 0.0))
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = 0.0, creditAmount = amount))
            }
        }

        dao.insertJournalEntries(journalEntries)
    }

    suspend fun createVoucher(
        voucherType: VoucherType,
        partyName: String,
        amount: Double,
        gstRate: Double = 18.0,
        isInterstate: Boolean = false,
        narration: String = "",
        selectedItemId: Long? = null,
        itemQuantity: Double = 1.0,
        itemRate: Double = 0.0,
        tags: String = ""
    ): Long {
        // 1. Calculate GST components via GstCalculationService
        val gstBreakdown = GstCalculationService.calculateGstBreakdown(
            totalAmountInclusive = amount,
            gstRatePercentage = gstRate,
            isInterstate = isInterstate
        )
        val taxableValue = gstBreakdown.taxableValue
        val totalGstAmount = gstBreakdown.totalGstAmount
        val cgstAmount = gstBreakdown.cgstAmount
        val sgstAmount = gstBreakdown.sgstAmount
        val igstAmount = gstBreakdown.igstAmount

        // 2. Ensure Party Ledger exists, or AUTO-CREATE
        val partyLedger = getOrCreatePartyLedger(partyName, voucherType)

        // 3. Save Voucher header
        val voucherNo = generateNextVoucherNo(voucherType)
        val voucherEntity = VoucherEntity(
            voucherNo = voucherNo,
            voucherType = voucherType,
            date = System.currentTimeMillis(),
            partyName = partyName,
            totalAmount = amount,
            gstAmount = totalGstAmount,
            isInterstate = isInterstate,
            narration = narration.ifBlank { "${voucherType.name} entry for $partyName" },
            isSynced = false,
            tags = tags
        )
        val voucherId = dao.insertVoucher(voucherEntity)

        // 4. Save GST Details
        if (totalGstAmount > 0) {
            dao.insertGstTaxDetail(
                GstTaxDetailEntity(
                    voucherId = voucherId,
                    isInterstate = isInterstate,
                    taxableValue = taxableValue,
                    cgstRate = gstBreakdown.cgstRate,
                    sgstRate = gstBreakdown.sgstRate,
                    igstRate = gstBreakdown.igstRate,
                    cgstAmount = cgstAmount,
                    sgstAmount = sgstAmount,
                    igstAmount = igstAmount
                )
            )
        }

        // 5. Post the children (journal, items, stock, GST) — shared with amendVoucher
        //    so the two can never drift apart.
        postVoucherChildren(
            voucherId = voucherId,
            voucherType = voucherType,
            partyName = partyName,
            partyLedger = partyLedger,
            amount = amount,
            gstRate = gstRate,
            isInterstate = isInterstate,
            narration = narration,
            breakdown = gstBreakdown,
            selectedItemId = selectedItemId,
            itemQuantity = itemQuantity,
            itemRate = itemRate,
            // Read once here rather than threaded through 14 call sites: a service
            // business has no Purchase Account, and routing its buying through one put
            // the expense inside a Trading Account it should never have had.
            inventoryEnabled = dao.getUserSync()?.enableInventory ?: true
        )

        // 6. Log for Background Sync
        val syncPayload = JSONObject().apply {
            put("voucherNo", voucherNo)
            put("type", voucherType.name)
            put("party", partyName)
            put("amount", amount)
            put("gst", totalGstAmount)
        }.toString()

        dao.insertSyncLog(SyncLogEntity(action = "CREATE_VOUCHER", payload = syncPayload))

        return voucherId
    }

    private suspend fun getOrCreatePartyLedger(partyName: String, voucherType: VoucherType): LedgerEntity {
        val existing = dao.getLedgerByName(partyName)
        if (existing != null) return existing

        val lower = partyName.lowercase()
        val defaultGroupName = when {
            lower.contains("loan") || lower.contains("borrow") -> "Secured Loans"
            lower.contains("asset") || lower.contains("machinery") || lower.contains("equipment") || lower.contains("furniture") -> "Fixed Assets"
            // A return's counterparty is the same kind of party as the original trade:
            // a sales return comes back from a customer, a purchase return goes back to a
            // supplier. Both used to fall through to "Sundry Debtors", so every new
            // vendor first seen on a purchase return was created as a receivable (H4).
            voucherType == VoucherType.SALES || voucherType == VoucherType.RECEIPT ||
                voucherType == VoucherType.SALES_RETURN -> "Sundry Debtors"
            voucherType == VoucherType.PURCHASE || voucherType == VoucherType.PAYMENT ||
                voucherType == VoucherType.PURCHASE_RETURN -> "Sundry Creditors"
            else -> "Suspense A/c"
        }
        val category = when {
            defaultGroupName == "Secured Loans" || defaultGroupName == "Sundry Creditors" -> LedgerCategory.LIABILITY
            defaultGroupName == "Fixed Assets" || defaultGroupName == "Sundry Debtors" -> LedgerCategory.ASSET
            // Suspense A/c is Tally's own home for a counterparty that cannot yet be
            // classified — an honest holding place, not a guess at Debtors.
            else -> LedgerCategory.LIABILITY
        }

        return getLedgerByNameOrCreate(partyName, defaultGroupName, category)
    }

    /**
     * Resolves one of the ledgers the posting engine must always be able to find.
     *
     * Three steps, in order:
     *   1. by [code] — the only reliable route once a book has been through the migration;
     *   2. by loose name (case, spaces and hyphens ignored) — so an existing book adopts
     *      the cash or bank account it already has instead of gaining a third one;
     *   3. create it.
     *
     * Replaces six hardcoded `getLedgerByNameOrCreate("Cash-in-hand", ...)` /
     * `("HDFC Bank Ltd", ...)` call sites. The name lookup was exact and
     * case-sensitive, and never matched the seeded "Cash in Hand", so every Receipt,
     * Payment and Contra silently created a duplicate cash ledger — and a hardcoded bank
     * name that had nothing to do with the user's actual bank.
     */
    private suspend fun getOrCreateSystemLedger(
        code: String,
        defaultName: String,
        groupName: String,
        category: LedgerCategory
    ): LedgerEntity {
        dao.getLedgerBySystemCode(code)?.let { return it }

        val adopted = dao.getLedgerByLooseName(defaultName)
        if (adopted != null) {
            val stamped = adopted.copy(systemCode = code)
            dao.updateLedger(stamped)
            return stamped
        }

        var group = dao.getLedgerGroupByName(groupName)
        if (group == null) {
            val groupId = dao.insertLedgerGroup(LedgerGroupEntity(name = groupName, category = category))
            group = LedgerGroupEntity(id = groupId, name = groupName, category = category)
        }
        val id = dao.insertLedger(
            LedgerEntity(
                name = defaultName,
                groupId = group.id,
                groupName = group.name,
                category = group.category,
                balanceType = BalanceType.DR,
                systemCode = code
            )
        )
        return dao.getLedgerById(id)!!
    }

    private suspend fun cashLedger(): LedgerEntity =
        getOrCreateSystemLedger(SYSTEM_LEDGER_CASH, "Cash in Hand", "Cash-in-Hand", LedgerCategory.ASSET)

    private suspend fun bankLedger(): LedgerEntity =
        getOrCreateSystemLedger(SYSTEM_LEDGER_BANK, "Bank Account", "Bank Accounts", LedgerCategory.ASSET)

    private suspend fun getLedgerByNameOrCreate(name: String, groupName: String, category: LedgerCategory): LedgerEntity {
        val existing = dao.getLedgerByName(name)
        if (existing != null) return existing

        var group = dao.getLedgerGroupByName(groupName)
        if (group == null) {
            val groupId = dao.insertLedgerGroup(LedgerGroupEntity(name = groupName, category = category))
            group = LedgerGroupEntity(id = groupId, name = groupName, category = category)
        }

        val ledgerId = dao.insertLedger(
            LedgerEntity(
                name = name,
                groupId = group.id,
                groupName = group.name,
                category = group.category,
                openingBalance = 0.0,
                balanceType = if (category == LedgerCategory.ASSET || category == LedgerCategory.EXPENSE) BalanceType.DR else BalanceType.CR
            )
        )
        return dao.getLedgerById(ledgerId)!!
    }

    /**
     * Everything needed to put a deleted voucher back exactly as it was.
     *
     * Undo used to re-post through `createVoucher`, which issued a NEW voucher number
     * (while the toast claimed to restore the old one), restamped the date to now,
     * guessed the GST rate as 18-or-0, forced isInterstate false, and dropped tags and
     * item links. Capturing the rows before deleting them means the restore reproduces
     * the voucher rather than approximating it.
     */
    data class DeletedVoucherSnapshot(
        val voucher: VoucherEntity,
        val journalEntries: List<JournalEntryEntity>,
        val items: List<VoucherItemEntity>,
        val gstDetail: GstTaxDetailEntity?
    )

    /** Deletes a voucher and returns everything needed to undo it. */
    suspend fun deleteVoucher(voucherId: Long): DeletedVoucherSnapshot? {
        val voucher = dao.getVoucherById(voucherId) ?: return null
        val snapshot = DeletedVoucherSnapshot(
            voucher = voucher,
            journalEntries = dao.getJournalEntriesForVoucher(voucherId),
            items = dao.getVoucherItemsForVoucher(voucherId),
            gstDetail = dao.getGstTaxDetailForVoucher(voucherId)
        )

        dao.deleteVoucherAtomic(
            voucherId = voucherId,
            stockReversals = snapshot.items.map { it.itemId to -stockDirection(voucher.voucherType) * it.quantity }
        )

        dao.insertSyncLog(
            SyncLogEntity(
                action = "DELETE_VOUCHER",
                payload = JSONObject().apply {
                    put("voucherId", voucherId)
                    put("voucherNo", voucher.voucherNo)
                    put("action", "DELETE")
                }.toString()
            )
        )
        return snapshot
    }

    /** Puts a deleted voucher back verbatim — same id, number, date, tags and postings. */
    suspend fun restoreDeletedVoucher(snapshot: DeletedVoucherSnapshot) {
        dao.restoreVoucherAtomic(
            voucher = snapshot.voucher,
            journalEntries = snapshot.journalEntries.map { it.copy(id = 0) },
            items = snapshot.items.map { it.copy(id = 0) },
            gstDetail = snapshot.gstDetail?.copy(id = 0),
            stockDeltas = snapshot.items.map {
                it.itemId to stockDirection(snapshot.voucher.voucherType) * it.quantity
            }
        )
    }

    /**
     * Amends a voucher that was posted against two user-chosen ledgers.
     *
     * Keeps those ledgers: it re-reads the existing journal entries and rewrites their
     * amounts, rather than re-deriving the accounts from the voucher type. Only the
     * amount, narration, date and tags are editable — changing which accounts a manual
     * journal hits is a different operation, and silently guessing them is what C3 was.
     */
    private suspend fun amendCustomVoucher(
        existing: VoucherEntity,
        amount: Double,
        narration: String,
        dateMillis: Long,
        tags: String
    ) {
        val entries = dao.getJournalEntriesForVoucher(existing.id)
        val rewritten = entries.map {
            it.copy(
                debitAmount = if (it.debitAmount > 0) amount else 0.0,
                creditAmount = if (it.creditAmount > 0) amount else 0.0
            )
        }
        dao.amendCustomVoucherAtomic(
            voucher = existing.copy(
                totalAmount = amount,
                narration = narration.ifBlank { existing.narration },
                date = dateMillis,
                tags = tags,
                isSynced = false
            ),
            journalEntries = rewritten
        )
    }

    /**
     * Amends a posted voucher in place, preserving its identity.
     *
     * Was delete-and-recreate with seven parameters, which meant every edit issued a new
     * voucher number (leaving a permanent gap in a statutorily consecutive series),
     * restamped the date to now, wiped tags, destroyed the inventory link, and orphaned
     * any reconciliation row pointing at the old id. `gstRate` and `isInterstate`
     * carried defaults of 18.0 and false, so a caller that forgot them silently rewrote
     * a 5% invoice to 18% and converted IGST to CGST+SGST. Those defaults are gone on
     * purpose: an incomplete call site is now a compile error.
     *
     * [amount] is GST-inclusive gross, the same contract `createVoucher` uses.
     */
    suspend fun amendVoucher(
        voucherId: Long,
        voucherType: VoucherType,
        partyName: String,
        amount: Double,
        gstRate: Double,
        isInterstate: Boolean,
        narration: String,
        dateMillis: Long,
        tags: String,
        // null means "unchanged", NOT "remove". Both edit dialogs omit these, and
        // treating omission as removal meant a narration edit reversed the stock movement
        // and never re-applied it: a sale of 10 units silently un-sold them, inflating
        // closing stock and net profit, and dropped the invoice out of GSTR-1 Table 12.
        selectedItemId: Long? = null,
        itemQuantity: Double? = null
    ) {
        val existing = dao.getVoucherById(voucherId) ?: return

        // A voucher created through the Manual/Custom form posts to two ledgers the user
        // named, and records them as "Dr X / Cr Y" in partyName. Re-posting it through
        // createVoucher's per-type branches would move it to hardcoded accounts and
        // manufacture a Sundry Debtor called "Dr X / Cr Y" — silently undoing C3. Those
        // vouchers are amended by re-running the custom posting instead.
        if (existing.partyName.startsWith("Dr ") && existing.partyName.contains(" / Cr ")) {
            amendCustomVoucher(existing, amount, narration, dateMillis, tags)
            return
        }

        val oldItems = dao.getVoucherItemsForVoucher(voucherId)

        // Carry the existing line item forward when the caller did not supply one.
        val effectiveItemId = selectedItemId ?: oldItems.firstOrNull()?.itemId
        val effectiveQty = itemQuantity
            ?: oldItems.firstOrNull()?.quantity
            ?: 1.0
        val effectiveRate = oldItems.firstOrNull()?.rate ?: 0.0

        val breakdown = GstCalculationService.calculateGstBreakdown(amount, gstRate, isInterstate)
        val partyLedger = getOrCreatePartyLedger(partyName, voucherType)

        // Reverse using the OLD voucher's type and quantities. The edit may have changed
        // the type, and reversing with the new one would move stock the wrong way.
        val reversals = oldItems.map { it.itemId to -stockDirection(existing.voucherType) * it.quantity }

        inTransaction {
        dao.clearVoucherChildrenAtomic(
            voucher = existing.copy(
                voucherType = voucherType,
                date = dateMillis,
                partyName = partyName,
                totalAmount = amount,
                gstAmount = breakdown.totalGstAmount,
                isInterstate = isInterstate,
                narration = narration.ifBlank { existing.narration },
                tags = tags,
                isSynced = false
            ),
            stockReversals = reversals
        )

        // Re-post through the same body createVoucher uses, against the SAME voucher id,
        // so the amendment cannot drift from a fresh posting.
        if (breakdown.totalGstAmount > 0) {
            dao.insertGstTaxDetail(
                GstTaxDetailEntity(
                    voucherId = voucherId,
                    isInterstate = isInterstate,
                    taxableValue = breakdown.taxableValue,
                    cgstRate = breakdown.cgstRate,
                    sgstRate = breakdown.sgstRate,
                    igstRate = breakdown.igstRate,
                    cgstAmount = breakdown.cgstAmount,
                    sgstAmount = breakdown.sgstAmount,
                    igstAmount = breakdown.igstAmount
                )
            )
        }
        postVoucherChildren(
            voucherId = voucherId,
            voucherType = voucherType,
            partyName = partyName,
            partyLedger = partyLedger,
            amount = amount,
            gstRate = gstRate,
            isInterstate = isInterstate,
            narration = narration,
            breakdown = breakdown,
            selectedItemId = effectiveItemId,
            itemQuantity = effectiveQty,
            // Preserve the original per-unit rate instead of forcing
            // taxableValue / quantity, which also produced Infinity at qty 0.
            itemRate = effectiveRate,
            inventoryEnabled = dao.getUserSync()?.enableInventory ?: true
        )
        }

        dao.insertSyncLog(
            SyncLogEntity(
                action = "UPDATE_VOUCHER",
                payload = JSONObject().apply {
                    put("voucherId", voucherId)
                    put("voucherNo", existing.voucherNo)
                    put("action", "UPDATE")
                }.toString()
            )
        )
    }

    /**
     * Creates a stock item, optionally with the quantity already on the shelf.
     *
     * [openingQty] used to be hardcoded to 0.0, so a business could not record the stock
     * it already held. That is the main reason stock went negative: the first sale of an
     * existing item had nothing to draw down from.
     */
    suspend fun createInventoryItem(
        name: String,
        unit: String,
        hsn: String,
        gstRate: Double,
        cost: Double,
        price: Double,
        openingQty: Double = 0.0
    ): Long {
        return dao.insertInventoryItem(
            InventoryItemEntity(
                name = name,
                unit = unit,
                hsnCode = hsn,
                gstRate = gstRate,
                stockQty = openingQty,
                avgCostPrice = cost,
                sellingPrice = price
            )
        )
    }

    /**
     * Items whose recorded quantity has gone below zero.
     *
     * Negative stock is deliberately allowed rather than blocked — Tally and Vyapar both
     * permit it, because businesses genuinely sell before recording the purchase, and
     * refusing the sale would be worse than recording it. What was wrong is that it
     * happened SILENTLY: `updateStockQty` is a raw `stockQty = stockQty + :delta` with no
     * check, so a book could drift negative and nothing anywhere said so.
     */
    val negativeStockItemsFlow: Flow<List<InventoryItemEntity>> = dao.getNegativeStockItemsFlow()


    suspend fun getLedgerTransactions(ledgerId: Long): List<LedgerTxEntry> {
        return dao.getLedgerTransactions(ledgerId)
    }

    // ---------------- Full-fidelity backup / restore ----------------

    /**
     * Captures the complete set of books, primary keys included.
     *
     * Distinct from [exportDataToJson], which is a lossy human-readable summary: it
     * carries four tables and a subset of their columns, and cannot be restored from
     * without regenerating voucher numbers and dates. Use this one for anything the
     * user would call a backup.
     */
    suspend fun exportBooksToJson(nowMillis: Long = System.currentTimeMillis()): String {
        val snapshot = BookBackupSerializer.BookSnapshot(
            user = dao.getUserDirect(),
            ledgerGroups = dao.getAllLedgerGroupsList(),
            ledgers = dao.getAllLedgersList(),
            inventoryItems = dao.getAllInventoryItemsList(),
            vouchers = dao.getAllVouchersList(),
            journalEntries = dao.getAllJournalEntriesList(),
            voucherItems = dao.getAllVoucherItemsList(),
            gstTaxDetails = dao.getAllGstTaxDetailsList(),
            voucherConfigs = dao.getAllVoucherConfigsSync(),
            reconciliations = dao.getAllReconciliationDiscrepanciesSync()
        )
        return BookBackupSerializer.toJson(snapshot, nowMillis)
    }

    /**
     * Replaces the current books with the contents of [jsonStr].
     *
     * This *replaces*; it does not merge. Importing the previous format appended
     * instead, so restoring a backup onto a non-empty database silently doubled every
     * voucher. Returns the number of rows restored, or throws
     * [BookBackupSerializer.IncompatibleBackupException] if the file cannot be read as
     * a complete set of books — in which case nothing is touched.
     */
    suspend fun restoreBooksFromJson(jsonStr: String): Int {
        val snapshot = BookBackupSerializer.fromJson(jsonStr)
        dao.replaceAllBooks(
            user = snapshot.user,
            ledgerGroups = snapshot.ledgerGroups,
            ledgers = snapshot.ledgers,
            inventoryItems = snapshot.inventoryItems,
            vouchers = snapshot.vouchers,
            journalEntries = snapshot.journalEntries,
            voucherItems = snapshot.voucherItems,
            gstTaxDetails = snapshot.gstTaxDetails,
            voucherConfigs = snapshot.voucherConfigs,
            reconciliations = snapshot.reconciliations
        )
        return snapshot.rowCount()
    }

    suspend fun exportDataToJson(): String {
        val root = JSONObject()
        root.put("app", "TallyMobileBackup")
        root.put("timestamp", System.currentTimeMillis())

        val user = dao.getUserDirect()
        if (user != null) {
            val userObj = JSONObject().apply {
                put("phoneNumber", user.phoneNumber)
                put("businessName", user.businessName)
                put("gstin", user.gstin)
                put("ownerName", user.ownerName)
                put("email", user.email)
                put("address", user.address)
                put("city", user.city)
                put("state", user.state)
                put("pincode", user.pincode)
            }
            root.put("user", userObj)
        }

        val vouchers = dao.getAllVouchersList()
        val vouchersArray = JSONArray()
        for (v in vouchers) {
            val vObj = JSONObject().apply {
                put("voucherNo", v.voucherNo)
                put("voucherType", v.voucherType.name)
                put("date", v.date)
                put("partyName", v.partyName)
                put("totalAmount", v.totalAmount)
                put("gstAmount", v.gstAmount)
                put("isInterstate", v.isInterstate)
                put("narration", v.narration)
            }
            vouchersArray.put(vObj)
        }
        root.put("vouchers", vouchersArray)

        val ledgers = dao.getAllLedgersList()
        val ledgersArray = JSONArray()
        for (l in ledgers) {
            val lObj = JSONObject().apply {
                put("name", l.name)
                put("openingBalance", l.openingBalance)
                put("balanceType", l.balanceType.name)
                put("pincode", l.pincode)
                put("city", l.city)
                put("state", l.state)
                put("gstin", l.gstin)
            }
            ledgersArray.put(lObj)
        }
        root.put("ledgers", ledgersArray)

        val items = dao.getAllInventoryItemsList()
        val itemsArray = JSONArray()
        for (i in items) {
            val iObj = JSONObject().apply {
                put("name", i.name)
                put("unit", i.unit)
                put("hsnCode", i.hsnCode)
                put("gstRate", i.gstRate)
                put("stockQty", i.stockQty)
                put("avgCostPrice", i.avgCostPrice)
                put("sellingPrice", i.sellingPrice)
            }
            itemsArray.put(iObj)
        }
        root.put("inventory", itemsArray)

        return root.toString(2)
    }

    suspend fun importDataFromJson(jsonStr: String): Boolean {
        try {
            val root = JSONObject(jsonStr)
            if (root.has("user")) {
                val uObj = root.getJSONObject("user")
                val existing = dao.getUserDirect() ?: UserEntity(phoneNumber = "", token = "imported")
                val updated = existing.copy(
                    businessName = uObj.optString("businessName", existing.businessName),
                    gstin = uObj.optString("gstin", existing.gstin),
                    ownerName = uObj.optString("ownerName", existing.ownerName),
                    email = uObj.optString("email", existing.email),
                    address = uObj.optString("address", existing.address),
                    city = uObj.optString("city", existing.city),
                    state = uObj.optString("state", existing.state),
                    pincode = uObj.optString("pincode", existing.pincode)
                )
                dao.saveUser(updated)
            }

            if (root.has("ledgers")) {
                val lArr = root.getJSONArray("ledgers")
                for (i in 0 until lArr.length()) {
                    val lObj = lArr.getJSONObject(i)
                    val name = lObj.getString("name")
                    getLedgerByNameOrCreate(name, "Sundry Debtors", LedgerCategory.ASSET)
                }
            }

            if (root.has("vouchers")) {
                val vArr = root.getJSONArray("vouchers")
                for (i in 0 until vArr.length()) {
                    val vObj = vArr.getJSONObject(i)
                    val type = VoucherType.valueOf(vObj.getString("voucherType"))
                    val party = vObj.getString("partyName")
                    val amount = vObj.getDouble("totalAmount")
                    val gstAmt = vObj.optDouble("gstAmount", 0.0)
                    val isInter = vObj.optBoolean("isInterstate", false)
                    val narration = vObj.optString("narration", "")

                    val gstRate = if (amount > 0 && gstAmt > 0) ((gstAmt / (amount - gstAmt)) * 100.0) else 18.0

                    createVoucher(
                        voucherType = type,
                        partyName = party,
                        amount = amount,
                        gstRate = gstRate,
                        isInterstate = isInter,
                        narration = narration
                    )
                }
            }

            if (root.has("inventory")) {
                val iArr = root.getJSONArray("inventory")
                for (i in 0 until iArr.length()) {
                    val iObj = iArr.getJSONObject(i)
                    val name = iObj.getString("name")
                    val unit = iObj.optString("unit", "Pcs")
                    val hsn = iObj.optString("hsnCode", "8471")
                    val gstRate = iObj.optDouble("gstRate", 18.0)
                    val cost = iObj.optDouble("avgCostPrice", 0.0)
                    val price = iObj.optDouble("sellingPrice", 0.0)

                    createInventoryItem(name, unit, hsn, gstRate, cost, price)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    val voucherConfigsFlow: Flow<List<VoucherTypeConfigEntity>> = dao.getAllVoucherConfigsFlow()
    val reconciliationDiscrepanciesFlow: Flow<List<ReconciliationDiscrepancyEntity>> = dao.getAllReconciliationDiscrepanciesFlow()
    val unresolvedDiscrepancyCountFlow: Flow<Int> = dao.getUnresolvedDiscrepancyCountFlow()

    suspend fun runLedgerReconciliation(): Int {
        return com.example.service.LedgerReconciliationWorker.performReconciliation(dao)
    }

    suspend fun resolveReconciliationDiscrepancy(id: Long, resolutionNotes: String = "Resolved by user") {
        dao.markDiscrepancyResolved(id, resolutionNotes)
    }

    suspend fun clearAllReconciliationDiscrepancies() {
        dao.clearAllReconciliationDiscrepancies()
    }

    suspend fun updateVoucherConfig(type: String, prefix: String, nextNumber: Long, autoIncrement: Boolean, description: String = "") {
        val existing = dao.getVoucherConfigByType(type)
        val updated = VoucherTypeConfigEntity(
            voucherType = type,
            prefix = prefix,
            nextNumber = nextNumber,
            autoIncrement = autoIncrement,
            description = if (description.isNotBlank()) description else (existing?.description ?: "")
        )
        dao.insertOrUpdateVoucherConfig(updated)
    }

    /**
     * Issues the next voucher number, atomically, skipping any number already in use.
     *
     * The counter is reserved inside a transaction (H7) — the previous read-then-write
     * pair let two concurrent posts issue the same invoice number, which the XML import
     * triggers deterministically by launching one coroutine per row.
     *
     * The skip loop matters because a restore can rewind the counter below numbers that
     * already exist in the books; without it those numbers would silently duplicate.
     */
    /** Issues the next voucher number. Reservation and duplicate-skip are atomic (H7). */
    suspend fun generateNextVoucherNo(voucherType: VoucherType): String =
        dao.reserveNextVoucherNumber(voucherType.name) ?: seedMissingConfigAndReserve(voucherType)

    /**
     * Recreates a missing numbering config, then reserves from it.
     *
     * Replaces a `COUNT(vouchers) + 1001` fallback, which collides deterministically
     * after any deletion. The config table can genuinely end up empty: restoring a
     * truncated backup wipes it and re-inserts only what the snapshot carried.
     */
    private suspend fun seedMissingConfigAndReserve(voucherType: VoucherType): String {
        val fyShort = FiscalYearUtils.currentFyLabel().removePrefix("FY ")
        val prefix = when (voucherType) {
            VoucherType.SALES -> "INV/$fyShort/"
            VoucherType.PURCHASE -> "PUR/$fyShort/"
            VoucherType.RECEIPT -> "REC/$fyShort/"
            VoucherType.PAYMENT -> "PAY/$fyShort/"
            VoucherType.JOURNAL -> "JRN/$fyShort/"
            VoucherType.CONTRA -> "CTR/$fyShort/"
            VoucherType.SALES_RETURN -> "SRN/$fyShort/"
            VoucherType.PURCHASE_RETURN -> "PRN/$fyShort/"
        }
        dao.insertOrUpdateVoucherConfig(
            VoucherTypeConfigEntity(voucherType = voucherType.name, prefix = prefix, nextNumber = 1001)
        )
        return generateNextVoucherNo(voucherType)
    }

    /**
     * The single source of truth for which way a voucher moves stock.
     *
     * Replaces four scattered signs in `createVoucher` and a two-branch check in
     * `deleteVoucher` that handled only SALES and PURCHASE — so deleting a
     * SALES_RETURN or PURCHASE_RETURN never reversed its stock movement at all (H6),
     * leaving quantity permanently wrong while the valuation query, which does handle
     * all four types, disagreed with it.
     */
    // Output tax and input credit are separate ledgers.
    //
    // One "CGST" ledger used to carry both, so the tax collected on sales and the credit
    // paid on purchases netted against each other inside a single balance. The books
    // could then show a small net figure that concealed a large liability and a large
    // receivable, and there was no way to read unutilised ITC off the Balance Sheet at
    // all. Both stay under Tally's "Duties & Taxes" group, as separate accounts.

    private fun stockDirection(type: VoucherType): Double = when (type) {
        VoucherType.SALES, VoucherType.PURCHASE_RETURN -> -1.0
        VoucherType.PURCHASE, VoucherType.SALES_RETURN -> 1.0
        else -> 0.0
    }
}
