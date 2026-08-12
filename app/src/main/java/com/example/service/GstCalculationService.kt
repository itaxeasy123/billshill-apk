package com.example.service

import com.example.data.model.JournalEntryEntity
import com.example.data.model.VoucherType

data class GstCalculationResult(
    val grossAmount: Double,
    val taxableValue: Double,
    val totalGstAmount: Double,
    val cgstRate: Double,
    val sgstRate: Double,
    val igstRate: Double,
    val cgstAmount: Double,
    val sgstAmount: Double,
    val igstAmount: Double,
    val isInterstate: Boolean
)

data class GstJournalSplit(
    val calculation: GstCalculationResult,
    val journalEntries: List<JournalEntryEntity>
)

object GstCalculationService {

    /**
     * Calculates CGST, SGST, IGST breakdown from total gross invoice amount and GST tax slab rate.
     * Handles intra-state (CGST + SGST) vs inter-state (IGST) taxation rules under Indian GST law.
     */
    fun calculateGst(
        grossAmount: Double,
        gstRate: Double,
        isInterstate: Boolean
    ): GstCalculationResult {
        if (grossAmount <= 0.0) {
            return GstCalculationResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, isInterstate)
        }

        val taxableBase = if (gstRate > 0) grossAmount / (1.0 + (gstRate / 100.0)) else grossAmount
        val gstTaxTotal = grossAmount - taxableBase

        return if (isInterstate) {
            GstCalculationResult(
                grossAmount = grossAmount,
                taxableValue = taxableBase,
                totalGstAmount = gstTaxTotal,
                cgstRate = 0.0,
                sgstRate = 0.0,
                igstRate = gstRate,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                igstAmount = gstTaxTotal,
                isInterstate = true
            )
        } else {
            val halfRate = gstRate / 2.0
            val halfTax = gstTaxTotal / 2.0
            GstCalculationResult(
                grossAmount = grossAmount,
                taxableValue = taxableBase,
                totalGstAmount = gstTaxTotal,
                cgstRate = halfRate,
                sgstRate = halfRate,
                igstRate = 0.0,
                cgstAmount = halfTax,
                sgstAmount = halfTax,
                igstAmount = 0.0,
                isInterstate = false
            )
        }
    }

    /**
     * Splits calculated GST into distinct balanced double-entry journal lines (debits & credits)
     * based on voucher type (Sales vs Purchase) and target ledger IDs.
     */
    fun generateDoubleEntryJournalLines(
        voucherId: Long,
        voucherType: VoucherType,
        calculation: GstCalculationResult,
        partyLedgerId: Long,
        baseSalesOrPurchaseLedgerId: Long,
        cgstLedgerId: Long,
        sgstLedgerId: Long,
        igstLedgerId: Long
    ): List<JournalEntryEntity> {
        val entries = mutableListOf<JournalEntryEntity>()

        when (voucherType) {
            VoucherType.SALES -> {
                // Debit Party (Asset / Receivable) for full invoice amount
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = partyLedgerId,
                        debitAmount = calculation.grossAmount,
                        creditAmount = 0.0
                    )
                )

                // Credit Sales Revenue Ledger for net taxable value
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = baseSalesOrPurchaseLedgerId,
                        debitAmount = 0.0,
                        creditAmount = calculation.taxableValue
                    )
                )

                // Credit Output Tax Liabilities
                if (calculation.isInterstate) {
                    if (calculation.igstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = igstLedgerId,
                                debitAmount = 0.0,
                                creditAmount = calculation.igstAmount
                            )
                        )
                    }
                } else {
                    if (calculation.cgstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = cgstLedgerId,
                                debitAmount = 0.0,
                                creditAmount = calculation.cgstAmount
                            )
                        )
                    }
                    if (calculation.sgstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = sgstLedgerId,
                                debitAmount = 0.0,
                                creditAmount = calculation.sgstAmount
                            )
                        )
                    }
                }
            }

            VoucherType.PURCHASE -> {
                // Debit Purchase Expense Ledger for taxable base
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = baseSalesOrPurchaseLedgerId,
                        debitAmount = calculation.taxableValue,
                        creditAmount = 0.0
                    )
                )

                // Debit Input Tax Credit (ITC Asset) Ledgers
                if (calculation.isInterstate) {
                    if (calculation.igstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = igstLedgerId,
                                debitAmount = calculation.igstAmount,
                                creditAmount = 0.0
                            )
                        )
                    }
                } else {
                    if (calculation.cgstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = cgstLedgerId,
                                debitAmount = calculation.cgstAmount,
                                creditAmount = 0.0
                            )
                        )
                    }
                    if (calculation.sgstAmount > 0) {
                        entries.add(
                            JournalEntryEntity(
                                voucherId = voucherId,
                                ledgerId = sgstLedgerId,
                                debitAmount = calculation.sgstAmount,
                                creditAmount = 0.0
                            )
                        )
                    }
                }

                // Credit Vendor (Liability / Payable) for full invoice amount
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = partyLedgerId,
                        debitAmount = 0.0,
                        creditAmount = calculation.grossAmount
                    )
                )
            }

            else -> {
                // Default simple debit / credit transfer for Payment / Receipt / Journal
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = partyLedgerId,
                        debitAmount = calculation.grossAmount,
                        creditAmount = 0.0
                    )
                )
                entries.add(
                    JournalEntryEntity(
                        voucherId = voucherId,
                        ledgerId = baseSalesOrPurchaseLedgerId,
                        debitAmount = 0.0,
                        creditAmount = calculation.grossAmount
                    )
                )
            }
        }

        return entries
    }
}
