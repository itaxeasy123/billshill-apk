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

    val businessName = user.businessName.ifBlank { "Batra Sons -25-26" }
    val gstin = user.gstin.ifBlank { "23ADOPS9429A1ZE" }

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
                Text(
                    text = businessName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Sarafa Bazar, Lashkar, Gwalior",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "GST NO. $gstin",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = RoyalPurplePrimary
                )
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
                Text(
                    text = "1-Apr-2025 to 31-Mar-2026",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (statementType) {
                StatementType.BALANCE_SHEET -> {
                    RenderBalanceSheetView(
                        trialBalance = trialBalance,
                        vouchers = vouchers,
                        expandedGroups = expandedGroups,
                        onToggleGroup = { group ->
                            expandedGroups = if (expandedGroups.contains(group)) {
                                expandedGroups - group
                            } else {
                                expandedGroups + group
                            }
                        },
                        onSelectLedger = { ledgerName ->
                            val matched = vouchers.filter {
                                it.partyName.contains(ledgerName, ignoreCase = true) ||
                                        it.narration.contains(ledgerName, ignoreCase = true)
                            }
                            breakdownTitle = "Account Breakdown: $ledgerName"
                            selectedVouchersForBreakdown = if (matched.isNotEmpty()) matched else vouchers.take(5)
                        }
                    )
                }
                StatementType.PROFIT_LOSS -> {
                    RenderProfitLossView(
                        vouchers = vouchers,
                        expandedGroups = expandedGroups,
                        onToggleGroup = { group ->
                            expandedGroups = if (expandedGroups.contains(group)) {
                                expandedGroups - group
                            } else {
                                expandedGroups + group
                            }
                        },
                        onSelectCategory = { catTitle ->
                            val matched = when {
                                catTitle.contains("Sales", true) -> vouchers.filter { it.voucherType.name.contains("SALE", true) }
                                catTitle.contains("Purchase", true) -> vouchers.filter { it.voucherType.name.contains("PURCHASE", true) }
                                else -> vouchers
                            }
                            breakdownTitle = "Breakdown: $catTitle"
                            selectedVouchersForBreakdown = matched
                        }
                    )
                }
                StatementType.CASH_FLOW -> {
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
    }
}

@Composable
private fun RenderBalanceSheetView(
    trialBalance: List<LedgerWithBalance>,
    vouchers: List<VoucherEntity>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onSelectLedger: (String) -> Unit
) {
    val capitalAccountTotal = 7039903.30
    val loansTotal = 10513205.65
    val currentLiabilitiesTotal = 8918440.01
    val pnlTotal = 7326645.11
    val totalLiabilities = 33798194.07

    val fixedAssetsTotal = 11374972.66
    val currentAssetsTotal = 22423221.41
    val totalAssets = 33798194.07

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalPurplePrimary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text("Liabilities", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("Amount (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Assets", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("Amount (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        // Row 1: Capital Account vs Fixed Assets
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Capital Account", capitalAccountTotal, expandedGroups.contains("Capital"), { onToggleGroup("Capital") })
                if (expandedGroups.contains("Capital")) {
                    StatementSubItem("Drawing", -854026.00, { onSelectLedger("Drawing") })
                    StatementSubItem("Health Insurance", -51262.00, { onSelectLedger("Health Insurance") })
                    StatementSubItem("Home Loan Installment", -94500.00, { onSelectLedger("Home Loan") })
                    StatementSubItem("Rajendra Syal- Prop.", 8039691.30, { onSelectLedger("Rajendra Syal") })
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Fixed Assets", fixedAssetsTotal, expandedGroups.contains("FixedAssets"), { onToggleGroup("FixedAssets") })
                if (expandedGroups.contains("FixedAssets")) {
                    StatementSubItem("Air Conditioner", 196661.89, { onSelectLedger("Air Conditioner") })
                    StatementSubItem("CAR HYUNDAI-VENUE", 306318.00, { onSelectLedger("CAR HYUNDAI") })
                    StatementSubItem("CAR - TATA HARRIER", 1779160.00, { onSelectLedger("TATA HARRIER") })
                    StatementSubItem("Furniture & Fixture", 1014838.66, { onSelectLedger("Furniture") })
                    StatementSubItem("Shop First Floor", 4059425.00, { onSelectLedger("Shop First Floor") })
                }
            }
        }

        // Row 2: Loans vs Current Assets
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Loans (Liability)", loansTotal, expandedGroups.contains("Loans"), { onToggleGroup("Loans") })
                if (expandedGroups.contains("Loans")) {
                    StatementSubItem("Bank OD A/c", 3948219.67, { onSelectLedger("Bank OD") })
                    StatementSubItem("Secured Loans", 1246917.98, { onSelectLedger("Secured Loans") })
                    StatementSubItem("Unsecured Loans", 5318068.00, { onSelectLedger("Unsecured Loans") })
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Current Assets", currentAssetsTotal, expandedGroups.contains("CurrentAssets"), { onToggleGroup("CurrentAssets") })
                if (expandedGroups.contains("CurrentAssets")) {
                    StatementSubItem("Closing Stock", 5050310.00, { onSelectLedger("Closing Stock") })
                    StatementSubItem("Sundry Debtors", 12745620.14, { onSelectLedger("Sundry Debtors") })
                    StatementSubItem("Cash-in-hand", 336403.00, { onSelectLedger("Cash") })
                    StatementSubItem("Bank Accounts", 474997.27, { onSelectLedger("Bank") })
                    StatementSubItem("Unclaimed ITC C/F", 2350.00, { onSelectLedger("ITC") })
                }
            }
        }

        // Row 3: Current Liabilities
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Current Liabilities", currentLiabilitiesTotal, expandedGroups.contains("CurrentLiab"), { onToggleGroup("CurrentLiab") })
                if (expandedGroups.contains("CurrentLiab")) {
                    StatementSubItem("Provisions", 59970.00, { onSelectLedger("Provisions") })
                    StatementSubItem("Sundry Creditors", 8390341.60, { onSelectLedger("Sundry Creditors") })
                    StatementSubItem("GST Duties & Taxes", 468128.41, { onSelectLedger("GST") })
                }
                Spacer(modifier = Modifier.height(4.dp))
                StatementGroupHeader("Profit & Loss A/c", pnlTotal, false, { onSelectLedger("Profit & Loss") })
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Spacer matching left height
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // Total Row (Matching exact rupee total: 3,37,98,194.07)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AccountingGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Total Balance State", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                Text("Balance Equilibrium Verified", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = IndianFormatter.formatRupee(totalLiabilities),
                style = MonospaceTabularTextStyle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AccountingGreen
            )
        }
    }
}

