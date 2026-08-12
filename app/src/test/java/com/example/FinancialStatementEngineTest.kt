package com.example

import com.example.data.dao.LedgerWithBalance
import com.example.data.model.LedgerCategory
import com.example.data.report.FinancialStatementEngine
import com.example.data.report.StatementSide
import com.example.data.report.TallyGroup
import com.example.data.report.TallyGroupMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for C1 — the Balance Sheet and P&L that were 16 hardcoded literals belonging to
 * a different real business.
 *
 * The balancing invariant is the important one: everything else is presentation, but if
 * the two faces don't agree the statement is worthless.
 */
class FinancialStatementEngineTest {

    private val tol = 0.01
    private var nextId = 1L

    private fun ledger(
        name: String,
        group: String,
        category: LedgerCategory,
        debit: Double = 0.0,
        credit: Double = 0.0
    ) = LedgerWithBalance(
        id = nextId++,
        name = name,
        groupName = group,
        category = category,
        totalDebit = debit,
        totalCredit = credit,
        currentBalance = debit - credit
    )

    /**
     * A complete, balanced book: capital introduced, stock bought, goods sold at a
     * margin, GST collected. Debits equal credits overall, as every posting path in the
     * app guarantees.
     */
    /**
     * Built from five real transactions so the postings genuinely balance:
     *   1. Capital introduced in cash          Dr Cash 300,000 / Cr Capital 300,000
     *   2. Goods bought for cash               Dr Purchase 60,000 / Cr Cash 60,000
     *   3. Credit sale 100,000 + 18% GST       Dr Anand 118,000 / Cr Sales 100,000
     *                                          / Cr CGST 9,000 / Cr SGST 9,000
     *   4. Customer pays into the bank         Dr Bank 118,000 / Cr Anand 118,000
     *   5. Rent paid in cash                   Dr Rent 18,000 / Cr Cash 18,000
     * Debits 614,000 = credits 614,000.
     */
    private fun balancedBook() = listOf(
        // Assets
        ledger("Cash in Hand", "Cash-in-Hand", LedgerCategory.ASSET, debit = 300_000.0, credit = 78_000.0),
        ledger("Bank Account", "Bank Accounts", LedgerCategory.ASSET, debit = 118_000.0),
        ledger("Anand Traders", "Sundry Debtors", LedgerCategory.ASSET, debit = 118_000.0, credit = 118_000.0),
        // Liabilities & equity
        ledger("Capital Account", "Equity", LedgerCategory.EQUITY, credit = 300_000.0),
        ledger("CGST", "Duties & Taxes", LedgerCategory.LIABILITY, credit = 9_000.0),
        ledger("SGST", "Duties & Taxes", LedgerCategory.LIABILITY, credit = 9_000.0),
        // Nominal
        ledger("Sales Account", "Sales Accounts", LedgerCategory.REVENUE, credit = 100_000.0),
        ledger("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE, debit = 60_000.0),
        ledger("Shop Rent", "Indirect Expenses", LedgerCategory.EXPENSE, debit = 18_000.0)
    )

    /** Guards the fixture itself — a book that doesn't balance proves nothing. */
    @Test
    fun `the test fixture is itself a balanced set of books`() {
        val book = balancedBook()
        assertEquals(book.sumOf { it.totalDebit }, book.sumOf { it.totalCredit }, tol)
    }

    private fun nominal(book: List<LedgerWithBalance>) =
        book.filter { it.category == LedgerCategory.REVENUE || it.category == LedgerCategory.EXPENSE }

    // ---------- the invariant ----------

    @Test
    fun `balance sheet balances for a service business`() {
        val book = balancedBook()
        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 0.0, inventoryEnabled = false)
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = 0.0,
            openingStockValue = 0.0,
            currentPeriodProfit = pl.nettProfit, inventoryEnabled = false
        )

