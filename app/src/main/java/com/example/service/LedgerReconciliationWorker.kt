package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.dao.AccountingDao
import com.example.data.db.AppDatabase
import com.example.data.model.ReconciliationDiscrepancyEntity
import com.example.data.model.ReconciliationStatus
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.data.preference.UserSettingsDataStore
import com.example.utils.IndianFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Background Worker Service that automatically reconciles ledger entries by matching incoming/outgoing
 * transaction amounts against expected invoice values to flag shortfalls, overpayments, and unmatched transactions.
 */
class LedgerReconciliationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = applicationContext
            val db = AppDatabase.getDatabase(context)
            val dao = db.accountingDao()

            val discrepancyCount = performReconciliation(dao)

            val displayTimeStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
            UserSettingsDataStore(context).updateLastReconciliationTime(displayTimeStr, discrepancyCount)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "LedgerReconciliationWork"

        /**
         * Core auto-reconciliation algorithm.
         * Analyzes all vouchers, compares expected invoice totals with receipts/payments,
         * and writes flagged discrepancies into the database.
         */
        suspend fun performReconciliation(dao: AccountingDao): Int {
            val vouchers = dao.getAllVouchersSync()
            if (vouchers.isEmpty()) return 0

            val salesVouchers = vouchers.filter { it.voucherType == VoucherType.SALES }
            val purchaseVouchers = vouchers.filter { it.voucherType == VoucherType.PURCHASE }
            val receiptVouchers = vouchers.filter { it.voucherType == VoucherType.RECEIPT }
            val paymentVouchers = vouchers.filter { it.voucherType == VoucherType.PAYMENT }
            val salesReturnVouchers = vouchers.filter { it.voucherType == VoucherType.SALES_RETURN }
            val purchaseReturnVouchers = vouchers.filter { it.voucherType == VoucherType.PURCHASE_RETURN }

            val flaggedDiscrepancies = mutableListOf<ReconciliationDiscrepancyEntity>()

            // 1. Invoice-Level Specific Matching (Sales Invoices vs Receipts)
            salesVouchers.forEach { salesInv ->
                val party = salesInv.partyName
                val invNo = salesInv.voucherNo
                val expected = salesInv.totalAmount

                // Find receipts explicitly referencing this invoice in narration or matching invoice number
                // Bounded match. `contains` is a substring test, so invoice "INV/2026-27/1"
                // matched every receipt narrated for INV/2026-27/10, /11, /12 — invoice 1
                // swallowed ten other invoices' receipts and reported an EXCESS while each of
                // those ten reported a shortfall. The reference must end at a token boundary.
                fun mentions(text: String): Boolean {
                    if (invNo.isBlank()) return false
                    val idx = text.indexOf(invNo, ignoreCase = true)
                    if (idx < 0) return false
                    val after = idx + invNo.length
                    return after >= text.length || !text[after].isLetterOrDigit()
                }
                val matchedReceipts = receiptVouchers.filter { receipt ->
                    receipt.partyName.equals(party, ignoreCase = true) &&
                            (mentions(receipt.narration) || mentions(receipt.tags))
                }

                if (matchedReceipts.isNotEmpty()) {
                    val totalReceivedForInv = matchedReceipts.sumOf { it.totalAmount }
                    val diff = expected - totalReceivedForInv

                    if (Math.abs(diff) > 0.01) {
                        val status = if (diff > 0) ReconciliationStatus.SHORTFALL else ReconciliationStatus.EXCESS
                        val reason = if (diff > 0) {
                            "Shortfall: Receipt total (₹${formatAmount(totalReceivedForInv)}) is less than Sales Invoice $invNo total (₹${formatAmount(expected)})."
                        } else {
                            "Excess Payment: Receipt total (₹${formatAmount(totalReceivedForInv)}) exceeds Sales Invoice $invNo total (₹${formatAmount(expected)})."
                        }

                        flaggedDiscrepancies.add(
                            ReconciliationDiscrepancyEntity(
                                partyName = party,
                                invoiceVoucherNo = invNo,
                                invoiceVoucherId = salesInv.id,
                                voucherType = VoucherType.SALES,
                                expectedAmount = expected,
                                receivedAmount = totalReceivedForInv,
                                discrepancyAmount = Math.abs(diff),
                                status = status,
                                discrepancyReason = reason
                            )
                        )
                    }
                }
            }

            // 2. Party-Level Aggregate Sales vs Receipts Reconciliation
            val allParties = vouchers.map { it.partyName }.distinct().filter { it.isNotBlank() }

            allParties.forEach { party ->
                val partySales = salesVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }
                val partySalesReturns = salesReturnVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }
                val netExpectedSales = (partySales - partySalesReturns).coerceAtLeast(0.0)

                val partyReceipts = receiptVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }

                // Check if we haven't already flagged invoice-specific discrepancies for this party
                val hasSpecificInvoiceFlag = flaggedDiscrepancies.any { it.partyName.equals(party, ignoreCase = true) }

                if (!hasSpecificInvoiceFlag && netExpectedSales > 0.0) {
                    val diff = netExpectedSales - partyReceipts
                    // The SHORTFALL branch that stood here flagged every credit customer with
                    // an open balance. Every sale is booked on account, so billed-minus-received
                    // IS the receivable — the normal state of a credit business, not a
                    // discrepancy — and it was computed worse than the app's own receivables
                    // figure: it reads no opening balance, so a migrated party who has since
                    // paid in full still showed a balance, and it counts only SALES and RECEIPT
                    // vouchers, so a receivable settled by journal or contra read as unpaid.
                    // The posted party balance is already shown, correctly, on the
                    // "Customers (Receivables)" card.
                    //
                    // Receipts EXCEEDING billed sales is a real anomaly: money arrived that the
                    // books cannot place — an unrecorded invoice, a payment credited to the
                    // wrong party, or a duplicated receipt. Only that case survives here.
                    if (diff < -0.01) {
                        flaggedDiscrepancies.add(
                            ReconciliationDiscrepancyEntity(
                                partyName = party,
                                invoiceVoucherNo = "PARTY-TOTAL",
                                voucherType = VoucherType.SALES,
                                expectedAmount = netExpectedSales,
                                receivedAmount = partyReceipts,
                                discrepancyAmount = Math.abs(diff),
                                status = ReconciliationStatus.EXCESS,
                                discrepancyReason = "Party Advance/Excess: Total Receipts (₹${formatAmount(partyReceipts)}) exceeds billed Sales (₹${formatAmount(netExpectedSales)}) by ₹${formatAmount(Math.abs(diff))}."
                            )
                        )
                    }
                }

                // Unmatched Receipts (Party has receipts but 0 sales invoices)
                if (partySales == 0.0 && partyReceipts > 0.0) {
                    flaggedDiscrepancies.add(
                        ReconciliationDiscrepancyEntity(
                            partyName = party,
                            invoiceVoucherNo = "UNMATCHED",
                            voucherType = VoucherType.RECEIPT,
                            expectedAmount = 0.0,
                            receivedAmount = partyReceipts,
                            discrepancyAmount = partyReceipts,
                            status = ReconciliationStatus.UNMATCHED_PAYMENT,
                            discrepancyReason = "Unmatched Receipt: Received ₹${formatAmount(partyReceipts)} from $party without any recorded sales invoice."
                        )
                    )
                }

                // 3. Purchase vs Payment Reconciliation
                val partyPurchases = purchaseVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }
                val partyPurchaseReturns = purchaseReturnVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }
                val netExpectedPurchases = (partyPurchases - partyPurchaseReturns).coerceAtLeast(0.0)

                val partyPayments = paymentVouchers.filter { it.partyName.equals(party, ignoreCase = true) }.sumOf { it.totalAmount }

                // The mirror of the same non-event: an unpaid purchase bill is a payable,
                // not a discrepancy. "Vendor Owed Balance" flagged every supplier not yet
                // paid — including bills not yet due — under the same SHORTFALL status as a
                // genuine mismatch. What the user needs here is an ageing report keyed off a
                // credit period; neither LedgerEntity nor VoucherEntity records one, so it is
                // not derivable today and is not being approximated.
                if (netExpectedPurchases > 0.0 && partyPayments > netExpectedPurchases + 0.01) {
                    flaggedDiscrepancies.add(
                        ReconciliationDiscrepancyEntity(
                            partyName = party,
                            invoiceVoucherNo = "PURCHASE-TOTAL",
                            voucherType = VoucherType.PURCHASE,
                            expectedAmount = netExpectedPurchases,
                            receivedAmount = partyPayments,
                            discrepancyAmount = partyPayments - netExpectedPurchases,
                            status = ReconciliationStatus.EXCESS,
                            discrepancyReason = "Vendor Overpaid: Payments (₹${formatAmount(partyPayments)}) exceed Purchase Bills (₹${formatAmount(netExpectedPurchases)}) by ₹${formatAmount(partyPayments - netExpectedPurchases)}. Check for a missing bill or a duplicated payment."
                        )
                    )
                }
            }

            // Preserve resolved records, replace unresolved ones
            val existingList = dao.getAllReconciliationDiscrepanciesSync()
            val resolvedIds = existingList.filter { it.isResolved }.map { "${it.partyName}_${it.invoiceVoucherNo}" }.toSet()

            dao.clearUnresolvedReconciliationDiscrepancies()

            var count = 0
            flaggedDiscrepancies.forEach { discrepancy ->
                val key = "${discrepancy.partyName}_${discrepancy.invoiceVoucherNo}"
                if (!resolvedIds.contains(key)) {
                    dao.insertReconciliationDiscrepancy(discrepancy)
                    count++
                }
            }

            return count
        }

        /**
         * Was `String.format("%.2f", amount)` with no Locale, so it took the device
         * default. On any comma-decimal locale a discrepancy reason rendered "1234,56",
         * which reads as 1,234 to an Indian user — off by a factor of a hundred. These
         * strings are stored in the database and read back months later.
         */
        private fun formatAmount(amount: Double): String =
            IndianFormatter.formatRupee(amount, includeSymbol = false)

        fun schedulePeriodicReconciliation(context: Context, enabled: Boolean) {
            try {
                val workManager = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<LedgerReconciliationWorker>(12, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .build()
                        )
                        .build()
                    workManager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
                } else {
                    workManager.cancelUniqueWork(WORK_NAME)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun triggerImmediateReconciliation(context: Context) {
            try {
                val workManager = WorkManager.getInstance(context)
                val request = OneTimeWorkRequestBuilder<LedgerReconciliationWorker>().build()
                workManager.enqueue(request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
