package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.LedgerWithBalance
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.ui.AccountingViewModel
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.AccountingRed
import com.example.ui.theme.MonospaceTabularTextStyle
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter

enum class StatementType {
    BALANCE_SHEET,
    PROFIT_LOSS,
    CASH_FLOW
}

@Composable
fun HierarchicalFinancialStatement(
    statementType: StatementType,
    user: UserEntity,
    vouchers: List<VoucherEntity>,
    trialBalance: List<LedgerWithBalance>,
    viewModel: AccountingViewModel,
    modifier: Modifier = Modifier
) {
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var selectedVouchersForBreakdown by remember { mutableStateOf<List<VoucherEntity>?>(null) }
    var breakdownTitle by remember { mutableStateOf("") }

    if (selectedVouchersForBreakdown != null) {
        VoucherBreakdownModal(
            title = breakdownTitle,
            vouchers = selectedVouchersForBreakdown!!,
            viewModel = viewModel,
            onDismiss = { selectedVouchersForBreakdown = null }
        )
    }

    // Rendered only when the user supplied them. These were `.ifBlank` fallbacks
    // carrying a different real business's trading name and GSTIN, and became live the
    // moment UserEntity's fabricated defaults were blanked.
    val businessName = user.businessName
    val gstin = user.gstin

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("hierarchical_statement_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Enterprise Header Block (Matching Indian Tally/Marg Format)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = RoyalPurplePrimary.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (businessName.isNotBlank()) {
                    Text(
                        text = businessName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // Was an unconditional literal: a third party's real trading address,
                // compiled into every build and shown to every user.
                val address = listOf(user.address, user.city, user.state, user.pincode)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (gstin.isNotBlank()) {
                    Text(
                        text = "GST NO. $gstin",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = RoyalPurplePrimary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = RoyalPurplePrimary.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (statementType) {
                        StatementType.BALANCE_SHEET -> "Balance Sheet"
                        StatementType.PROFIT_LOSS -> "Profit & Loss A/c"
                        StatementType.CASH_FLOW -> "Cash Flow Statement"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Was a frozen literal period unrelated to the data shown.
                Text(
                    text = com.example.utils.FiscalYearUtils.currentFyLabel(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cash Flow only. The Balance Sheet and Profit & Loss arms were deleted
            // along with the hardcoded figures they rendered — ReportsScreen now routes
            // those two tabs to TallyBalanceSheetView / TallyProfitAndLossView, which
            // derive from real ledger balances.
            RenderCashFlowView(
                vouchers = vouchers,
                onSelectCategory = { title, matchedVouchers ->
                    breakdownTitle = title
                    selectedVouchersForBreakdown = matchedVouchers
                }
            )
        }
    }
}

@Composable
private fun RenderCashFlowView(
    vouchers: List<VoucherEntity>,
    onSelectCategory: (String, List<VoucherEntity>) -> Unit
) {
    val salesVouchers = vouchers.filter { it.voucherType.name.contains("SALE", true) || it.voucherType.name.contains("RECEIPT", true) }
    val purchaseVouchers = vouchers.filter { it.voucherType.name.contains("PURCHASE", true) || it.voucherType.name.contains("PAYMENT", true) }

    val salesInflow = salesVouchers.sumOf { it.totalAmount }
    val purchaseOutflow = purchaseVouchers.sumOf { it.totalAmount }
    val netCash = salesInflow - purchaseOutflow

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatementGroupHeader("Cash Inflows (Operating / Sales / Receipts)", salesInflow, false, { onSelectCategory("Cash Inflows", salesVouchers) })
        StatementGroupHeader("Cash Outflows (Operating / Purchases / Payments)", purchaseOutflow, false, { onSelectCategory("Cash Outflows", purchaseVouchers) })

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalPurplePrimary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Net Period Cash Flow", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
            Text(
                text = IndianFormatter.formatRupee(netCash),
                style = MonospaceTabularTextStyle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (netCash >= 0) AccountingGreen else AccountingRed
            )
        }
    }
}

@Composable
private fun StatementGroupHeader(
    title: String,
    amount: Double,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("statement_group_header_${title.lowercase().replace(" ", "_")}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = RoyalPurplePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = IndianFormatter.formatRupee(Math.abs(amount)),
                style = MonospaceTabularTextStyle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatementSubItem(
    title: String,
    amount: Double,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 24.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = IndianFormatter.formatRupee(Math.abs(amount)),
            style = MonospaceTabularTextStyle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