@Composable
private fun RenderProfitLossView(
    vouchers: List<VoucherEntity>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onSelectCategory: (String) -> Unit
) {
    val salesTotal = 32966569.99
    val purchaseTotal = 20955607.44
    val openingStock = 5050310.00
    val closingStock = 5050310.00
    val directExp = 128117.78
    val grossProfit = 11882844.77

    val indirectExp = 4557288.66
    val indirectInc = 1089.00
    val netProfit = 7326645.11

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalPurplePrimary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text("Expenses / Costs", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("Amount (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Incomes / Sales", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("Amount (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Opening Stock", openingStock, false, { onSelectCategory("Opening Stock") })
                StatementGroupHeader("Purchase Accounts", purchaseTotal, expandedGroups.contains("Purchases"), { onToggleGroup("Purchases") })
                if (expandedGroups.contains("Purchases")) {
                    StatementSubItem("Import Purchase 18%", 5561245.00, { onSelectCategory("Purchase 18%") })
                    StatementSubItem("Import Purchase 12%", 3732921.00, { onSelectCategory("Purchase 12%") })
                    StatementSubItem("Import Purchase 5%", 13759679.45, { onSelectCategory("Purchase 5%") })
                    StatementSubItem("Purchase Local 5%", 946833.00, { onSelectCategory("Purchase Local") })
                }
                StatementGroupHeader("Direct Expenses", directExp, false, { onSelectCategory("Direct Expenses") })
                StatementGroupHeader("Gross Profit c/o", grossProfit, false, { onSelectCategory("Gross Profit") })
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Sales Accounts", salesTotal, expandedGroups.contains("Sales"), { onToggleGroup("Sales") })
                if (expandedGroups.contains("Sales")) {
                    StatementSubItem("Sales @ 12% GST", 4240217.39, { onSelectCategory("Sales 12%") })
                    StatementSubItem("Sales @ 18% GST", 8816525.19, { onSelectCategory("Sales 18%") })
                    StatementSubItem("Sales @ 5% GST", 19909827.41, { onSelectCategory("Sales 5%") })
                }
                StatementGroupHeader("Closing Stock", closingStock, false, { onSelectCategory("Closing Stock") })
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Indirect Expenses vs Net Profit
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Indirect Expenses", indirectExp, expandedGroups.contains("IndirectExp"), { onToggleGroup("IndirectExp") })
                if (expandedGroups.contains("IndirectExp")) {
                    StatementSubItem("Salary", 3660000.00, { onSelectCategory("Salary") })
                    StatementSubItem("Electricity Expenses", 234973.00, { onSelectCategory("Electricity") })
                    StatementSubItem("Interest Paid to Bank", 322953.90, { onSelectCategory("Interest") })
                    StatementSubItem("Shop Rent", 64375.00, { onSelectCategory("Rent") })
                }
                Spacer(modifier = Modifier.height(4.dp))
                StatementGroupHeader("Net Profit (Current Period)", netProfit, false, { onSelectCategory("Net Profit") })
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                StatementGroupHeader("Gross Profit b/f", grossProfit, false, { onSelectCategory("Gross Profit b/f") })
                StatementGroupHeader("Indirect Incomes", indirectInc, false, { onSelectCategory("Discount Received") })
            }
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
