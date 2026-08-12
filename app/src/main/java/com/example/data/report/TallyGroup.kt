package com.example.data.report

import com.example.data.model.LedgerCategory

/** Which face of which statement a group belongs on. */
enum class StatementSide { LIABILITIES, ASSETS, PNL_DEBIT, PNL_CREDIT }

/**
 * Tally's primary groups, with the exact spellings used in the Tally Excel exports the
 * statements have to match.
 *
 * The spelling is load-bearing and is not to be "tidied": "Capital Account" (not
 * "Capital Liabilities"), "Suspense A/c" (not "Suspense Account"), "Misc. Expenses
 * (ASSET)" with the parenthesised ASSET, "Bank OD A/c", and the plural "Direct Incomes"
 * / "Indirect Incomes". Capital Account belongs on the Liabilities side — owner's
 * capital is money owed back to the owners.
 */
enum class TallyGroup(val displayName: String, val side: StatementSide, val order: Int) {
    // Liabilities, in Tally's own ordering
    CAPITAL_ACCOUNT("Capital Account", StatementSide.LIABILITIES, 10),
    LOANS_LIABILITY("Loans (Liability)", StatementSide.LIABILITIES, 20),
    CURRENT_LIABILITIES("Current Liabilities", StatementSide.LIABILITIES, 30),
    SUSPENSE_AC("Suspense A/c", StatementSide.LIABILITIES, 40),

    // Assets
    FIXED_ASSETS("Fixed Assets", StatementSide.ASSETS, 10),
    INVESTMENTS("Investments", StatementSide.ASSETS, 20),
    CURRENT_ASSETS("Current Assets", StatementSide.ASSETS, 30),
    STOCK_IN_HAND("Stock-in-Hand", StatementSide.ASSETS, 40),
    MISC_EXPENSES_ASSET("Misc. Expenses (ASSET)", StatementSide.ASSETS, 50),
    BRANCH_DIVISIONS("Branch / Divisions", StatementSide.ASSETS, 60),

    // Trading and P&L
    PURCHASE_ACCOUNTS("Purchase Accounts", StatementSide.PNL_DEBIT, 10),
    DIRECT_EXPENSES("Direct Expenses", StatementSide.PNL_DEBIT, 20),
    INDIRECT_EXPENSES("Indirect Expenses", StatementSide.PNL_DEBIT, 30),
    SALES_ACCOUNTS("Sales Accounts", StatementSide.PNL_CREDIT, 10),
    DIRECT_INCOMES("Direct Incomes", StatementSide.PNL_CREDIT, 20),
    INDIRECT_INCOMES("Indirect Incomes", StatementSide.PNL_CREDIT, 30);

    /** Trading Account groups — hidden entirely when inventory is off. */
    val isTradingAccount: Boolean
        get() = this == PURCHASE_ACCOUNTS || this == DIRECT_EXPENSES ||
            this == SALES_ACCOUNTS || this == DIRECT_INCOMES
}

/**
 * Maps a ledger's group to a Tally primary group.
 *
 * Group names are an open namespace — the Ledger modal accepts free text and
 * `getLedgerByNameOrCreate` will create any name it is handed — so no lookup table can
 * ever be complete. The category fall-through is therefore mandatory, not a nicety: it
 * is what guarantees every ledger lands somewhere, which is what keeps the Balance Sheet
 * balancing. A ledger that vanished from the statement would silently break the
 * accounting identity.
 */
object TallyGroupMapper {

    /**
     * Collapses spelling variants to a comparison key. This is what makes
     * "Cash-in-Hand", "Cash-in-hand" and "Cash in Hand" — all three of which exist in
     * this database — resolve to the same group on the statement, even though they
     * remain three distinct ledger rows.
     */
    fun normalise(name: String): String =
        name.lowercase()
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")

    private val byName: Map<String, TallyGroup> = buildMap {
        fun put(group: TallyGroup, vararg names: String) =
            names.forEach { put(normalise(it), group) }

        put(TallyGroup.CAPITAL_ACCOUNT,
            "Capital Account", "Capital", "Capital Accounts", "Equity",
            "Reserves & Surplus", "Reserves and Surplus", "Retained Earnings")
        put(TallyGroup.LOANS_LIABILITY,
            "Loans (Liability)", "Loans", "Loan", "Secured Loans", "Unsecured Loans",
            "Bank OD A/c", "Bank OCC A/c", "Bank Overdraft")
        put(TallyGroup.CURRENT_LIABILITIES,
            "Current Liabilities", "Liabilities", "Sundry Creditors", "Creditors",
            "Duties & Taxes", "Duties and Taxes", "Provisions", "Outstanding Expenses")
        put(TallyGroup.SUSPENSE_AC, "Suspense A/c", "Suspense Account", "Suspense")

        put(TallyGroup.FIXED_ASSETS, "Fixed Assets", "Fixed Asset")
        put(TallyGroup.INVESTMENTS, "Investments", "Investment")
        put(TallyGroup.CURRENT_ASSETS,
            "Current Assets", "Assets", "Bank Accounts", "Bank Account",
            "Cash-in-Hand", "Cash in Hand", "Cash", "Cash/Bank Accounts",
            "Sundry Debtors", "Debtors", "Deposits (Asset)", "Deposits",
            "Loans & Advances (Asset)", "Loans and Advances (Asset)", "Loans Advance")
        put(TallyGroup.STOCK_IN_HAND, "Stock-in-Hand", "Stock in Hand", "Closing Stock", "Inventory")
        put(TallyGroup.MISC_EXPENSES_ASSET, "Misc. Expenses (ASSET)", "Misc Expenses (ASSET)")
        put(TallyGroup.BRANCH_DIVISIONS, "Branch / Divisions", "Branch Divisions", "Branches")

        put(TallyGroup.PURCHASE_ACCOUNTS, "Purchase Accounts", "Purchase Account", "Purchases", "Purchase")
        put(TallyGroup.DIRECT_EXPENSES, "Direct Expenses", "Direct Expense")
        put(TallyGroup.INDIRECT_EXPENSES, "Indirect Expenses", "Indirect Expense", "Expenses", "Expense")
        put(TallyGroup.SALES_ACCOUNTS, "Sales Accounts", "Sales Account", "Sales", "Revenue")
        put(TallyGroup.DIRECT_INCOMES, "Direct Incomes", "Direct Income")
        put(TallyGroup.INDIRECT_INCOMES, "Indirect Incomes", "Indirect Income", "Other Income")
    }

    /**
     * Resolves a group. [matchedByName] is false when the fall-through was used, which
     * the UI surfaces so the user can go and classify those ledgers properly rather than
     * silently accepting a guess.
     */
    fun classify(groupName: String, category: LedgerCategory): Pair<TallyGroup, Boolean> {
        byName[normalise(groupName)]?.let { return it to true }
        val fallback = when (category) {
            LedgerCategory.ASSET -> TallyGroup.CURRENT_ASSETS
            LedgerCategory.LIABILITY -> TallyGroup.CURRENT_LIABILITIES
            LedgerCategory.EQUITY -> TallyGroup.CAPITAL_ACCOUNT
            LedgerCategory.REVENUE -> TallyGroup.INDIRECT_INCOMES
            LedgerCategory.EXPENSE -> TallyGroup.INDIRECT_EXPENSES
        }
        return fallback to false
    }
}
