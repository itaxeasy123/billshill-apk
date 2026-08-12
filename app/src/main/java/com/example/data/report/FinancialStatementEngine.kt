package com.example.data.report

import com.example.data.dao.LedgerWithBalance
import com.example.data.model.LedgerCategory
import kotlin.math.abs

/** A single ledger line inside a statement group. */
data class StatementLine(
    val ledgerId: Long,
    val label: String,
    val amount: Double,
    /** True when this ledger's group was not recognised and the category fall-through was used. */
    val ungrouped: Boolean = false
)

/** A Tally primary group with its member ledgers. */
data class StatementGroup(
    val group: TallyGroup,
    val label: String,
    val total: Double,
    val lines: List<StatementLine>
)

data class BalanceSheet(
    val liabilities: List<StatementGroup>,
    val assets: List<StatementGroup>,
    val closingStock: Double,
    val pnlOpening: Double,
    val pnlCurrent: Double,
    val openingDifference: Double,
    val journalImbalance: Double,
    val liabilitiesTotal: Double,
    val assetsTotal: Double,
    val ungroupedLedgerCount: Int,
    val hasAnyData: Boolean
) {
    /** True when the two faces agree once the difference line is included. */
    val balances: Boolean get() = abs(assetsTotal - liabilitiesTotal) < 0.01
}

data class ProfitAndLoss(
    val tradingShown: Boolean,
    val tradingDebit: List<StatementGroup>,
    val tradingCredit: List<StatementGroup>,
    val openingStock: Double,
    val closingStock: Double,
    val grossProfit: Double,
    val pnlDebit: List<StatementGroup>,
    val pnlCredit: List<StatementGroup>,
    val nettProfit: Double,
    val hasAnyData: Boolean
)

/**
 * Builds Tally-shaped statements from real ledger balances.
 *
 * Pure Kotlin with no Android or Compose dependency, so the balancing invariant can be
 * tested on the JVM. The statements it produces replace 16 hardcoded literals that
 * belonged to a different real business.
 *
 * The accounting identity it relies on: every voucher posts balanced journal entries, so
 * summed over all ledgers `Σ(debit − credit) = 0`. Partitioned by category that gives
 * `Assets = Liabilities + Equity + (Revenue − Expense)`, i.e. the sheet balances by
 * construction — no plug, and none needed. Where it can genuinely fail is opening
 * balances (written onto the ledger row with no contra posting) and a corrupt journal;
 * both are surfaced as explicit lines rather than absorbed.
 */
object FinancialStatementEngine {

    private const val EPSILON = 0.005

    /**
     * @param inventoryEnabled when true, Stock-in-Hand ledgers are replaced on the face
     *   of the sheet by [closingStockValue]. Showing both double-counts stock and breaks
     *   the identity by exactly the opening stock figure — Tally's periodic model keeps
     *   stock out of the ledger and lets the Trading A/c carry it.
     */
    fun balanceSheet(
        balances: List<LedgerWithBalance>,
        priorProfit: Double,
        nominalOpeningBalance: Double,
        openingDifference: Double,
        journalImbalance: Double,
        closingStockValue: Double,
        /** Stock value at the START of the period, i.e. the P&L's opening stock. */
        openingStockValue: Double,
        currentPeriodProfit: Double,
        inventoryEnabled: Boolean
    ): BalanceSheet {
        val classified = balances.map { it to TallyGroupMapper.classify(it.groupName, it.category) }

        val realSideOnly = classified.filter { (_, c) ->
            c.first.side == StatementSide.LIABILITIES || c.first.side == StatementSide.ASSETS
        }

        // Stock-in-Hand ledgers are dropped when inventory is on; the derived closing
        // stock stands in for them.
        val visible = realSideOnly.filter { (_, c) ->
            !(inventoryEnabled && c.first == TallyGroup.STOCK_IN_HAND)
        }

        val liabilities = buildSide(visible, StatementSide.LIABILITIES) { it.totalCredit - it.totalDebit }
        val assets = buildSide(visible, StatementSide.ASSETS) { it.totalDebit - it.totalCredit }

        // The stock adjustment belonging to everything BEFORE this period.
        //
        // The Stock-in-Hand LEDGER is written once by the opening-stock posting and never
        // moves again — sales and purchases post to Sales A/c and Purchase A/c, never to
        // stock. The DERIVED stock value moves with every transaction. So for any period
        // that does not start at the book's inception, the two diverge and the sheet was
        // out by exactly the stock movement that happened before the period began: viewing
        // August alone, after a year with Rs 350 of net stock movement, reported the two
        // faces Rs 350 apart and tripped the imbalance banner on an honest book.
        //
        // Carrying (stock at period start − stock ledger) into retained earnings closes
        // it, because that movement IS prior-period profit that never reached the ledger.
        val stockLedgerBalance = classified
            .filter { (_, c) -> c.first == TallyGroup.STOCK_IN_HAND }
            .sumOf { (row, _) -> row.totalDebit - row.totalCredit }
        val priorStockAdjustment =
            if (inventoryEnabled) openingStockValue - stockLedgerBalance else 0.0

        val pnlOpening = priorProfit + nominalOpeningBalance + priorStockAdjustment

        var liabilitiesTotal = liabilities.sumOf { it.total } + pnlOpening + currentPeriodProfit
        var assetsTotal = assets.sumOf { it.total }
        if (inventoryEnabled) assetsTotal += closingStockValue

        // Tally's own treatment: name the discrepancy on the face of the statement
        // rather than silently plugging it into another figure.
        if (openingDifference > EPSILON) liabilitiesTotal += openingDifference
        else if (openingDifference < -EPSILON) assetsTotal += -openingDifference

        return BalanceSheet(
            liabilities = liabilities,
            assets = assets,
            closingStock = if (inventoryEnabled) closingStockValue else 0.0,
            pnlOpening = pnlOpening,
            pnlCurrent = currentPeriodProfit,
            openingDifference = openingDifference,
            journalImbalance = journalImbalance,
            liabilitiesTotal = liabilitiesTotal,
            assetsTotal = assetsTotal,
            ungroupedLedgerCount = realSideOnly.count { (_, c) -> !c.second },
            hasAnyData = balances.isNotEmpty()
        )
    }