        assertEquals("assets must equal liabilities", bs.liabilitiesTotal, bs.assetsTotal, tol)
        assertTrue(bs.balances)
    }

    @Test
    fun `balance sheet balances for a trading business with stock`() {
        val book = balancedBook()
        val closingStock = 25_000.0
        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, closingStock, inventoryEnabled = true)
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = closingStock,
            openingStockValue = 0.0, currentPeriodProfit = pl.nettProfit, inventoryEnabled = true
        )

        // Closing stock lands on both faces — asset on one, profit via the P&L on the other.
        assertEquals(bs.liabilitiesTotal, bs.assetsTotal, tol)
    }

    @Test
    fun `an opening balance difference is named, not absorbed`() {
        // A ledger given a Rs 50,000 opening debit with no contra posting — exactly what
        // happens today, since createLedger writes openingBalance onto the row and posts
        // nothing. The books are now genuinely out by 50,000 on the debit side.
        val difference = 50_000.0
        val book = balancedBook() +
            ledger("Opening Receivable", "Sundry Debtors", LedgerCategory.ASSET, debit = difference)

        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 0.0, inventoryEnabled = false)
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = difference, journalImbalance = 0.0, closingStockValue = 0.0,
            openingStockValue = 0.0,
            currentPeriodProfit = pl.nettProfit, inventoryEnabled = false
        )

        assertEquals(difference, bs.openingDifference, tol)
        // Opening debits exceed credits, so the credit face is short by that much. Tally
        // names it as "Difference in Opening Balances" on the Liabilities side rather
        // than quietly folding it into another figure.
        assertEquals(bs.liabilitiesTotal, bs.assetsTotal, tol)
    }

    @Test
    fun `an opening difference the other way lands on the assets face`() {
        val difference = -30_000.0   // opening credits exceed debits
        val book = balancedBook() +
            ledger("Opening Payable", "Sundry Creditors", LedgerCategory.LIABILITY, credit = 30_000.0)

        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 0.0, inventoryEnabled = false)
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = difference, journalImbalance = 0.0, closingStockValue = 0.0,
            openingStockValue = 0.0,
            currentPeriodProfit = pl.nettProfit, inventoryEnabled = false
        )
        assertEquals(bs.liabilitiesTotal, bs.assetsTotal, tol)
    }

    @Test
    fun `net profit is the same number on both statements`() {
        val book = balancedBook()
        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 25_000.0, inventoryEnabled = true)
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = 25_000.0,
            openingStockValue = 0.0,
            currentPeriodProfit = pl.nettProfit, inventoryEnabled = true
        )
        assertEquals(pl.nettProfit, bs.pnlCurrent, tol)
    }

    // ---------- Trading Account gating ----------

    @Test
    fun `service business has no trading account`() {
        val pl = FinancialStatementEngine.profitAndLoss(
            nominal(balancedBook()), openingStockValue = 0.0, closingStockValue = 0.0, inventoryEnabled = false
        )
        assertFalse(pl.tradingShown)
        assertTrue(pl.tradingDebit.isEmpty())
        assertTrue(pl.tradingCredit.isEmpty())
        assertEquals(0.0, pl.closingStock, tol)
        // Sales and Purchases still appear — in the P&L section, not a Trading Account.
        val all = (pl.pnlCredit + pl.pnlDebit).map { it.group }
        assertTrue(TallyGroup.SALES_ACCOUNTS in all)
        assertTrue(TallyGroup.PURCHASE_ACCOUNTS in all)
    }

    @Test
    fun `trading business shows gross profit`() {
        val pl = FinancialStatementEngine.profitAndLoss(
            nominal(balancedBook()), openingStockValue = 10_000.0, closingStockValue = 25_000.0, inventoryEnabled = true
        )
        assertTrue(pl.tradingShown)
        // (Sales 100,000 + closing 25,000) - (purchases 60,000 + opening 10,000)
        assertEquals(55_000.0, pl.grossProfit, tol)
        // gross 55,000 - indirect 18,000
        assertEquals(37_000.0, pl.nettProfit, tol)
    }

    @Test
    fun `stock ledgers are excluded when inventory is on to avoid double counting`() {
        val book = balancedBook() +
            ledger("Closing Stock", "Stock-in-Hand", LedgerCategory.ASSET, debit = 25_000.0)

        val withInventory = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = 25_000.0,
            openingStockValue = 0.0,
            currentPeriodProfit = 0.0, inventoryEnabled = true
        )
        assertTrue(withInventory.assets.none { it.group == TallyGroup.STOCK_IN_HAND })

        val withoutInventory = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = 0.0,
            openingStockValue = 0.0,
            currentPeriodProfit = 0.0, inventoryEnabled = false
        )
        assertTrue(withoutInventory.assets.any { it.group == TallyGroup.STOCK_IN_HAND })
    }

    // ---------- empty states ----------

    @Test
    fun `an empty book reports no data rather than a grid of zeros`() {
        val bs = FinancialStatementEngine.balanceSheet(
            emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, inventoryEnabled = true
        )
        assertFalse(bs.hasAnyData)
        assertTrue(bs.liabilities.isEmpty())
        assertTrue(bs.assets.isEmpty())

        val pl = FinancialStatementEngine.profitAndLoss(emptyList(), 0.0, 0.0, inventoryEnabled = true)
        assertFalse(pl.hasAnyData)
        assertEquals(0.0, pl.nettProfit, tol)
    }

    @Test
    fun `zero-balance ledgers are omitted, not rendered as empty rows`() {
        val book = listOf(
            ledger("Cash in Hand", "Cash-in-Hand", LedgerCategory.ASSET, debit = 1000.0),
            ledger("Dormant Ledger", "Cash-in-Hand", LedgerCategory.ASSET),
            ledger("Capital Account", "Equity", LedgerCategory.EQUITY, credit = 1000.0)
        )
        val bs = FinancialStatementEngine.balanceSheet(
            book, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, inventoryEnabled = false
        )
        val names = bs.assets.flatMap { it.lines }.map { it.label }
        assertTrue("Cash in Hand" in names)
        assertFalse("Dormant Ledger" in names)
    }

    // ---------- group mapping ----------

    @Test
    fun `the three cash spellings in this database collapse to one group`() {
        val keys = listOf("Cash-in-Hand", "Cash-in-hand", "Cash in Hand").map { TallyGroupMapper.normalise(it) }
        assertEquals(1, keys.toSet().size)
    }

    @Test
    fun `unknown group names fall through by category and are counted`() {
        listOf(
            LedgerCategory.ASSET to TallyGroup.CURRENT_ASSETS,
            LedgerCategory.LIABILITY to TallyGroup.CURRENT_LIABILITIES,
            LedgerCategory.EQUITY to TallyGroup.CAPITAL_ACCOUNT,
            LedgerCategory.REVENUE to TallyGroup.INDIRECT_INCOMES,
            LedgerCategory.EXPENSE to TallyGroup.INDIRECT_EXPENSES
        ).forEach { (category, expected) ->
            val (group, matched) = TallyGroupMapper.classify("Nonsense Group $category", category)
            assertEquals(expected, group)
            assertFalse("fall-through must be reported, not silent", matched)
        }
    }

    @Test
    fun `a ledger in an unrecognised group still reaches the statement`() {
        // This is what keeps the identity intact — nothing may silently vanish.
        val book = listOf(
            ledger("Weird Asset", "Totally Made Up Group", LedgerCategory.ASSET, debit = 5_000.0),
            ledger("Capital Account", "Equity", LedgerCategory.EQUITY, credit = 5_000.0)
        )
        val bs = FinancialStatementEngine.balanceSheet(
            book, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, inventoryEnabled = false
        )
        assertEquals(5_000.0, bs.assetsTotal, tol)
        assertEquals(bs.liabilitiesTotal, bs.assetsTotal, tol)
        assertEquals(1, bs.ungroupedLedgerCount)
    }

    @Test
    fun `the seeded chart of accounts maps entirely to real Tally groups`() {
        // Every group the seed creates should be recognised by name, not fall through.
        listOf(
            "Cash-in-Hand" to LedgerCategory.ASSET,
            "Bank Accounts" to LedgerCategory.ASSET,
            "Sundry Debtors" to LedgerCategory.ASSET,
            "Sundry Creditors" to LedgerCategory.LIABILITY,
            "Duties & Taxes" to LedgerCategory.LIABILITY,
            "Sales Accounts" to LedgerCategory.REVENUE,
            "Purchase Accounts" to LedgerCategory.EXPENSE,
            "Equity" to LedgerCategory.EQUITY
        ).forEach { (name, category) ->
            val (_, matched) = TallyGroupMapper.classify(name, category)
            assertTrue("seeded group '$name' should map by name", matched)
        }
    }

    @Test
    fun `input GST is an asset even though it sits in Duties and Taxes`() {
        // Output and input tax share Tally's "Duties & Taxes" group, which maps to
        // Current Liabilities. Unutilised input credit is a receivable, and reporting it
        // on the liabilities face would understate both sides of the sheet.
        val (outGroup, _) = TallyGroupMapper.classify("Duties & Taxes", LedgerCategory.LIABILITY)
        assertEquals(StatementSide.LIABILITIES, outGroup.side)

        val (inGroup, _) = TallyGroupMapper.classify("Duties & Taxes", LedgerCategory.ASSET)
        assertEquals(StatementSide.ASSETS, inGroup.side)
    }

    @Test
    fun `capital account sits on the liabilities side`() {
        assertEquals(StatementSide.LIABILITIES, TallyGroup.CAPITAL_ACCOUNT.side)
    }

    @Test
    fun `a loss is carried with its sign intact`() {
        val book = listOf(
            ledger("Cash in Hand", "Cash-in-Hand", LedgerCategory.ASSET, debit = 10_000.0, credit = 30_000.0),
            ledger("Capital Account", "Equity", LedgerCategory.EQUITY, credit = 10_000.0),
            ledger("Shop Rent", "Indirect Expenses", LedgerCategory.EXPENSE, debit = 30_000.0)
        )
        val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 0.0, inventoryEnabled = false)
        assertTrue("a real loss must stay negative", pl.nettProfit < 0)
        assertEquals(-30_000.0, pl.nettProfit, tol)

        val bs = FinancialStatementEngine.balanceSheet(
            book, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, pl.nettProfit, inventoryEnabled = false
        )
        assertEquals(bs.liabilitiesTotal, bs.assetsTotal, tol)
    }

    @Test
    fun `journal imbalance is reported rather than hidden`() {
        val bs = FinancialStatementEngine.balanceSheet(
            balancedBook(), 0.0, 0.0, 0.0, journalImbalance = 250.0,
            closingStockValue = 0.0, openingStockValue = 0.0, currentPeriodProfit = 0.0, inventoryEnabled = false
        )
        assertEquals(250.0, bs.journalImbalance, tol)
    }

    @Test
    fun `prior profit and nominal opening balances form the P and L opening line`() {
        val bs = FinancialStatementEngine.balanceSheet(
            balancedBook(), priorProfit = 40_000.0, nominalOpeningBalance = 15_000.0,
            openingDifference = 0.0, journalImbalance = 0.0, closingStockValue = 0.0,
            openingStockValue = 0.0,
            currentPeriodProfit = 0.0, inventoryEnabled = false
        )
        assertEquals(55_000.0, bs.pnlOpening, tol)
    }

    // ---------- stock: the invariant that actually matters ----------

    @Test
    fun `the sheet balances for a period that does not start at inception`() {
        // The Stock-in-Hand LEDGER is written once by the opening posting and never moves
        // again, while the DERIVED stock value moves with every transaction. Viewing a
        // single month after a year of trading therefore reported the two faces apart by
        // exactly the stock movement that preceded the period — on an honest book.
        //
        // Opening 100 @ Rs 50 posted (ledger 5,000); by 31 Jul stock is worth 4,650.
        val book = listOf(
            ledger("Anand Traders", "Sundry Debtors", LedgerCategory.ASSET, debit = 3_000.0),
            ledger("Sharma Traders", "Sundry Creditors", LedgerCategory.LIABILITY, credit = 1_200.0),
            ledger("Stock-in-Hand", "Stock-in-Hand", LedgerCategory.ASSET, debit = 5_000.0),
            ledger("Difference in Opening Balances", "Suspense A/c", LedgerCategory.LIABILITY, credit = 5_000.0),
            ledger("Sales Account", "Sales Accounts", LedgerCategory.REVENUE, credit = 3_000.0),
            ledger("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE, debit = 1_200.0)
        )
        // August-only view: nothing posted in the period, everything is prior.
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book,
            priorProfit = 1_800.0,          // 3,000 sales - 1,200 purchases, all before August
            nominalOpeningBalance = 0.0,
            openingDifference = 0.0,
            journalImbalance = 0.0,
            closingStockValue = 4_650.0,
            openingStockValue = 4_650.0,    // stock as at 31 July
            currentPeriodProfit = 0.0,
            inventoryEnabled = true
        )
        assertEquals(
            "a mid-year period must still balance",
            bs.liabilitiesTotal, bs.assetsTotal, tol
        )
        assertTrue(bs.balances)
    }

    @Test
    fun `the sheet balances for the full year the stock was opened in`() {
        val book = listOf(
            ledger("Anand Traders", "Sundry Debtors", LedgerCategory.ASSET, debit = 3_000.0),
            ledger("Sharma Traders", "Sundry Creditors", LedgerCategory.LIABILITY, credit = 1_200.0),
            ledger("Stock-in-Hand", "Stock-in-Hand", LedgerCategory.ASSET, debit = 5_000.0),
            ledger("Difference in Opening Balances", "Suspense A/c", LedgerCategory.LIABILITY, credit = 5_000.0),
            ledger("Sales Account", "Sales Accounts", LedgerCategory.REVENUE, credit = 3_000.0),
            ledger("Purchase Account", "Purchase Accounts", LedgerCategory.EXPENSE, debit = 1_200.0)
        )
        val pl = FinancialStatementEngine.profitAndLoss(
            nominal(book), openingStockValue = 5_000.0, closingStockValue = 4_650.0, inventoryEnabled = true
        )
        assertEquals("gross profit", 1_450.0, pl.grossProfit, tol)
        assertEquals("nett profit", 1_450.0, pl.nettProfit, tol)

        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0,
            closingStockValue = 4_650.0, openingStockValue = 5_000.0,
            currentPeriodProfit = pl.nettProfit, inventoryEnabled = true
        )
        assertEquals(7_650.0, bs.assetsTotal, tol)
        assertEquals(7_650.0, bs.liabilitiesTotal, tol)
        // Nothing is plugged: the opening-balance line stays at zero.
        assertEquals(0.0, bs.openingDifference, tol)
    }

    @Test
    fun `the stock ledger is not double counted against the derived closing stock`() {
        val book = listOf(
            ledger("Stock-in-Hand", "Stock-in-Hand", LedgerCategory.ASSET, debit = 5_000.0),
            ledger("Difference in Opening Balances", "Suspense A/c", LedgerCategory.LIABILITY, credit = 5_000.0)
        )
        val bs = FinancialStatementEngine.balanceSheet(
            balances = book, priorProfit = 0.0, nominalOpeningBalance = 0.0,
            openingDifference = 0.0, journalImbalance = 0.0,
            closingStockValue = 5_000.0, openingStockValue = 5_000.0,
            currentPeriodProfit = 0.0, inventoryEnabled = true
        )
        // Stock appears once, as the derived figure — never as both.
        assertTrue(bs.assets.none { it.group == TallyGroup.STOCK_IN_HAND })
        assertEquals(5_000.0, bs.assetsTotal, tol)
        assertEquals(5_000.0, bs.liabilitiesTotal, tol)
    }

    @Test
    fun `randomised balanced books always balance`() {
        // Guards the invariant against shapes the hand-written cases don't cover.
        val rnd = java.util.Random(20260813L)
        repeat(200) {
            val amount = (rnd.nextInt(90) + 10) * 1000.0
            val expense = (rnd.nextInt(50) + 1) * 100.0
            val book = listOf(
                ledger("Cash", "Cash-in-Hand", LedgerCategory.ASSET, debit = amount, credit = expense),
                ledger("Capital", "Equity", LedgerCategory.EQUITY, credit = amount),
                ledger("Rent", "Indirect Expenses", LedgerCategory.EXPENSE, debit = expense)
            )
            val pl = FinancialStatementEngine.profitAndLoss(nominal(book), 0.0, 0.0, inventoryEnabled = false)
            val bs = FinancialStatementEngine.balanceSheet(
                book, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, pl.nettProfit, inventoryEnabled = false
            )
            assertTrue(
                "unbalanced: assets=${bs.assetsTotal} liabilities=${bs.liabilitiesTotal}",
                abs(bs.assetsTotal - bs.liabilitiesTotal) < tol
            )
        }
    }
}
