package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.LedgerTxEntry
import com.example.data.dao.LedgerWithBalance
import com.example.data.model.LedgerCategory
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.gst.GstClassifier
import com.example.data.gst.SupplyCategory
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.components.AnalyticsSummaryChartCard
import com.example.ui.components.CashBankTrendChartCard
import com.example.ui.components.DateRangeFilterState
import com.example.ui.components.DynamicDateRangeSelector
import com.example.ui.components.toEpochMillisRange
import com.example.ui.components.LedgerSummaryList
import com.example.ui.components.MonetaryRow
import com.example.ui.theme.*
import com.example.utils.*

import com.example.ui.components.ExportDataButton
import com.example.ui.components.HierarchicalFinancialStatement
import com.example.ui.components.StatementType
import com.example.ui.components.VoucherBreakdownModal
import com.example.ui.components.TallyBalanceSheetView
import com.example.ui.components.TallyProfitAndLossView
import kotlinx.coroutines.launch

@Composable
fun ReportsScreen(
    viewModel: AccountingViewModel,
    user: UserEntity
) {
    var dateRangeState by remember { mutableStateOf(DateRangeFilterState()) }
    var selectedReportTab by remember { mutableStateOf(0) }
    val reportTitles = remember(user.enableInventory) {
        if (user.enableInventory) {
            listOf("Analytics & Trends", "Trial Balance", "Audit Trail", "P & L", "Balance Sheet", "GSTR-1 Summary", "Tally & Marg XML", "Cash Flow", "Chart of Accounts", "Statutory GST", "Stock Status")
        } else {
            listOf("Analytics & Trends", "Trial Balance", "Audit Trail", "P & L", "Balance Sheet", "GSTR-1 Summary", "Tally & Marg XML", "Cash Flow", "Chart of Accounts", "Statutory GST")
        }
    }

    var ledgerSearchQuery by remember { mutableStateOf("") }
    var ledgerCategoryFilter by remember { mutableStateOf("ALL") }

    val context = LocalContext.current
    var selectedLedgerForStatement by remember { mutableStateOf<LedgerWithBalance?>(null) }
    var ledgerTransactions by remember { mutableStateOf<List<LedgerTxEntry>>(emptyList()) }
    var editingVoucherInStatement by remember { mutableStateOf<VoucherEntity?>(null) }
    var editPartyName by remember { mutableStateOf("") }
    var editAmountText by remember { mutableStateOf("") }
    var editNarration by remember { mutableStateOf("") }

    // Voucher Breakdown Modal State
    var breakdownModalTitle by remember { mutableStateOf<String?>(null) }
    var breakdownModalVouchers by remember { mutableStateOf<List<VoucherEntity>>(emptyList()) }
    var showTrialBalanceReportModal by remember { mutableStateOf(false) }

    // Tally & Marg XML States
    var xmlImportText by remember { mutableStateOf("") }
    var parsedXmlVouchers by remember { mutableStateOf<List<TallyMargParsedVoucher>>(emptyList()) }
    var showConflictResolutionModal by remember { mutableStateOf(false) }
    var xmlExportModalContent by remember { mutableStateOf<String?>(null) }

    val allVouchers by viewModel.vouchersState.collectAsState()

    LaunchedEffect(selectedLedgerForStatement) {
        selectedLedgerForStatement?.let { ledger ->
            viewModel.loadLedgerTransactions(ledger.id) { list ->
                ledgerTransactions = list
            }
        }
    }

    val trialBalance by viewModel.trialBalanceState.collectAsState()
    val balanceSheet by viewModel.balanceSheetState.collectAsState()
    val profitAndLoss by viewModel.profitAndLossState.collectAsState()
    val gstSummary by viewModel.gstSummaryState.collectAsState()
    val totalSales by viewModel.totalSalesState.collectAsState()
    val totalPurchases by viewModel.totalPurchasesState.collectAsState()
    val inventoryItems by viewModel.inventoryState.collectAsState()
    val negativeStockItems by viewModel.negativeStockItemsState.collectAsState()
    // Party ledgers carry the buyer's GSTIN — the only place it is captured today — and
    // that is what decides B2B vs B2C.
    val allLedgersForGst by viewModel.ledgersState.collectAsState()

    val filteredTrialBalance = trialBalance.filter { item ->
        val matchesQuery = item.name.contains(ledgerSearchQuery, ignoreCase = true) ||
                item.groupName.contains(ledgerSearchQuery, ignoreCase = true) ||
                item.currentBalance.toString().contains(ledgerSearchQuery)

        val matchesCategory = when (ledgerCategoryFilter) {
            "ALL" -> true
            else -> item.category.name == ledgerCategoryFilter
        }

        matchesQuery && matchesCategory
    }

    val totalDebit = trialBalance.sumOf { it.totalDebit }
    val totalCredit = trialBalance.sumOf { it.totalCredit }
    val isDoubleEntryBalanced = Math.abs(totalDebit - totalCredit) < 0.01

    val totalOutputGst = gstSummary.totalOutputCgst + gstSummary.totalOutputSgst + gstSummary.totalOutputIgst
    val totalInputGst = gstSummary.totalInputCgst + gstSummary.totalInputSgst + gstSummary.totalInputIgst
    val netGstPayable = totalOutputGst - totalInputGst

    // Financial Metrics: GP, NP, Expenses, Cash Flow
    val totalExpenses = trialBalance.filter { it.category == LedgerCategory.EXPENSE }.sumOf { Math.abs(it.currentBalance) }
    val grossProfit = totalSales - totalPurchases
    val grossProfitMargin = if (totalSales > 0) (grossProfit / totalSales) * 100.0 else 0.0
    val netProfit = grossProfit - totalExpenses
    val netProfitMargin = if (totalSales > 0) (netProfit / totalSales) * 100.0 else 0.0

    val cashInflows = allVouchers.filter { it.voucherType == VoucherType.SALES || it.voucherType == VoucherType.RECEIPT }.sumOf { it.totalAmount }
    val cashOutflows = allVouchers.filter { it.voucherType == VoucherType.PURCHASE || it.voucherType == VoucherType.PAYMENT }.sumOf { it.totalAmount }
    val netCashFlow = cashInflows - cashOutflows

    // Analytics tab data: real, date-ranged, straight from the ledger.
    val analyticsRange = remember(dateRangeState) { dateRangeState.toEpochMillisRange() }
    LaunchedEffect(analyticsRange) {
        viewModel.setAnalyticsDateRange(analyticsRange.first, analyticsRange.second)
    }
    val monthlyPnlRows by viewModel.monthlyPnlState.collectAsState()
    val cashBankTrend by viewModel.cashBankTrendState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("reports_screen_scroll"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                text = "Financial Reports & Statutory",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Date-Range Selector Bar
            DynamicDateRangeSelector(
                state = dateRangeState,
                onStateChange = { dateRangeState = it }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Report Selector Tabs
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedReportTab,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                reportTitles.forEachIndexed { index, title ->
                    if (index == 10 && !user.enableInventory) return@forEachIndexed
                    Tab(
                        selected = selectedReportTab == index,
                        onClick = { selectedReportTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedReportTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedReportTab == index) RoyalPurplePrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        when (selectedReportTab) {
            // TAB 0: ANALYTICS & TRENDS
            0 -> {
                item {
                    AnalyticsSummaryChartCard(
                        monthlyPnlRows = monthlyPnlRows,
                        rangeStartMillis = analyticsRange.first,
                        rangeEndMillis = analyticsRange.second,
                        trialBalance = trialBalance,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                item {
                    CashBankTrendChartCard(
                        points = cashBankTrend,
                        hasAnyActivity = allVouchers.isNotEmpty(),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }

            // TAB 1: TRIAL BALANCE
            1 -> {
                item {
                    // Double Entry Verification Card & Formal Report Trigger
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isDoubleEntryBalanced) AccountingGreen.copy(alpha = 0.12f) else AccountingRed.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("trial_balance_status_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isDoubleEntryBalanced) "BALANCED LEDGER STATE ✓" else "UNBALANCED WARNING ⚠️",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDoubleEntryBalanced) AccountingGreen else AccountingRed
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isDoubleEntryBalanced)
                                            "Debit allocations equal credit sources (${IndianFormatter.formatRupee(totalDebit)}). Accounting equilibrium verified."
                                        else
                                            "Difference detected between total debit and credit accounts in ledger database.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Button(
                                    onClick = { showTrialBalanceReportModal = true },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    modifier = Modifier.testTag("generate_trial_balance_report_btn")
                                ) {
                                    Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Full Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Global Search & Category Filters + Export CSV
                item {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = ledgerSearchQuery,
                                onValueChange = { ledgerSearchQuery = it },
                                placeholder = { Text("Search ledger, group, amount...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f).testTag("ledger_search_query_input")
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    val csv = CsvExporter.generateLedgerSummaryCsv(filteredTrialBalance)
                                    CsvExporter.shareCsvFile(context, "Ledger_Summary_${System.currentTimeMillis()}.csv", csv)
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.height(48.dp).testTag("export_ledger_csv_btn")
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("ALL" to "All", "ASSET" to "Assets", "LIABILITY" to "Liabilities", "REVENUE" to "Revenue", "EXPENSE" to "Expenses").forEach { (key, label) ->
                                FilterChip(
                                    selected = ledgerCategoryFilter == key,
                                    onClick = { ledgerCategoryFilter = key },
                                    label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RoyalPurplePrimary,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }

                // Header Row
                item {
                    Card(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                        colors = CardDefaults.cardColors(containerColor = LavenderContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Account Aggregates", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                            Text("Allocations (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                            Text("Sources (₹)", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (filteredTrialBalance.isEmpty()) {
                    item {
                        Text(
                            text = "No matching ledgers found.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(filteredTrialBalance) { item ->
                        Card(
                            onClick = { selectedLedgerForStatement = item },
                            shape = RoundedCornerShape(0.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1.5f)) {
                                        Text(text = item.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                        Text(text = "${item.groupName} • Tap for Account Statement", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        text = if (item.totalDebit > 0) IndianFormatter.formatRupee(item.totalDebit, false) else "-",
                                        style = MonospaceTabularTextStyle,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (item.totalCredit > 0) IndianFormatter.formatRupee(item.totalCredit, false) else "-",
                                        style = MonospaceTabularTextStyle,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                // Trial Balance Total Footer
                item {
                    Card(
                        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkPurpleVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOTAL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.5f))
                            Text(
                                text = IndianFormatter.formatRupee(totalDebit, false),
                                style = MonospaceTabularTextStyle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = IndianFormatter.formatRupee(totalCredit, false),
                                style = MonospaceTabularTextStyle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // TAB 2: AUDIT TRAIL & GST COMPLIANCE SUMMARY
            2 -> {
                val zeroValueVouchers = allVouchers.filter { it.totalAmount <= 0.0 }
                val missingPartyVouchers = allVouchers.filter { it.partyName.isBlank() || it.partyName.equals("Cash", ignoreCase = true) && it.totalAmount > 50000.0 }
                val missingNarrationVouchers = allVouchers.filter { it.narration.isBlank() }
                val highValueMissingGstin = allVouchers.filter { it.totalAmount > 50000.0 && it.gstAmount > 0.0 && user.gstin.isBlank() }

                val totalAnomalies = zeroValueVouchers.size + missingPartyVouchers.size + missingNarrationVouchers.size + highValueMissingGstin.size
                val complianceScore = if (allVouchers.isEmpty()) 100 else (((allVouchers.size - totalAnomalies.coerceAtMost(allVouchers.size)).toDouble() / allVouchers.size) * 100).toInt()

                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("audit_trail_summary_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (complianceScore >= 90) Icons.Default.VerifiedUser else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (complianceScore >= 90) AccountingGreen else AccountingRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "GST AUDIT COMPLIANCE SCORE",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (complianceScore >= 90) AccountingGreen.copy(alpha = 0.15f) else AccountingRed.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "$complianceScore% COMPLIANT",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (complianceScore >= 90) AccountingGreen else AccountingRed,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Automated pre-filing audit engine analyzing voucher completeness, narration logs, and GST tax rules.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Total Audited Vouchers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${allVouchers.size} Entries", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Compliance Anomalies", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$totalAnomalies Issues", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (totalAnomalies == 0) AccountingGreen else AccountingRed)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Audit Issue Category 1: Zero Value Vouchers
                if (zeroValueVouchers.isNotEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = AccountingRed.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("⚠️ Zero / Negative Amount Vouchers (${zeroValueVouchers.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                                Text("Vouchers with ₹0.0 value may cause GSTR-1 verification rejection.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                zeroValueVouchers.forEach { v ->
                                    Text("• Voucher #${v.voucherNo} (${v.partyName}) - ${v.voucherType.name}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // Audit Issue Category 2: Missing Narrations
                if (missingNarrationVouchers.isNotEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("📝 Vouchers Missing Audit Narration (${missingNarrationVouchers.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Text("Indian Tax Law Section 44AB recommends clear audit narrations for all accounting vouchers.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                missingNarrationVouchers.take(5).forEach { v ->
                                    Text("• #${v.voucherNo} (${v.partyName}) - ${IndianFormatter.formatRupee(v.totalAmount)}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Audit Success Banner
                if (totalAnomalies == 0) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = AccountingGreen.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccountingGreen, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("All Vouchers Audit Ready!", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                    Text("No missing fields, tax mismatches, or zero-value anomalies found.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: PROFIT & LOSS (GP & NP Breakdown)
            3 -> {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Statement of Profit & Loss", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            ExportDataButton(
                                viewModel = viewModel,
                                label = "Export P&L Data"
                            )
                        }

                        TallyProfitAndLossView(
                            pnl = profitAndLoss,
                            user = user,
                            periodLabel = dateRangeState.displayLabel,
                            onSelectLedger = { ledgerId, _ ->
                                selectedLedgerForStatement = trialBalance.firstOrNull { it.id == ledgerId }
                            }
                        )
                    }
                }
            }

            // TAB 4: REAL-TIME BALANCE SHEET STATEMENT
            4 -> {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Real-Time Balance Sheet", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            ExportDataButton(
                                viewModel = viewModel,
                                label = "Export B/S Data"
                            )
                        }

                        TallyBalanceSheetView(
                            sheet = balanceSheet,
                            user = user,
                            asOnLabel = dateRangeState.displayLabel,
                            onSelectLedger = { ledgerId, _ ->
                                selectedLedgerForStatement = trialBalance.firstOrNull { it.id == ledgerId }
                            }
                        )
                    }
                }
            }

            // TAB 5: GSTR-1 SUMMARY REPORT VIEW (B2B, B2C, HSN Breakdown)
            5 -> {
                // Date-filtered (H10): this read every voucher ever posted while the
                // heading claimed to cover the selected period, so a "return" for one
                // month silently contained the whole book.
                val (gstFrom, gstTo) = dateRangeState.toEpochMillisRange()
                val outboundSales = allVouchers.filter {
                    it.voucherType == VoucherType.SALES && it.date in gstFrom..gstTo
                }

                // One classifier, and it reads the BUYER's GSTIN off their ledger. The old
                // test was `partyName.contains("GSTIN") || user.gstin.isNotBlank()` — the
                // second clause is the SELLER's registration, so once the business had a
                // GSTIN every sale it made was classified B2B and Table 7 was empty (H8).
                fun buyerGstin(v: VoucherEntity): String? =
                    allLedgersForGst.firstOrNull { it.name.equals(v.partyName, ignoreCase = true) }?.gstin

                val classified = outboundSales.groupBy {
                    GstClassifier.classify(buyerGstin(it), it.isInterstate, it.totalAmount)
                }
                val b2bSales = classified[SupplyCategory.B2B].orEmpty()
                val b2cLarge = classified[SupplyCategory.B2CL].orEmpty()
                val b2cSmall = classified[SupplyCategory.B2CS].orEmpty()

                val totalOutboundVal = outboundSales.sumOf { it.totalAmount }
                val totalOutboundTax = outboundSales.sumOf { it.gstAmount }

                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("gstr1_summary_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("GSTR-1 SUMMARY REPORT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary, letterSpacing = 1.sp)
                                    Text("Outbound sales vouchers for ${dateRangeState.displayLabel}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Export GSTR-1 Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val csv = CsvExporter.generateGstr1Csv(
                                            outboundSales,
                                            user,
                                            // Without this the CSV classifies every invoice
                                            // B2C while the card above it says B2B — the same
                                            // two-surfaces-disagree failure H25 exists to stop.
                                            buyerGstins = allLedgersForGst
                                                .filter { it.gstin.isNotBlank() }
                                                .associate { it.name.lowercase() to it.gstin }
                                        )
                                        CsvExporter.shareCsvFile(context, "GSTR1_Summary_${System.currentTimeMillis()}.csv", csv)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).testTag("export_gstr1_csv_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val reportText = "GSTR-1 SUMMARY REPORT\nBusiness Name: ${user.businessName}\nGSTIN: ${user.gstin}\nTotal Outbound Sales Invoices: ${outboundSales.size}\nTotal Sales Amount: ₹${totalOutboundVal}\nTotal GST Liability: ₹${totalOutboundTax}\n\n1. B2B Invoices: ${b2bSales.size} Invoices (₹${b2bSales.sumOf { it.totalAmount }})\n2. B2C Large: ${b2cLarge.size} Invoices (₹${b2cLarge.sumOf { it.totalAmount }})\n3. B2C Small: ${b2cSmall.size} Invoices (₹${b2cSmall.sumOf { it.totalAmount }})"
                                        // .txt, not .pdf: this writes plain text and shares it as text/plain. The
                                        // .pdf name meant no PDF viewer could open the file it produced.
                                        CsvExporter.shareTextOrPdfReport(context, "GSTR1_Summary_${System.currentTimeMillis()}.txt", reportText)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).testTag("export_gstr1_pdf_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export Text Report", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // GSTR-1 Overview Tiles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = LavenderContainer,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Total Supplies", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                        Text("${outboundSales.size} Invoices", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(IndianFormatter.formatRupee(totalOutboundVal), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = AccountingGreen.copy(alpha = 0.12f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Total Tax Liability", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                        Text(IndianFormatter.formatRupee(totalOutboundTax), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                        Text("CGST+SGST+IGST", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(16.dp))

                            // Categorized Sections
                            Text("1. B2B Invoices (Taxable Supplies to Registered Persons)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            MonetaryRow(label = "B2B Supplies Count: ${b2bSales.size} Invoices", amount = b2bSales.sumOf { it.totalAmount }, amountColor = RoyalPurplePrimary)

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("2. B2C Large Invoices (Interstate Supplies > ₹1 Lakh)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            MonetaryRow(label = "B2C Large Count: ${b2cLarge.size} Invoices", amount = b2cLarge.sumOf { it.totalAmount })

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("3. B2C Small Invoices (Intrastate or < ₹2.5 Lakhs)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            MonetaryRow(label = "B2C Small Count: ${b2cSmall.size} Invoices", amount = b2cSmall.sumOf { it.totalAmount })

                            Spacer(modifier = Modifier.height(16.dp))
                            Text("4. Outbound Tax Invoices Summarized by GST Rate", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Per-slab figures are not available and are no longer estimated.
                            // This section used to split the real taxable total across the four
                            // slabs by fixed invented proportions -- 10% at 5%, 20% at 12%, 60%
                            // at 18% and 10% at 28% -- and label the result "For Quick Filing".
                            // Vouchers store one blended GST amount, not per-line rates, so the
                            // slab split cannot be derived from what the app records.
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Not available", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "A per-slab breakdown (5% / 12% / 18% / 28%) needs the tax rate recorded against each invoice line. Vouchers currently store a single GST amount per invoice, so these figures cannot be produced without guessing.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text("5. HSN / SAC Summary Breakdown", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Also not available. This used to list three invented commodity
                            // codes -- HSN 8471 "Computers & IT Hardware", HSN 8517 "Telecom &
                            // Electronics" and SAC 9983 "Professional Consultations" -- and
                            // apportion real sales across them 60/25/15, describing goods the
                            // user may never have traded in.
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Not available", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "An HSN/SAC summary needs the commodity code captured on each invoice line. Vouchers are recorded at invoice level only, so there is no line-item data to summarise here.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TAB 6: TALLY & MARG XML EXPORT / IMPORT WITH CONFLICT RESOLUTION
            6 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("tally_marg_xml_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("TALLY & MARG XML INTEROPERABILITY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary, letterSpacing = 1.sp)
                            Text("Import or export accounting vouchers directly to Tally Prime or Marg ERP XML formats.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))

                            // Export Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        xmlExportModalContent = TallyMargXmlUtil.exportToTallyXml(allVouchers, user)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("export_tally_xml_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export Tally XML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        xmlExportModalContent = TallyMargXmlUtil.exportToMargXml(allVouchers, user)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("export_marg_xml_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export Marg XML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(16.dp))

                            // Import Section
                            Text("Import XML File Content (Tally or Marg):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = xmlImportText,
                                onValueChange = { xmlImportText = it },
                                placeholder = { Text("Paste <ENVELOPE> or <MARG_DATA> XML snippet here...") },
                                modifier = Modifier.fillMaxWidth().height(120.dp).testTag("xml_import_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    if (xmlImportText.isNotBlank()) {
                                        parsedXmlVouchers = TallyMargXmlUtil.parseTallyOrMargXml(xmlImportText)
                                        if (parsedXmlVouchers.isNotEmpty()) {
                                            showConflictResolutionModal = true
                                        }
                                    }
                                },
                                enabled = xmlImportText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("parse_xml_import_btn")
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Parse & Reconcile XML Records", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // TAB 7: CASH FLOW STATEMENT
            7 -> {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cash Flow Statement", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            ExportDataButton(
                                viewModel = viewModel,
                                label = "Export Cash Flow"
                            )
                        }

                        HierarchicalFinancialStatement(
                            statementType = StatementType.CASH_FLOW,
                            user = user,
                            vouchers = allVouchers,
                            trialBalance = trialBalance,
                            viewModel = viewModel
                        )
                    }
                }
            }

            // TAB 8: VISUAL CHART OF ACCOUNTS & LEDGER SUMMARY LIST
            8 -> {
                item {
                    LedgerSummaryList(
                        ledgers = trialBalance,
                        onLedgerClick = { selectedLedgerForStatement = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                items(trialBalance) { ledger ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = ledger.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                    Text(text = "${ledger.groupName} • ${ledger.category.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = IndianFormatter.formatRupee(Math.abs(ledger.currentBalance)),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // The progress bar that used to sit here measured every ledger
                            // against a hardcoded `benchmarkLimit = 100000.0` and captioned it
                            // "% of ₹1L Target" with a "Normal Budget" / "High Volume / Limit
                            // Alert" verdict. The user never set a ₹1 lakh budget on anything,
                            // so both the bar and its verdict were invented. Removed rather
                            // than replaced -- the app has no budget feature to draw from.
                        }
                    }
                }
            }

            // TAB 9: STATUTORY GST COMPLIANCE
            9 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("GSTR-3B SUMMARY REPORT", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("Consolidated tax liabilities and input tax credits", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Export GSTR-3B Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val csv = CsvExporter.generateGstr3bCsv(allVouchers, gstSummary, user)
                                        CsvExporter.shareCsvFile(context, "GSTR3B_Summary_${System.currentTimeMillis()}.csv", csv)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).testTag("export_gstr3b_csv_btn")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val reportText = "GSTR-3B SUMMARY REPORT\nBusiness Name: ${user.businessName}\nGSTIN: ${user.gstin}\n\n3.1 Outward Taxable Supplies Output: ₹${totalOutputGst}\n4. Eligible Input Tax Credit: ₹${totalInputGst}\nNet GST Balance: ₹${totalInputGst - totalOutputGst}"
                                        CsvExporter.shareTextOrPdfReport(context, "GSTR3B_Summary_${System.currentTimeMillis()}.txt", reportText)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).testTag("export_gstr3b_pdf_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export Text Report", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // AUTOMATED ON-DEVICE AI GST & WORKMANAGER AGGREGATION CARD
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RoyalPurplePrimary, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("AUTOMATED ON-DEVICE AI GST ENGINE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Extracts Jama/Udhar notes with AI, splits CGST/SGST/IGST, and generates GSTR-1 & GSTR-3B JSON files in background.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Starts empty. This field used to open pre-filled with a
                                    // complete invented transaction -- party "Sharma ji kirana",
                                    // ₹15,000, 18% tax, state 07, invoice
                                    // INV202608110001928374 -- which parsed into a plausible
                                    // GST entry the user never entered. The example now lives
                                    // in the placeholder, which cannot be submitted.
                                    var merchantNoteText by remember { mutableStateOf("") }
                                    var parsedResultNote by remember { mutableStateOf<com.example.ai.LocalAiReconciliationEngine.ParsedMerchantNote?>(null) }
                                    var autoExportStatus by remember { mutableStateOf(com.example.data.gst.GstAutomationEngine.getLastExportStatus(context)) }
                                    val coroutineScope = rememberCoroutineScope()

                                    OutlinedTextField(
                                        value = merchantNoteText,
                                        onValueChange = { merchantNoteText = it },
                                        label = { Text("Raw Merchant Note (Jama/Udhar)", fontSize = 10.sp) },
                                        placeholder = { Text("e.g. Sharma ji kirana - Rs.15000 with 18% tax state 07", fontSize = 10.sp) },
                                        modifier = Modifier.fillMaxWidth().testTag("raw_merchant_note_input"),
                                        shape = RoundedCornerShape(10.dp),
                                        singleLine = true
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = {
                                                parsedResultNote = com.example.ai.LocalAiReconciliationEngine.reconcileAndCleanNote(merchantNoteText)
                                            },
                                            enabled = merchantNoteText.isNotBlank(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                                            modifier = Modifier.weight(1f).testTag("ai_parse_note_btn")
                                        ) {
                                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Parse AI Note", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    autoExportStatus = "Running automated GST aggregation & JSON export worker..."
                                                    // Ran the export TWICE: once via the worker and
                                                    // again inline, both writing the same file.
                                                    val res = com.example.data.gst.GstAutomationEngine.executeAutomatedGstExport(context)
                                                    autoExportStatus = if (res.isSuccess) {
                                                        // Read back what the engine recorded, which carries
                                                        // any "N invoices missing from Table 12" warning.
                                                        // An unconditional SUCCESS here made an incomplete
                                                        // return look clean.
                                                        com.example.data.gst.GstAutomationEngine.getLastExportStatus(context)
                                                    } else {
                                                        "FAILED: ${res.exceptionOrNull()?.message}"
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                            modifier = Modifier.weight(1f).testTag("trigger_auto_gst_worker_btn")
                                        ) {
                                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Auto Export GST", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    parsedResultNote?.let { note ->
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            // Anything the parser did not actually find in the
                                            // note now reads "not detected". These lines used to
                                            // print the engine's invented defaults -- customer
                                            // "Cash Customer", rate 18%, POS "07", invoice
                                            // "INV-001" -- under an "AI Extracted" heading.
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Text(
                                                    text = "Extracted Customer: ${note.customerName ?: "not detected"}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = RoyalPurplePrimary
                                                )
                                                Text(
                                                    text = "Taxable Value: ${note.taxableValue?.let { "₹$it" } ?: "not detected"}" +
                                                        " | Rate: ${note.taxRate?.let { "$it%" } ?: "not detected"}" +
                                                        " | POS: ${note.stateCode ?: "not detected"}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Cleaned inum (max 16 chars): ${note.invoiceNumber ?: "not detected"}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AccountingGreen
                                                )
                                            }
                                        }
                                    }

                                    if (autoExportStatus.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = autoExportStatus,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (autoExportStatus.contains("SUCCESS")) AccountingGreen else RoyalPurplePrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // "Opening GST Ledger Balance" was hardcoded to 0.0 and the row below
                            // it ("Balance after ITC Offset") simply restated the ITC total on
                            // that assumption. The app carries no opening balance for the GST
                            // duty ledgers, so asserting zero was a claim it could not support.
                            // Both rows removed; the ITC and liability figures below are real.
                            MonetaryRow(label = "+ Input Tax Credit (Purchases ITC)", amount = totalInputGst, amountColor = AccountingGreen)

                            Spacer(modifier = Modifier.height(12.dp))
                            MonetaryRow(label = "- Output Tax Liability (Sales Tax)", amount = totalOutputGst, amountColor = AccountingRed)
                            Divider(modifier = Modifier.padding(vertical = 10.dp))

                            val netRunningGstBal = totalInputGst - totalOutputGst
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (netRunningGstBal >= 0) AccountingGreen.copy(alpha = 0.15f) else AccountingRed.copy(alpha = 0.15f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = if (netRunningGstBal >= 0) "NET GST RUNNING BALANCE (ITC CREDIT SURPLUS)" else "NET GST RUNNING BALANCE (TAX PAYABLE TO GOVT)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (netRunningGstBal >= 0) AccountingGreen else AccountingRed
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = IndianFormatter.formatRupee(Math.abs(netRunningGstBal)),
                                        style = MonospaceTabularTextStyle.copy(fontSize = 22.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = if (netRunningGstBal >= 0) AccountingGreen else AccountingRed
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (netRunningGstBal >= 0) "ITC Balance = ₹${IndianFormatter.formatRupee(totalInputGst)} - Tax ₹${IndianFormatter.formatRupee(totalOutputGst)} = ₹${IndianFormatter.formatRupee(netRunningGstBal)}"
                                        else "Liability = Tax ₹${IndianFormatter.formatRupee(totalOutputGst)} - ITC ₹${IndianFormatter.formatRupee(totalInputGst)} = ₹${IndianFormatter.formatRupee(Math.abs(netRunningGstBal))} Payable",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TAB 10: STOCK STATUS REPORT
            10 -> {
                // Negative stock is allowed (a business genuinely sells before keying in
                // the purchase, and Tally and Vyapar both permit it) but it must not be
                // silent — nothing anywhere reported it before.
                if (negativeStockItems.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AccountingRed.copy(alpha = 0.10f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${negativeStockItems.size} item(s) show negative stock",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccountingRed
                                )
                                Text(
                                    "More has been sold than recorded as purchased. Record the " +
                                        "missing purchase, or set the item's opening quantity.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                negativeStockItems.forEach {
                                    Text(
                                        "• ${it.name}: ${it.stockQty} ${it.unit}",
                                        fontSize = 11.sp,
                                        color = AccountingRed
                                    )
                                }
                            }
                        }
                    }
                }
                items(inventoryItems) { item ->
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "HSN: ${item.hsnCode} • GST ${item.gstRate}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (item.stockQty < 0) AccountingRed.copy(alpha = 0.15f) else LavenderContainer
                                ) {
                                    Text(
                                        text = "${item.stockQty.toInt()} ${item.unit}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.stockQty < 0) AccountingRed else RoyalPurplePrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            MonetaryRow(label = "Selling Price / Unit", amount = item.sellingPrice)
                            // Valued at cost, not selling price. AS-2 / Ind AS 2 require the
                            // lower of cost and NRV; using sellingPrice booked unrealised
                            // margin into the stated asset value.
                            MonetaryRow(label = "Total Stock Valuation (at cost)", amount = item.stockQty * item.avgCostPrice, amountColor = RoyalPurplePrimary)
                        }
                    }
                }
            }
        }
    }

    // Tally-Style Ledger Statement Dialog
    selectedLedgerForStatement?.let { ledger ->
        AlertDialog(
            onDismissRequest = { selectedLedgerForStatement = null },
            title = {
                Column {
                    Text(
                        text = "Ledger: ${ledger.name}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary
                    )
                    Text(
                        text = "Group: ${ledger.groupName} • Closing Bal: ${IndianFormatter.formatRupee(Math.abs(ledger.currentBalance))}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        text = "Tap any transaction row to View, Edit, Delete or Print PDF Invoice",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Statement Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LavenderContainer, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Date & Voucher", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                        Text("Party / Particulars", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                        Text("Debit (Dr)", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text("Credit (Cr)", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (ledgerTransactions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No journal entries for this ledger yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(ledgerTransactions) { tx ->
                                Card(
                                    onClick = {
                                        val match = allVouchers.firstOrNull { it.id == tx.voucherId }
                                        if (match != null) {
                                            editingVoucherInStatement = match
                                            editPartyName = match.partyName
                                            editAmountText = match.totalAmount.toString()
                                            editNarration = match.narration
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1.2f)) {
                                            Text(IndianFormatter.formatDate(tx.date), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text("${tx.voucherType.name} #${tx.voucherNo}", fontSize = 10.sp, color = RoyalPurplePrimary)
                                        }
                                        Text(
                                            text = tx.partyName.ifBlank { "Particulars" },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1.5f)
                                        )
                                        Text(
                                            text = if (tx.debitAmount > 0) IndianFormatter.formatRupee(tx.debitAmount, false) else "-",
                                            fontSize = 11.sp,
                                            style = MonospaceTabularTextStyle,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = if (tx.creditAmount > 0) IndianFormatter.formatRupee(tx.creditAmount, false) else "-",
                                            fontSize = 11.sp,
                                            style = MonospaceTabularTextStyle,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            PdfInvoiceGenerator.generateAndShareLedgerPdf(
                                context = context,
                                ledgerName = selectedLedgerForStatement?.name ?: "Ledger Account",
                                groupName = selectedLedgerForStatement?.groupName ?: "General",
                                currentBalance = selectedLedgerForStatement?.currentBalance ?: 0.0,
                                transactions = ledgerTransactions,
                                user = user
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export PDF", fontSize = 12.sp)
                    }
                    TextButton(onClick = { selectedLedgerForStatement = null }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    // Voucher Management Modal in Statement
    editingVoucherInStatement?.let { voucher ->
        AlertDialog(
            onDismissRequest = { editingVoucherInStatement = null },
            title = {
                Text(
                    text = "Voucher #${voucher.voucherNo} (${voucher.voucherType.name})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editPartyName,
                        onValueChange = { editPartyName = it },
                        label = { Text("Party Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAmountText,
                        onValueChange = { editAmountText = it },
                        label = { Text("Total Amount (₹)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editNarration,
                        onValueChange = { editNarration = it },
                        label = { Text("Narration / Notes") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            PdfInvoiceGenerator.generateAndSharePdf(context, voucher, user)
                        }
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PDF")
                    }
                    Button(
                        onClick = {
                            viewModel.updateVoucher(
                                voucherId = voucher.id,
                                type = voucher.voucherType,
                                partyName = editPartyName,
                                amountText = editAmountText,
                                // Derived from the voucher's own figures. The literal
                                // "18"-or-"0" here rewrote every 5%/12%/28% invoice to 18%
                                // on save. (This dialog did already preserve isInterstate.)
                                gstRateText = GstCalculationService
                                    .deriveGstRate(voucher.totalAmount, voucher.gstAmount)
                                    .let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() },
                                isInterstate = voucher.isInterstate,
                                narration = editNarration,
                                dateMillis = voucher.date,
                                tags = voucher.tags
                            )
                            editingVoucherInStatement = null
                            selectedLedgerForStatement?.let { l ->
                                viewModel.loadLedgerTransactions(l.id) { list ->
                                    ledgerTransactions = list
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                    ) {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteVoucher(voucher.id)
                            editingVoucherInStatement = null
                            selectedLedgerForStatement?.let { l ->
                                viewModel.loadLedgerTransactions(l.id) { list ->
                                    ledgerTransactions = list
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccountingRed)
                    ) {
                        Text("Delete")
                    }
                    TextButton(onClick = { editingVoucherInStatement = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // Tally/Marg XML Export Payload Preview Modal
    xmlExportModalContent?.let { payload ->
        AlertDialog(
            onDismissRequest = { xmlExportModalContent = null },
            title = { Text("Tally / Marg XML Export Payload", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copy or save this standard XML payload for importing into Tally Prime / Marg ERP:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = payload,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("TallyMargXML", payload)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "XML payload copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                        xmlExportModalContent = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Copy XML Payload")
                }
            },
            dismissButton = {
                TextButton(onClick = { xmlExportModalContent = null }) {
                    Text("Close")
                }
            }
        )
    }

    // XML Import Reconciliation & Conflict Resolution Modal
    if (showConflictResolutionModal && parsedXmlVouchers.isNotEmpty()) {
        val conflictList = remember(parsedXmlVouchers) {
            parsedXmlVouchers.map { imported ->
                val existingMatch = allVouchers.find { existing ->
                    existing.partyName.equals(imported.partyName, ignoreCase = true) &&
                            Math.abs(existing.totalAmount - imported.amount) < 1.0
                }
                ImportConflictItem(
                    importedVoucher = imported,
                    existingVoucher = existingMatch,
                    chosenAction = if (existingMatch != null) ConflictAction.SKIP else ConflictAction.MERGE
                )
            }.toMutableStateList()
        }

        AlertDialog(
            onDismissRequest = { showConflictResolutionModal = false },
            title = { Text("Reconcile XML Import Records (${parsedXmlVouchers.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Review parsed vouchers and resolve duplicate conflict actions:", fontSize = 12.sp)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(conflictList) { item ->
                            val imported = item.importedVoucher
                            val isDuplicate = item.existingVoucher != null

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDuplicate) AccountingRed.copy(alpha = 0.08f) else AccountingGreen.copy(alpha = 0.08f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("${imported.partyName} • ${imported.voucherType.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Date: ${imported.date} • Amt: ${IndianFormatter.formatRupee(imported.amount)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (isDuplicate) {
                                            Text("DUPLICATE MATCH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                                        } else {
                                            Text("NEW RECORD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                        }
                                    }

                                    if (isDuplicate) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            FilterChip(
                                                selected = item.chosenAction == ConflictAction.SKIP,
                                                onClick = { item.chosenAction = ConflictAction.SKIP },
                                                label = { Text("Skip Duplicate", fontSize = 10.sp) }
                                            )
                                            FilterChip(
                                                selected = item.chosenAction == ConflictAction.REPLACE,
                                                onClick = { item.chosenAction = ConflictAction.REPLACE },
                                                label = { Text("Replace", fontSize = 10.sp) }
                                            )
                                            FilterChip(
                                                selected = item.chosenAction == ConflictAction.MERGE,
                                                onClick = { item.chosenAction = ConflictAction.MERGE },
                                                label = { Text("Import as New", fontSize = 10.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        conflictList.filter { it.chosenAction != ConflictAction.SKIP }.forEach { item ->
                            val imported = item.importedVoucher
                            viewModel.addVoucher(
                                type = imported.voucherType,
                                partyName = imported.partyName,
                                amountText = imported.amount.toString(),
                                // Derived from the imported figures and read from the
                                // import itself. The "18"-or-"0" literal and a hardcoded
                                // false are the same guess that was removed from the edit
                                // dialogs — it silently rewrote every non-18% voucher and
                                // converted IGST imports to CGST+SGST.
                                gstRateText = GstCalculationService
                                    .deriveGstRate(imported.amount, imported.gstAmount)
                                    .let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() },
                                isInterstate = imported.isInterstate,
                                narration = "${imported.narration} [Source: ${imported.source}]"
                            )
                        }
                        android.widget.Toast.makeText(context, "Successfully reconciled & imported XML records!", android.widget.Toast.LENGTH_SHORT).show()
                        showConflictResolutionModal = false
                        xmlImportText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Commit Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConflictResolutionModal = false }) { Text("Cancel") }
            }
        )
    }

    // Voucher Line Item Breakdown Modal Overlay
    breakdownModalTitle?.let { title ->
        VoucherBreakdownModal(
            title = title,
            vouchers = breakdownModalVouchers,
            viewModel = viewModel,
            onDismiss = { breakdownModalTitle = null }
        )
    }

    if (showTrialBalanceReportModal) {
        com.example.ui.components.TrialBalanceReportModal(
            user = user,
            trialBalance = trialBalance,
            onDismiss = { showTrialBalanceReportModal = false }
        )
    }
}