    fun profitAndLoss(
        movement: List<LedgerWithBalance>,
        openingStockValue: Double,
        closingStockValue: Double,
        inventoryEnabled: Boolean
    ): ProfitAndLoss {
        val classified = movement.map { it to TallyGroupMapper.classify(it.groupName, it.category) }

        fun groupsFor(side: StatementSide, tradingOnly: Boolean?): List<StatementGroup> =
            buildSide(
                classified.filter { (_, c) ->
                    tradingOnly == null || c.first.isTradingAccount == tradingOnly
                },
                side
            ) { row ->
                if (side == StatementSide.PNL_CREDIT) row.totalCredit - row.totalDebit
                else row.totalDebit - row.totalCredit
            }

        return if (inventoryEnabled) {
            val tradingDebit = groupsFor(StatementSide.PNL_DEBIT, tradingOnly = true)
            val tradingCredit = groupsFor(StatementSide.PNL_CREDIT, tradingOnly = true)
            val pnlDebit = groupsFor(StatementSide.PNL_DEBIT, tradingOnly = false)
            val pnlCredit = groupsFor(StatementSide.PNL_CREDIT, tradingOnly = false)

            // Opening Stock is a debit and Closing Stock a credit, so the stock movement
            // folds into profit and both sides close.
            val grossProfit = (tradingCredit.sumOf { it.total } + closingStockValue) -
                (tradingDebit.sumOf { it.total } + openingStockValue)
            val nettProfit = grossProfit + pnlCredit.sumOf { it.total } - pnlDebit.sumOf { it.total }

            ProfitAndLoss(
                tradingShown = true,
                tradingDebit = tradingDebit,
                tradingCredit = tradingCredit,
                openingStock = openingStockValue,
                closingStock = closingStockValue,
                grossProfit = grossProfit,
                pnlDebit = pnlDebit,
                pnlCredit = pnlCredit,
                nettProfit = nettProfit,
                hasAnyData = movement.isNotEmpty()
            )
        } else {
            // A service business has no Trading Account at all: no opening or closing
            // stock, no gross profit line. Everything sits in one P&L section.
            val pnlDebit = groupsFor(StatementSide.PNL_DEBIT, tradingOnly = null)
            val pnlCredit = groupsFor(StatementSide.PNL_CREDIT, tradingOnly = null)
            val nettProfit = pnlCredit.sumOf { it.total } - pnlDebit.sumOf { it.total }

            ProfitAndLoss(
                tradingShown = false,
                tradingDebit = emptyList(),
                tradingCredit = emptyList(),
                openingStock = 0.0,
                closingStock = 0.0,
                grossProfit = 0.0,
                pnlDebit = pnlDebit,
                pnlCredit = pnlCredit,
                nettProfit = nettProfit,
                hasAnyData = movement.isNotEmpty()
            )
        }
    }

    /** Groups classified rows onto one side, dropping anything that rounds to zero. */
    private fun buildSide(
        classified: List<Pair<LedgerWithBalance, Pair<TallyGroup, Boolean>>>,
        side: StatementSide,
        amountOf: (LedgerWithBalance) -> Double
    ): List<StatementGroup> =
        classified
            .filter { (_, c) -> c.first.side == side }
            .groupBy { (_, c) -> c.first }
            .map { (group, rows) ->
                val lines = rows
                    .map { (row, c) ->
                        StatementLine(
                            ledgerId = row.id,
                            label = row.name,
                            amount = amountOf(row),
                            ungrouped = !c.second
                        )
                    }
                    .filter { abs(it.amount) >= EPSILON }
                    .sortedBy { it.label.lowercase() }
                StatementGroup(group, group.displayName, lines.sumOf { it.amount }, lines)
            }
            .filter { it.lines.isNotEmpty() && abs(it.total) >= EPSILON }
            .sortedBy { it.group.order }
}
