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
                val matchedReceipts = receiptVouchers.filter { receipt ->
                    receipt.partyName.equals(party, ignoreCase = true) &&
                            (receipt.narration.contains(invNo, ignoreCase = true) || receipt.tags.contains(invNo, ignoreCase = true))
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
                    if (diff > 0.01) {
                        flaggedDiscrepancies.add(
                            ReconciliationDiscrepancyEntity(
                                partyName = party,
                                invoiceVoucherNo = "PARTY-TOTAL",
                                voucherType = VoucherType.SALES,
                                expectedAmount = netExpectedSales,
                                receivedAmount = partyReceipts,
                                discrepancyAmount = diff,
                                status = ReconciliationStatus.SHORTFALL,
                                discrepancyReason = "Party Ledger Shortfall: Total billed Sales (₹${formatAmount(netExpectedSales)}) exceeds total Receipts (₹${formatAmount(partyReceipts)}) by ₹${formatAmount(diff)}."
                            )
                        )
                    } else if (diff < -0.01) {
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

                if (netExpectedPurchases > 0.0 && partyPayments < netExpectedPurchases) {
                    val diff = netExpectedPurchases - partyPayments
                    if (diff > 0.01) {
                        flaggedDiscrepancies.add(
                            ReconciliationDiscrepancyEntity(
                                partyName = party,
                                invoiceVoucherNo = "PURCHASE-TOTAL",
                                voucherType = VoucherType.PURCHASE,
                                expectedAmount = netExpectedPurchases,
                                receivedAmount = partyPayments,
                                discrepancyAmount = diff,
                                status = ReconciliationStatus.SHORTFALL,
                                discrepancyReason = "Vendor Owed Balance: Pending payment of ₹${formatAmount(diff)} against Purchase Bills total ₹${formatAmount(netExpectedPurchases)}."
                            )
                        )
                    }
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

        private fun formatAmount(amount: Double): String {
            return String.format("%.2f", amount)
        }

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
