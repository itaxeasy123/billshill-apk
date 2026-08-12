package com.example.utils

import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType

data class ContraSuggestion(
    val accountName: String,
    /**
     * Share of the user's own past vouchers for this party that used [recommendedType],
     * as a percentage. Null when the suggestion comes from a keyword rule rather than
     * from history.
     *
     * This field used to be a non-null `probabilityScore` that every suggestion carried,
     * and the UI rendered it as "ML Predicted ... (96%)". Nine of those numbers were
     * hardcoded literals (98, 95, 92, 88, 85, 94, 96, 75, 70) chosen to look confident.
     * There is no model in this app -- only a count over past vouchers and a handful of
     * substring rules -- so only the counted figure is reported now, and the rest report
     * nothing at all.
     */
    val matchConfidencePercent: Int?,
    val recommendedType: VoucherType,
    val explanation: String
)

object AccountContraEngine {

    /**
     * Suggests a probable contra-account and voucher type for a typed account name, from
     * the user's own voucher history where there is any, and from simple keyword rules
     * otherwise. Only history-derived suggestions carry a confidence figure.
     */
    fun predictContraAccount(
        typedQuery: String,
        historicalVouchers: List<VoucherEntity>
    ): List<ContraSuggestion> {
        if (typedQuery.isBlank()) {
            return listOf(
                ContraSuggestion("Sales Account", null, VoucherType.SALES, "Common for customer transactions"),
                ContraSuggestion("Purchase Account", null, VoucherType.PURCHASE, "Common for vendor purchases"),
                ContraSuggestion("Cash Account", null, VoucherType.PAYMENT, "Common for cash petty expenses")
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
            // Genuinely computed: the proportion of this party's past vouchers that used
            // primaryType. Reported as-is, without the previous coerceIn(70, 99) floor
            // that inflated a weak match into a confident-looking one.
            val confidence = ((typeCounts[primaryType] ?: 0).toDouble() / total * 100).toInt()

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
                    matchConfidencePercent = confidence,
                    recommendedType = primaryType,
                    explanation = "Matched $total past entry patterns for '$typedQuery'"
                )
            )
        }

        // 2. Keyword heuristics for new/unseen parties. These are substring rules, not
        //    predictions, so they carry no confidence figure.
        when {
            queryLower.contains("trader") || queryLower.contains("store") || queryLower.contains("enterprise") || queryLower.contains("customer") -> {
                if (suggestions.none { it.recommendedType == VoucherType.SALES }) {
                    suggestions.add(ContraSuggestion("Sales Account (Sundry Debtors)", null, VoucherType.SALES, "Name looks like a commercial customer"))
                }
            }
            queryLower.contains("corp") || queryLower.contains("pvts") || queryLower.contains("supplier") || queryLower.contains("pvt") || queryLower.contains("ltd") -> {
                if (suggestions.none { it.recommendedType == VoucherType.PURCHASE }) {
                    suggestions.add(ContraSuggestion("Purchase Account (Sundry Creditors)", null, VoucherType.PURCHASE, "Name looks like a corporate vendor/supplier"))
                }
            }
            queryLower.contains("tea") || queryLower.contains("coffee") || queryLower.contains("rent") || queryLower.contains("taxi") || queryLower.contains("salary") || queryLower.contains("office") -> {
                if (suggestions.none { it.recommendedType == VoucherType.PAYMENT }) {
                    suggestions.add(ContraSuggestion("Office & Administrative Expenses", null, VoucherType.PAYMENT, "Name looks like an operational expense"))
                }
            }
            queryLower.contains("bank") || queryLower.contains("hdfc") || queryLower.contains("icici") || queryLower.contains("sbi") -> {
                if (suggestions.none { it.recommendedType == VoucherType.CONTRA }) {
                    suggestions.add(ContraSuggestion("Bank Deposit / Transfer Account", null, VoucherType.CONTRA, "Name looks like a banking institution"))
                }
            }
        }

        // Fallbacks if list is still short
        if (suggestions.isEmpty()) {
            suggestions.add(ContraSuggestion("$typedQuery Ledger", null, VoucherType.SALES, "Customer sales entry"))
            suggestions.add(ContraSuggestion("Cash / Direct Expense", null, VoucherType.PAYMENT, "Expense payment entry"))
        }

        // History-derived suggestions rank above keyword ones; unscored entries keep their
        // insertion order rather than being ranked by an invented number.
        return suggestions.sortedByDescending { it.matchConfidencePercent ?: -1 }
    }
}
