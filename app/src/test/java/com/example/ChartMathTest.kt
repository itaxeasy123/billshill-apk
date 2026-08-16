package com.example

import com.example.data.dao.LedgerWithBalance
import com.example.data.model.LedgerCategory
import com.example.ui.components.ChartAxis
import com.example.ui.components.expenseBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two chart defects lived inside `Canvas { }` lambdas and a `remember { }` block, where
 * nothing in this repo could execute them — which is exactly why a full green test run
 * never noticed either. Both are now pure functions, and these are the assertions that
 * would have caught them.
 */
class ChartMathTest {

    // Real dp values from AnalyticsSummaryChartCard's bar canvas.
    private val height = 150f
    private val padY = 16f
    private val drawH = 118f

    private fun expense(name: String, amount: Double) = LedgerWithBalance(
        id = name.hashCode().toLong(),
        name = name,
        groupName = "Indirect Expenses",
        category = LedgerCategory.EXPENSE,
        totalDebit = amount,
        totalCredit = 0.0,
        currentBalance = amount
    )

    // ---- the axis: no invented floor ------------------------------------------------

    @Test
    fun `a small month scales to its own data, not to an invented floor`() {
        // The axis was max(observed, 50_000.0) * 1.15, so it never dropped below Rs 57,500.
        // A trader with Rs 4,000 of income got a bar 7% of chart height that read as
        // "nothing happened" rather than "a small month".
        val axis = ChartAxis.forValues(listOf(4_000.0, 1_500.0, 2_500.0))

        assertTrue("must scale to the data: ${axis.max}", axis.max < 10_000.0)
        val h = axis.barHeight(4_000.0, drawH)
        assertTrue("the tallest bar should fill most of the chart, got $h of $drawH", h > drawH * 0.8)
    }

    @Test
    fun `an all-zero series does not divide by zero`() {
        val axis = ChartAxis.forValues(listOf(0.0, 0.0))
        assertEquals(1.0, axis.span, 0.0)
        assertEquals(0f, axis.barHeight(0.0, drawH), 0.0001f)
    }

    @Test
    fun `an empty series does not throw`() {
        val axis = ChartAxis.forValues(emptyList())
        assertEquals(0.0, axis.max, 0.0)
        assertEquals(0.0, axis.min, 0.0)
    }

    @Test
    fun `with no losses the zero line is the baseline, so nothing renders differently`() {
        val axis = ChartAxis.forValues(listOf(1_000.0, 500.0))
        assertEquals(height - padY, axis.zeroY(height, padY, drawH), 0.001f)
    }

    // ---- loss bars: the clipping bug -------------------------------------------------

    @Test
    fun `two different losses render at different heights`() {
        // The bug: a loss was drawn downward from `height - padY` by up to drawH (118dp)
        // into padY (16dp) of space, so Compose clipped it — a Rs 78,000 loss and a
        // Rs 5,00,000 loss came out as the same 16dp stub.
        val axis = ChartAxis.forValues(listOf(5_00_000.0, -78_000.0, -5_00_000.0))

        val small = axis.barHeight(-78_000.0, drawH)
        val large = axis.barHeight(-5_00_000.0, drawH)

        assertTrue("a bigger loss must draw taller: $small vs $large", large > small * 2)
    }

    @Test
    fun `no bar is ever drawn outside the canvas, at any scale`() {
        // The property that makes clipping impossible rather than merely unlikely.
        val series = listOf(
            listOf(5_00_000.0, -2_00_000.0, 1_00_000.0),
            listOf(-9_00_000.0, 4_000.0),
            listOf(0.01, -0.01),
            listOf(1_00_00_000.0, -1_00_00_000.0),
            listOf(7_000.0)
        )
        series.forEach { values ->
            val axis = ChartAxis.forValues(values)
            values.forEach { v ->
                val top = axis.barTop(v, height, padY, drawH)
                val bottom = top + axis.barHeight(v, drawH)
                assertTrue("top $top above canvas for $v in $values", top >= padY - 0.01f)
                assertTrue("bottom $bottom below canvas for $v in $values", bottom <= height - padY + 0.01f)
            }
        }
    }

    @Test
    fun `a loss grows downward from zero and a profit upward`() {
        val axis = ChartAxis.forValues(listOf(1_00_000.0, -50_000.0))
        val zero = axis.zeroY(height, padY, drawH)

        assertTrue("profit sits above zero", axis.barTop(1_00_000.0, height, padY, drawH) < zero)
        assertEquals("a loss starts at zero", zero, axis.barTop(-50_000.0, height, padY, drawH), 0.001f)
    }

    // ---- the donut: percentages of the true total ------------------------------------

    @Test
    fun `percentages are of total expenses, not of the top five`() {
        // Twelve ledgers totalling 10,00,000 with a top five of 6,00,000. Rent at
        // 2,00,000 is 20% — it used to display as 33.3%, inflated 1.67x, because the
        // denominator was summed after .take(5).
        val ledgers = listOf(
            expense("Rent", 2_00_000.0),
            expense("Salaries", 1_50_000.0),
            expense("Freight", 1_00_000.0),
            expense("Power", 90_000.0),
            expense("Repairs", 60_000.0)
        ) + (1..7).map { expense("Misc $it", 4_00_000.0 / 7) }

        val items = expenseBreakdown(ledgers)
        val rent = items.single { it.categoryName == "Rent" }

        assertEquals("Rent is 20% of 10,00,000", 20.0f, rent.percentage, 0.01f)
    }

    @Test
    fun `the remainder gets its own slice and the ring closes`() {
        val ledgers = (1..9).map { expense("Head $it", it * 10_000.0) }
        val items = expenseBreakdown(ledgers)

        val other = items.single { it.categoryName.startsWith("Other") }
        assertEquals("Other (4 ledgers)", other.categoryName)
        assertEquals(
            "every slice must be accounted for",
            100.0f, items.sumOf { it.percentage.toDouble() }.toFloat(), 0.05f
        )
    }

    @Test
    fun `five or fewer ledgers produce no Other slice`() {
        val items = expenseBreakdown((1..5).map { expense("Head $it", 1_000.0) })
        assertEquals(5, items.size)
        assertFalse(items.any { it.categoryName.startsWith("Other") })
        assertEquals(100.0f, items.sumOf { it.percentage.toDouble() }.toFloat(), 0.05f)
    }

    @Test
    fun `non-expense and zero-balance ledgers are excluded`() {
        val ledgers = listOf(
            expense("Rent", 1_000.0),
            expense("Nothing", 0.0),
            LedgerWithBalance(9L, "Sales", "Sales Accounts", LedgerCategory.REVENUE, 0.0, 5_000.0, 5_000.0)
        )
        val items = expenseBreakdown(ledgers)

        assertEquals(1, items.size)
        assertEquals("Rent", items.single().categoryName)
    }

    @Test
    fun `an empty book produces no slices rather than a divide by zero`() {
        assertTrue(expenseBreakdown(emptyList()).isEmpty())
    }
}
