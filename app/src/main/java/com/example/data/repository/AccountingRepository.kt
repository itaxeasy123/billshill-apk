package com.example.data.repository

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

class AccountingRepository(private val dao: AccountingDao) {

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

        // 5. Generate Double-Entry Journal Lines (Debits = Credits)
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
                    val cgst = getLedgerByNameOrCreate("CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = 0.0, creditAmount = cgstAmount))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = 0.0, creditAmount = sgstAmount))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
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
                    val cgst = getLedgerByNameOrCreate("CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = cgstAmount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = sgstAmount, creditAmount = 0.0))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
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
                val isAssetPurchase = partyName.lowercase().contains("asset") || 
                        partyName.lowercase().contains("air conditioner") || 
                        partyName.lowercase().contains("computer") || 
                        partyName.lowercase().contains("car") || 
                        partyName.lowercase().contains("furniture") || 
                        partyName.lowercase().contains("machinery") || 
                        narration.lowercase().contains("asset")

                val destinationLedger = if (isAssetPurchase) {
                    getLedgerByNameOrCreate(partyName, "Fixed Assets", LedgerCategory.ASSET)
                } else {
                    getLedgerByNameOrCreate("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE)
                }

                // Credit: Vendor / Cash / Bank
                val vendorLedger = if (isAssetPurchase) {
                    getLedgerByNameOrCreate("Sundry Creditors / Cash", "Sundry Creditors", LedgerCategory.LIABILITY)
                } else {
                    partyLedger
                }

                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = vendorLedger.id, debitAmount = 0.0, creditAmount = amount)
                )

                // Debit: Purchase Account OR Fixed Asset Ledger
                journalEntries.add(
                    JournalEntryEntity(voucherId = voucherId, ledgerId = destinationLedger.id, debitAmount = taxableValue, creditAmount = 0.0)
                )

                // Debit: GST
                if (!isInterstate && totalGstAmount > 0) {
                    val cgst = getLedgerByNameOrCreate("CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = cgstAmount, creditAmount = 0.0))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = sgstAmount, creditAmount = 0.0))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
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
                    dao.updateStockQty(selectedItemId, itemQuantity)
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
                    val cgst = getLedgerByNameOrCreate("CGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    val sgst = getLedgerByNameOrCreate("SGST", "Duties & Taxes", LedgerCategory.LIABILITY)
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = cgst.id, debitAmount = 0.0, creditAmount = cgstAmount))
                    journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = sgst.id, debitAmount = 0.0, creditAmount = sgstAmount))
                } else if (isInterstate && totalGstAmount > 0) {
                    val igst = getLedgerByNameOrCreate("IGST", "Duties & Taxes", LedgerCategory.LIABILITY)
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
                    getLedgerByNameOrCreate("Cash-in-hand", "Cash-in-hand", LedgerCategory.ASSET)
                } else {
                    getLedgerByNameOrCreate("HDFC Bank Ltd", "Bank Accounts", LedgerCategory.ASSET)
                }

                // Debit: Bank/Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankOrCashLedger.id, debitAmount = amount, creditAmount = 0.0))
                // Credit: Customer Party / Payer
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = 0.0, creditAmount = amount))
            }

            VoucherType.PAYMENT -> {
                val bankOrCashLedger = if (partyName.lowercase().contains("cash")) {
                    getLedgerByNameOrCreate("Cash-in-hand", "Cash-in-hand", LedgerCategory.ASSET)
                } else {
                    getLedgerByNameOrCreate("HDFC Bank Ltd", "Bank Accounts", LedgerCategory.ASSET)
                }

                // Debit: Vendor / Payee / Asset
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = partyLedger.id, debitAmount = amount, creditAmount = 0.0))
                // Credit: Bank/Cash
                journalEntries.add(JournalEntryEntity(voucherId = voucherId, ledgerId = bankOrCashLedger.id, debitAmount = 0.0, creditAmount = amount))
            }

            VoucherType.CONTRA -> {
                val cashLedger = getLedgerByNameOrCreate("Cash-in-hand", "Cash-in-hand", LedgerCategory.ASSET)
                val bankLedger = getLedgerByNameOrCreate("HDFC Bank Ltd", "Bank Accounts", LedgerCategory.ASSET)

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
            voucherType == VoucherType.SALES || voucherType == VoucherType.RECEIPT -> "Sundry Debtors"
            voucherType == VoucherType.PURCHASE || voucherType == VoucherType.PAYMENT -> "Sundry Creditors"
            else -> "Sundry Debtors"
        }
        val category = when {
            defaultGroupName == "Secured Loans" || defaultGroupName == "Sundry Creditors" -> LedgerCategory.LIABILITY
            defaultGroupName == "Fixed Assets" || defaultGroupName == "Sundry Debtors" -> LedgerCategory.ASSET
            else -> LedgerCategory.ASSET
        }

        return getLedgerByNameOrCreate(partyName, defaultGroupName, category)
    }

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

    suspend fun deleteVoucher(voucherId: Long) {
        val voucher = dao.getVoucherById(voucherId) ?: return
        val items = dao.getVoucherItemsForVoucher(voucherId)
        for (item in items) {
            if (voucher.voucherType == VoucherType.SALES) {
                dao.updateStockQty(item.itemId, item.quantity) // Restore stock
            } else if (voucher.voucherType == VoucherType.PURCHASE) {
                dao.updateStockQty(item.itemId, -item.quantity) // Reverse stock
            }
        }
        dao.deleteJournalEntriesForVoucher(voucherId)
        dao.deleteVoucherItemsForVoucher(voucherId)
        dao.deleteGstDetailForVoucher(voucherId)
        dao.deleteVoucherById(voucherId)

        val syncPayload = JSONObject().apply {
            put("voucherId", voucherId)
            put("action", "DELETE")
        }.toString()
        dao.insertSyncLog(SyncLogEntity(action = "DELETE_VOUCHER", payload = syncPayload))
    }

    suspend fun updateVoucher(
        voucherId: Long,
        voucherType: VoucherType,
        partyName: String,
        amount: Double,
        gstRate: Double = 18.0,
        isInterstate: Boolean = false,
        narration: String = ""
    ) {
        deleteVoucher(voucherId)
        createVoucher(
            voucherType = voucherType,
            partyName = partyName,
            amount = amount,
            gstRate = gstRate,
            isInterstate = isInterstate,
            narration = narration
        )
    }

    suspend fun createInventoryItem(name: String, unit: String, hsn: String, gstRate: Double, cost: Double, price: Double): Long {
        return dao.insertInventoryItem(
            InventoryItemEntity(
                name = name,
                unit = unit,
                hsnCode = hsn,
                gstRate = gstRate,
                stockQty = 0.0,
                avgCostPrice = cost,
                sellingPrice = price
            )
        )
    }

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

    suspend fun generateNextVoucherNo(voucherType: VoucherType): String {
        val config = dao.getVoucherConfigByType(voucherType.name)
        if (config != null) {
            val prefix = config.prefix
            val seq = config.nextNumber
            val formattedSeq = String.format("%04d", seq)
            if (config.autoIncrement) {
                dao.insertOrUpdateVoucherConfig(config.copy(nextNumber = seq + 1))
            }
            return "$prefix$formattedSeq"
        } else {
            val prefix = when (voucherType) {
                VoucherType.SALES -> "INV/25-26/"
                VoucherType.PURCHASE -> "PUR/25-26/"
                VoucherType.RECEIPT -> "REC/25-26/"
                VoucherType.PAYMENT -> "PAY/25-26/"
                VoucherType.JOURNAL -> "JRN/25-26/"
                VoucherType.CONTRA -> "CTR/25-26/"
                VoucherType.SALES_RETURN -> "SRN/25-26/"
                VoucherType.PURCHASE_RETURN -> "PRN/25-26/"
            }
            val count = dao.getAllVouchersSync().filter { it.voucherType == voucherType }.size + 1001
            return "$prefix$count"
        }
    }
}
