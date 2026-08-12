package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserEntity
import com.example.data.report.BalanceSheet
import com.example.data.report.ProfitAndLoss
import com.example.data.report.StatementGroup
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.AccountingRed
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberGold
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter
import kotlin.math.abs

/**
 * Tally-format Balance Sheet and Profit & Loss, rendered from real ledger balances.
 *
 * Replaces the hardcoded statements that showed a different real business's books. The
 * layout follows the Tally Excel exports the statements are checked against: Balance
 * Sheet as a horizontal T with Liabilities on the left and Assets on the right, P&L as a
 * Trading Account over a Profit & Loss section, Nett Profit as the balancing figure on
 * the debit side.
 */

/** Tally prints negatives in parentheses rather than dropping the sign. */
private fun money(amount: Double): String {
    val text = IndianFormatter.formatRupee(abs(amount))
    return if (amount < -0.005) "($text)" else text
}

@Composable
private fun StatementIdentityHeader(user: UserEntity, title: String, periodLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Rendered only when the user actually provided them. These lines used to carry
        // a third party's real trading name, GSTIN and street address as `.ifBlank`
        // fallbacks compiled into the APK.
        if (user.businessName.isNotBlank()) {
            Text(
                user.businessName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (user.gstin.isNotBlank()) {
            Text(
                "GSTIN: ${user.gstin}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val address = listOf(user.address, user.city, user.state, user.pincode)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        if (address.isNotBlank()) {
            Text(address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
        // Derived from the selected range, not a frozen "1-Apr-2025 to 31-Mar-2026".
        Text(periodLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyStatement(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LavenderContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun WarningBanner(text: String, severe: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (severe) AccountingRed.copy(alpha = 0.12f) else AmberContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (severe) FontWeight.Bold else FontWeight.Normal,
            color = if (severe) AccountingRed else AmberGold,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/** One side of a T-format statement: group headings with their ledger lines. */
@Composable
private fun StatementColumn(
    header: String,
    groups: List<StatementGroup>,
    extraLines: List<Pair<String, Double>>,
    total: Double,
    onSelectLedger: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            header,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = RoyalPurplePrimary,
            modifier = Modifier
                .fillMaxWidth()
                .background(LavenderContainer.copy(alpha = 0.7f))
                .padding(6.dp)
        )
        Spacer(Modifier.height(4.dp))

        groups.forEach { group ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    group.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    money(group.total),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            group.lines.forEach { line ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLedger(line.ledgerId, line.label) }
                        .padding(start = 10.dp, top = 1.dp, bottom = 1.dp)
                ) {
                    Text(
                        if (line.ungrouped) "${line.label} •" else line.label,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        money(line.amount),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        extraLines.forEach { (label, amount) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(money(amount), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(6.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Total", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(money(total), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
        }
    }
}

@Composable
fun TallyBalanceSheetView(
    sheet: BalanceSheet?,
    user: UserEntity,
    asOnLabel: String,
    onSelectLedger: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        StatementIdentityHeader(user, "BALANCE SHEET", "as at $asOnLabel")
        Spacer(Modifier.height(10.dp))

        if (sheet == null || !sheet.hasAnyData) {
            EmptyStatement(
                "No accounts yet. Create a ledger or post a voucher and your Balance Sheet " +
                    "will appear here."
            )
            return@Column
        }

        // A corrupt journal is reported, never absorbed into another line.
        if (abs(sheet.journalImbalance) >= 0.01) {
            WarningBanner(
                "Your books do not balance by ${IndianFormatter.formatRupee(abs(sheet.journalImbalance))}. " +
                    "This statement cannot be relied upon until that is corrected.",
                severe = true
            )
            Spacer(Modifier.height(8.dp))
        }
        if (sheet.ungroupedLedgerCount > 0) {
            WarningBanner(
                "${sheet.ungroupedLedgerCount} ledger(s) marked • are not in a standard group and " +
                    "are shown under their nearest category. Review them in Chart of Accounts.",
                severe = false
            )
            Spacer(Modifier.height(8.dp))
        }

        val liabilityExtras = buildList {
            if (abs(sheet.pnlOpening) >= 0.005 || abs(sheet.pnlCurrent) >= 0.005) {
                add("Profit & Loss A/c" to (sheet.pnlOpening + sheet.pnlCurrent))
            }
            // Tally's own line name. Opening balances are written onto the ledger row
            // with no contra posting, so nothing forces them to net to zero.
            if (sheet.openingDifference > 0.005) {
                add("Difference in Opening Balances" to sheet.openingDifference)
            }
        }
        val assetExtras = buildList {
            if (sheet.closingStock >= 0.005) add("Closing Stock" to sheet.closingStock)
            if (sheet.openingDifference < -0.005) {
                add("Difference in Opening Balances" to -sheet.openingDifference)
            }
        }

        Row(Modifier.fillMaxWidth()) {
            StatementColumn(
                header = "Liabilities",
                groups = sheet.liabilities,
                extraLines = liabilityExtras,
                total = sheet.liabilitiesTotal,
                onSelectLedger = onSelectLedger,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            StatementColumn(
                header = "Assets",
                groups = sheet.assets,
                extraLines = assetExtras,
                total = sheet.assetsTotal,
                onSelectLedger = onSelectLedger,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
        }

        if (sheet.closingStock >= 0.005) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Closing stock valued at the cost recorded on each item. Not reconciled to a physical count.",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TallyProfitAndLossView(
    pnl: ProfitAndLoss?,
    user: UserEntity,
    periodLabel: String,
    onSelectLedger: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        StatementIdentityHeader(user, "PROFIT & LOSS A/C", periodLabel)
        Spacer(Modifier.height(10.dp))

        if (pnl == null || !pnl.hasAnyData) {
            EmptyStatement("No income or expense postings in $periodLabel.")
            return@Column
        }

        // Trading Account — present only for a business carrying stock. A service
        // business has no Opening Stock, no Closing Stock and no Gross Profit line at
        // all; the section is absent rather than zeroed.
        if (pnl.tradingShown) {
            Text(
                "Trading Account",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RoyalPurplePrimary
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                StatementColumn(
                    header = "Particulars (Dr)",
                    groups = pnl.tradingDebit,
                    extraLines = buildList {
                        if (pnl.openingStock >= 0.005) add(0, "Opening Stock" to pnl.openingStock)
                        if (pnl.grossProfit >= 0.005) add("Gross Profit c/o" to pnl.grossProfit)
                    },
                    total = pnl.tradingDebit.sumOf { it.total } + pnl.openingStock +
                        (if (pnl.grossProfit >= 0.0) pnl.grossProfit else 0.0),
                    onSelectLedger = onSelectLedger,
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                )
                StatementColumn(
                    header = "Particulars (Cr)",
                    groups = pnl.tradingCredit,
                    extraLines = buildList {
                        if (pnl.closingStock >= 0.005) add("Closing Stock" to pnl.closingStock)
                        if (pnl.grossProfit < -0.005) add("Gross Loss c/o" to -pnl.grossProfit)
                    },
                    total = pnl.tradingCredit.sumOf { it.total } + pnl.closingStock +
                        (if (pnl.grossProfit < 0.0) -pnl.grossProfit else 0.0),
                    onSelectLedger = onSelectLedger,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(
            "Profit & Loss Account",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = RoyalPurplePrimary
        )
        Spacer(Modifier.height(4.dp))

        val grossBroughtForward = if (pnl.tradingShown && pnl.grossProfit >= 0.005) pnl.grossProfit else 0.0
        val grossLossBroughtForward = if (pnl.tradingShown && pnl.grossProfit < -0.005) -pnl.grossProfit else 0.0

        Row(Modifier.fillMaxWidth()) {
            StatementColumn(
                header = "Particulars (Dr)",
                groups = pnl.pnlDebit,
                extraLines = buildList {
                    if (grossLossBroughtForward > 0.0) add(0, "Gross Loss b/f" to grossLossBroughtForward)
                    // Tally carries Nett Profit on the debit side as the balancing figure.
                    if (pnl.nettProfit >= 0.005) add("Nett Profit" to pnl.nettProfit)
                },
                total = pnl.pnlDebit.sumOf { it.total } + grossLossBroughtForward +
                    (if (pnl.nettProfit >= 0.0) pnl.nettProfit else 0.0),
                onSelectLedger = onSelectLedger,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            StatementColumn(
                header = "Particulars (Cr)",
                groups = pnl.pnlCredit,
                extraLines = buildList {
                    if (grossBroughtForward > 0.0) add(0, "Gross Profit b/f" to grossBroughtForward)
                    if (pnl.nettProfit < -0.005) add("Nett Loss" to -pnl.nettProfit)
                },
                total = pnl.pnlCredit.sumOf { it.total } + grossBroughtForward +
                    (if (pnl.nettProfit < 0.0) -pnl.nettProfit else 0.0),
                onSelectLedger = onSelectLedger,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (pnl.nettProfit >= 0) AccountingGreen.copy(alpha = 0.10f)
            else AccountingRed.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(10.dp).fillMaxWidth()) {
                Text(
                    if (pnl.nettProfit >= 0) "Nett Profit" else "Nett Loss",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = if (pnl.nettProfit >= 0) AccountingGreen else AccountingRed
                )
                Text(
                    IndianFormatter.formatRupee(abs(pnl.nettProfit)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pnl.nettProfit >= 0) AccountingGreen else AccountingRed
                )
            }
        }
    }
}
