package com.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.data.dao.LedgerWithBalance
import com.example.data.dao.MonthlyPnlRow
import com.example.data.model.LedgerCategory
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.components.AnalyticsSummaryChartCard
import com.example.ui.components.CustomVoucherModal
import com.example.ui.components.LedgerManagementModal
import com.example.ui.components.GstCalculatorModal
import com.example.ui.components.ChoiceChip
import com.example.ui.components.ChoiceChipRow
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the pieces that were reported as broken at the width of the phone they were
 * reported on — a 720x1600 device is 360dp wide — and writes a PNG per case.
 *
 * These are not assertions. They exist so a layout claim can be checked by looking at the
 * output instead of reasoning about measure passes: every defect in the bug report (blank
 * rate pills, "Journ / al", a header three times its proper height) is visible in a
 * render at this width and invisible at tablet widths.
 *
 * Output: app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ResponsiveLayoutScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            MyApplicationTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) { content() }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Dialogs live in their own window, so the capture targets the dialog node. */
    private fun captureDialog(name: String, content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MyApplicationTheme { content() } }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ManualVoucherDialog is not captured here: its two ExposedDropdownMenuBox anchors
    // never report idle under Robolectric, so the capture times out on the harness rather
    // than on anything in the layout. Its one broken piece was the voucher-type chip row,
    // which `voucher type chips` below renders directly.

    @Test
    fun `gst calculator dialog`() = captureDialog("dialog_gst_calculator") {
        GstCalculatorModal(onDismiss = {}, onApplyToVoucher = { _, _, _, _ -> })
    }

    @Test
    fun `custom voucher dialog`() = captureDialog("dialog_custom_voucher") {
        CustomVoucherModal(viewModel = viewModel(), onDismiss = {})
    }

    @Test
    fun `ledger management dialog`() = captureDialog("dialog_ledger_management") {
        LedgerManagementModal(viewModel = viewModel(), onDismiss = {})
    }

    private fun viewModel() =
        AccountingViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `voucher type chips`() = capture("chips_voucher_type") {
        Text("Voucher type", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        var selected by remember { mutableStateOf(VoucherType.JOURNAL) }
        ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp) {
            listOf(VoucherType.JOURNAL, VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.CONTRA)
                .forEach { type ->
                    ChoiceChip(
                        label = type.displayName,
                        selected = selected == type,
                        onClick = { selected = type },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
        }
    }

    @Test
    fun `gst rate slab chips`() = capture("chips_gst_slabs") {
        Text("GST rate", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        var selected by remember { mutableStateOf(18.0) }
        ChoiceChipRow(modifier = Modifier.fillMaxWidth(), horizontalSpacing = 6.dp) {
            listOf(0.0, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0, 40.0).forEach { slab ->
                ChoiceChip(
                    label = if (slab % 1.0 == 0.0) "${slab.toInt()}%" else "$slab%",
                    selected = selected == slab,
                    onClick = { selected = slab },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ChoiceChip(label = "Custom", selected = false, onClick = {}, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Test
    fun `analytics card header and axis`() = capture("analytics_card") {
        Box {
            AnalyticsSummaryChartCard(
                monthlyPnlRows = listOf(
                    MonthlyPnlRow(monthKey = "2026-04", category = LedgerCategory.REVENUE, totalDebit = 0.0, totalCredit = 425000.0),
                    MonthlyPnlRow(monthKey = "2026-04", category = LedgerCategory.EXPENSE, totalDebit = 218000.0, totalCredit = 0.0),
                    MonthlyPnlRow(monthKey = "2026-05", category = LedgerCategory.REVENUE, totalDebit = 0.0, totalCredit = 512000.0),
                    MonthlyPnlRow(monthKey = "2026-05", category = LedgerCategory.EXPENSE, totalDebit = 265000.0, totalCredit = 0.0),
                    MonthlyPnlRow(monthKey = "2026-06", category = LedgerCategory.REVENUE, totalDebit = 0.0, totalCredit = 388000.0),
                    MonthlyPnlRow(monthKey = "2026-06", category = LedgerCategory.EXPENSE, totalDebit = 301000.0, totalCredit = 0.0)
                ),
                // April to September 2026 — six months, the shape a quarterly or
                // half-yearly range produces.
                rangeStartMillis = 1775001600000L,
                rangeEndMillis = 1790726400000L,
                periodExpenseLedgers = listOf(
                    LedgerWithBalance(id = 1, name = "Rent & Premises", groupName = "Indirect Expenses", category = LedgerCategory.EXPENSE, totalDebit = 360000.0, totalCredit = 0.0, currentBalance = 360000.0),
                    LedgerWithBalance(id = 2, name = "Salaries", groupName = "Indirect Expenses", category = LedgerCategory.EXPENSE, totalDebit = 285000.0, totalCredit = 0.0, currentBalance = 285000.0),
                    LedgerWithBalance(id = 3, name = "Transport", groupName = "Direct Expenses", category = LedgerCategory.EXPENSE, totalDebit = 139000.0, totalCredit = 0.0, currentBalance = 139000.0)
                )
            )
        }
    }
}
