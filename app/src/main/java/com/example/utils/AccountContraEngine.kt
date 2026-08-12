package com.example.utils

import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType

data class ContraSuggestion(
    val accountName: String,
    val probabilityScore: Int, // e.g. 95%
    val recommendedType: VoucherType,
    val explanation: String
)

object AccountContraEngine {

    /**
     * Analyzes historical entry patterns from past vouchers to predict
     * the most probable contra-account and voucher type for a typed account name.
     */
    fun predictContraAccount(
        typedQuery: String,
        historicalVouchers: List<VoucherEntity>
    ): List<ContraSuggestion> {
        if (typedQuery.isBlank()) {
            return listOf(
                ContraSuggestion("Sales Account", 98, VoucherType.SALES, "Default for customer transactions"),
                ContraSuggestion("Purchase Account", 95, VoucherType.PURCHASE, "Default for vendor purchases"),
                ContraSuggestion("Cash Account", 92, VoucherType.PAYMENT, "Frequent cash petty expense")
            )
        }

        val queryLower = typedQuery.trim().lowercase()
        val suggestions = mutableListOf<ContraSuggestion>()

        // 1. Exact or partial party matches from historical DB
        val matchingHistorical = historicalVouchers.filter {
            it.partyName.lowercase().contains(queryLower)
        }

        if (matchingHistorical.isNotEmpty()) {
            val total = matchingHistorical.size
            val typeCounts = matchingHistorical.groupingBy { it.voucherType }.eachCount()
            val primaryType = typeCounts.maxByOrNull { it.value }?.key ?: VoucherType.SALES
            val confidence = ((typeCounts[primaryType] ?: 0).toDouble() / total * 100).toInt().coerceIn(70, 99)

            val primaryContra = when (primaryType) {
                VoucherType.SALES -> "Sales Account (Sundry Debtors)"
                VoucherType.PURCHASE -> "Purchase Account (Sundry Creditors)"
                VoucherType.PAYMENT -> "General Expenses / Cash"
                VoucherType.RECEIPT -> "Bank / Cash Receipt"
                VoucherType.CONTRA -> "Cash / Bank Transfer"
                else -> "General Ledger"
            }

            suggestions.add(
                ContraSuggestion(
                    accountName = primaryContra,
                    probabilityScore = confidence,
                    recommendedType = primaryType,
                    explanation = "Matched $total past entry patterns for '$typedQuery'"
                )
            )
        }

        // 2. Keyword/Semantic heuristics for new/unseen parties
        when {
            queryLower.contains("trader") || queryLower.contains("store") || queryLower.contains("enterprise") || queryLower.contains("customer") -> {
                if (suggestions.none { it.recommendedType == VoucherType.SALES }) {
                    suggestions.add(ContraSuggestion("Sales Account (Sundry Debtors)", 88, VoucherType.SALES, "Pattern: Commercial customer ledger"))
                }
            }
            queryLower.contains("corp") || queryLower.contains("pvts") || queryLower.contains("supplier") || queryLower.contains("pvt") || queryLower.contains("ltd") -> {
                if (suggestions.none { it.recommendedType == VoucherType.PURCHASE }) {
                    suggestions.add(ContraSuggestion("Purchase Account (Sundry Creditors)", 85, VoucherType.PURCHASE, "Pattern: Corporate vendor/supplier"))
                }
            }
            queryLower.contains("tea") || queryLower.contains("coffee") || queryLower.contains("rent") || queryLower.contains("taxi") || queryLower.contains("salary") || queryLower.contains("office") -> {
                if (suggestions.none { it.recommendedType == VoucherType.PAYMENT }) {
                    suggestions.add(ContraSuggestion("Office & Administrative Expenses", 94, VoucherType.PAYMENT, "Pattern: Operational expense item"))
                }
            }
            queryLower.contains("bank") || queryLower.contains("hdfc") || queryLower.contains("icici") || queryLower.contains("sbi") -> {
                if (suggestions.none { it.recommendedType == VoucherType.CONTRA }) {
                    suggestions.add(ContraSuggestion("Bank Deposit / Transfer Account", 96, VoucherType.CONTRA, "Pattern: Banking institution transaction"))
                }
            }
        }

        // Fallbacks if list is still short
        if (suggestions.isEmpty()) {
            suggestions.add(ContraSuggestion("$typedQuery Ledger", 75, VoucherType.SALES, "Standard Customer Sales"))
            suggestions.add(ContraSuggestion("Cash / Direct Expense", 70, VoucherType.PAYMENT, "Standard Expense Payment"))
        }

        return suggestions.sortedByDescending { it.probabilityScore }
    }
}
