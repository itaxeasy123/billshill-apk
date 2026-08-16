package com.example

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.example.data.dao.LedgerWithBalance
import com.example.data.dao.MonthlyPnlRow
import com.example.data.model.LedgerCategory
import com.example.ui.components.AnalyticsSummaryChartCard
import org.junit.Rule
import org.junit.Test

/**
 * The first Compose UI tests in this repo.
 *
 * Every chart defect fixed in this pass lived in the presentation layer, and the whole
 * test suite — 156 unit tests and 38 instrumented ones — could go green without a single
 * one of these composables ever being rendered. The app also opens on an SMS OTP gate, so
 * no screen behind login can be reached by hand either. These render the changed
 * composables directly, which needs no login because they take plain parameters.
 */
class ChartScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun expense(name: String, amount: Double) = LedgerWithBalance(
        id = name.hashCode().toLong(),
        name = name,
        groupName = "Indirect Expenses",
        category = LedgerCategory.EXPENSE,
        totalDebit = amount,
        totalCredit = 0.0,
        currentBalance = amount
    )

    private fun month(key: String, revenue: Double, expense: Double) = listOf(
        MonthlyPnlRow(key, LedgerCategory.REVENUE, 0.0, revenue),
        MonthlyPnlRow(key, LedgerCategory.EXPENSE, expense, 0.0)
    )

    @Test
    fun theAnalyticsCardRendersWithRealData() {
        compose.setContent {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = month("2026-04", 1_18_000.0, 59_000.0) +
                    month("2026-05", 90_000.0, 1_20_000.0),
                rangeStartMillis = 0L,
                rangeEndMillis = Long.MAX_VALUE,
                periodExpenseLedgers = listOf(expense("Rent", 2_00_000.0))
            )
        }
        compose.onNodeWithTag("analytics_summary_chart_card").assertIsDisplayed()
    }

    @Test
    fun aLossMonthDoesNotCrashTheCanvas() {
        // May is a loss month. Loss bars used to be drawn into 16dp of padding; the geometry
        // now positions zero inside the drawable band, and this proves it still composes.
        compose.setContent {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = month("2026-04", 5_00_000.0, 1_00_000.0) +
                    month("2026-05", 50_000.0, 5_50_000.0),
                rangeStartMillis = 0L,
                rangeEndMillis = Long.MAX_VALUE,
                periodExpenseLedgers = listOf(expense("Rent", 1_000.0))
            )
        }
        compose.onNodeWithTag("analytics_summary_chart_card").assertIsDisplayed()
    }

    @Test
    fun theRemainderIsDisclosedOnScreen() {
        // Nine expense heads: five slices plus an "Other (4 ledgers)" row. Before the fix
        // the card showed five slices summing to 100% with no sign a remainder existed.
        //
        // Scrolled to first: the card is taller than the screen, exactly as it is inside
        // the Reports LazyColumn.
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AnalyticsSummaryChartCard(
                    monthlyPnlRows = month("2026-04", 1_00_000.0, 45_000.0),
                    rangeStartMillis = 0L,
                    rangeEndMillis = Long.MAX_VALUE,
                    periodExpenseLedgers = (1..9).map { expense("Head $it", it * 10_000.0) }
                )
            }
        }
        // assertExists, not assertIsDisplayed: the card is taller than the test viewport,
        // so the row composes below the fold. That it is PRODUCED, with the right ledger
        // count, is the thing the old code got wrong — the percentages themselves are
        // pinned by ChartMathTest.
        // The donut lives behind the "Expenses" tab; the card opens on the trend chart.
        compose.onAllNodesWithText("Expenses")[0].performClick()
        compose.onNodeWithText("Other (4 ledgers)").assertExists()
    }

    @Test
    fun fiveOrFewerHeadsShowNoOtherRow() {
        compose.setContent {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = month("2026-04", 1_00_000.0, 45_000.0),
                rangeStartMillis = 0L,
                rangeEndMillis = Long.MAX_VALUE,
                periodExpenseLedgers = (1..4).map { expense("Head $it", 10_000.0) }
            )
        }
        compose.onAllNodesWithText("Expenses")[0].performClick()
        compose.onAllNodesWithText("Other", substring = true).assertCountEquals(0)
    }

    @Test
    fun theDonutHoleNoLongerClaimsTopFive() {
        // The label said "Top 5" while the ring now covers every expense head.
        compose.setContent {
          Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = month("2026-04", 1_00_000.0, 45_000.0),
                rangeStartMillis = 0L,
                rangeEndMillis = Long.MAX_VALUE,
                periodExpenseLedgers = (1..9).map { expense("Head $it", it * 1_000.0) }
            )
          }
        }
        compose.onAllNodesWithText("Expenses")[0].performClick()
        compose.onAllNodesWithText("Top 5").assertCountEquals(0)
        compose.onNodeWithText("All").assertExists()
    }

    @Test
    fun anEmptyBookRendersWithoutCrashing() {
        compose.setContent {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = emptyList(),
                rangeStartMillis = 0L,
                rangeEndMillis = Long.MAX_VALUE,
                periodExpenseLedgers = emptyList()
            )
        }
        compose.onNodeWithTag("analytics_summary_chart_card").assertIsDisplayed()
    }
}
