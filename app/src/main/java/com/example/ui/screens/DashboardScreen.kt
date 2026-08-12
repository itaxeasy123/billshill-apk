package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import com.example.ui.components.QuickActionsCarousel
import com.example.ui.components.SignaturePadDialog
import com.example.ui.components.QrCodeScannerDialog
import com.example.ui.components.LedgerManagementModal
import com.example.ui.components.CustomVoucherModal
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ArrowDropDown
import com.example.data.dao.MonthlyPnlRow
import com.example.data.model.LedgerCategory
import com.example.data.model.UserEntity
import com.example.data.model.VoucherEntity
import com.example.data.model.VoucherType
import com.example.ui.AccountingViewModel
import com.example.ui.components.MonetaryRow
import com.example.ui.theme.*
import com.example.utils.IndianFormatter

enum class FiscalYearOption(val label: String, val shortLabel: String) {
    ALL_TIME("All Time", "All Time"),
    FY_2026_27("FY 2026-27 (Apr '26 - Mar '27)", "FY 26-27"),
    FY_2025_26("FY 2025-26 (Apr '25 - Mar '26)", "FY 25-26"),
    FY_2024_25("FY 2024-25 (Apr '24 - Mar '25)", "FY 24-25"),
    FY_2023_24("FY 2023-24 (Apr '23 - Mar '24)", "FY 23-24")
}

fun getFiscalYearRange(fy: FiscalYearOption): Pair<Long, Long>? {
    val yearStart = when (fy) {
        FiscalYearOption.ALL_TIME -> return null
        FiscalYearOption.FY_2026_27 -> 2026
        FiscalYearOption.FY_2025_26 -> 2025
        FiscalYearOption.FY_2024_25 -> 2024
        FiscalYearOption.FY_2023_24 -> 2023
    }
    val calStart = java.util.Calendar.getInstance().apply {
        set(yearStart, java.util.Calendar.APRIL, 1, 0, 0, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val calEnd = java.util.Calendar.getInstance().apply {
        set(yearStart + 1, java.util.Calendar.MARCH, 31, 23, 59, 59)
        set(java.util.Calendar.MILLISECOND, 999)
    }
    return Pair(calStart.timeInMillis, calEnd.timeInMillis)
}

@Composable
fun DashboardScreen(
    viewModel: AccountingViewModel,
    user: UserEntity,
    onNavigateToVouchers: (VoucherType) -> Unit
) {
    val totalSales by viewModel.totalSalesState.collectAsState()
    val totalPurchases by viewModel.totalPurchasesState.collectAsState()
    val cashBalance by viewModel.cashBalanceState.collectAsState()
    val bankBalance by viewModel.bankBalanceState.collectAsState()
    val cashAndBankLedgers by viewModel.cashAndBankLedgersState.collectAsState()
    val gstSummary by viewModel.gstSummaryState.collectAsState()
    val vouchers by viewModel.vouchersState.collectAsState()
    val salesVouchers by viewModel.salesVouchersState.collectAsState()
    val purchaseVouchers by viewModel.purchaseVouchersState.collectAsState()
    val reconciliationDiscrepancies by viewModel.reconciliationDiscrepanciesState.collectAsState()
    val unresolvedDiscrepancyCount by viewModel.unresolvedDiscrepancyCountState.collectAsState()
    val isReconciling by viewModel.isReconcilingState.collectAsState()
    val autoReconciliationEnabled by viewModel.autoReconciliationEnabledState.collectAsState()
    val lastReconciliationTime by viewModel.lastReconciliationTimeState.collectAsState()
    // Real per-month revenue/expense buckets for the trend chart below, replacing the
    // hardcoded ratios that chart used to draw.
    val monthlyPnlRows by viewModel.monthlyPnlState.collectAsState()

    var selectedDashboardTab by remember { mutableStateOf(0) }
    val dashboardTabs = listOf("Overview", "Sales", "Purchase", "Petty Cash", "Scheduled Reminders", "Receipt & Payment", "Excel / PDF Import")

    var showCashBankDialog by remember { mutableStateOf(false) }
    var showContraModal by remember { mutableStateOf(false) }
    var showEasyEntryModal by remember { mutableStateOf(false) }
    var showLedgerManagementModal by remember { mutableStateOf(false) }
    var showCustomVoucherModal by remember { mutableStateOf(false) }
    var showSignaturePadDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var showGstFilingScheduleModal by remember { mutableStateOf(false) }
    var showGstCalculatorModal by remember { mutableStateOf(false) }
    var showReconciliationModal by remember { mutableStateOf(false) }
    var selectedFiscalYear by remember { mutableStateOf(FiscalYearOption.ALL_TIME) }
    val context = LocalContext.current

    // Hoisted out of the "Scheduled Reminders" tab so the list of alarms the user has
    // actually scheduled can be rendered below the form. It used to be declared inside
    // the form's own `item {}` block and was therefore unreachable from the list section,
    // which instead displayed one hardcoded card ("Anand Traders / ₹24,500 / Due
    // Tomorrow") to every user on every install.
    val reminderScheduledList = remember { mutableStateListOf<com.example.service.PaymentReminderItem>() }


    // Voucher calculations filtered by Fiscal Year
    val filteredVouchers = remember(vouchers, selectedFiscalYear) {
        val range = getFiscalYearRange(selectedFiscalYear)
        if (range == null) vouchers
        else vouchers.filter { it.date in range.first..range.second }
    }
    val filteredSalesVouchers = remember(salesVouchers, selectedFiscalYear) {
        val range = getFiscalYearRange(selectedFiscalYear)
        if (range == null) salesVouchers
        else salesVouchers.filter { it.date in range.first..range.second }
    }
    val filteredPurchaseVouchers = remember(purchaseVouchers, selectedFiscalYear) {
        val range = getFiscalYearRange(selectedFiscalYear)
        if (range == null) purchaseVouchers
        else purchaseVouchers.filter { it.date in range.first..range.second }
    }

    val totalFilteredSales = remember(filteredSalesVouchers) { filteredSalesVouchers.sumOf { it.totalAmount } }
    val totalFilteredPurchases = remember(filteredPurchaseVouchers) { filteredPurchaseVouchers.sumOf { it.totalAmount } }

    val filteredGstOutputTax = remember(filteredSalesVouchers) { filteredSalesVouchers.sumOf { it.gstAmount } }
    val filteredGstInputTax = remember(filteredPurchaseVouchers) { filteredPurchaseVouchers.sumOf { it.gstAmount } }
    val filteredNetGstLiability = filteredGstOutputTax - filteredGstInputTax

    val creditSalesVouchers = filteredSalesVouchers.filter { !it.partyName.lowercase().contains("cash") }
    val totalCreditSales = creditSalesVouchers.sumOf { it.totalAmount }
    val salesReturnVouchers = filteredVouchers.filter { it.voucherType == VoucherType.SALES_RETURN }
    val totalSalesReturns = salesReturnVouchers.sumOf { it.totalAmount }

    val creditPurchaseVouchers = filteredPurchaseVouchers.filter { !it.partyName.lowercase().contains("cash") }
    val totalCreditPurchases = creditPurchaseVouchers.sumOf { it.totalAmount }
    val purchaseReturnVouchers = filteredVouchers.filter { it.voucherType == VoucherType.PURCHASE_RETURN }
    val totalPurchaseReturns = purchaseReturnVouchers.sumOf { it.totalAmount }

    val receiptVouchers = filteredVouchers.filter { it.voucherType == VoucherType.RECEIPT }
    val totalReceipts = receiptVouchers.sumOf { it.totalAmount }
    val paymentVouchers = filteredVouchers.filter { it.voucherType == VoucherType.PAYMENT }
    val totalPayments = paymentVouchers.sumOf { it.totalAmount }
    val contraVouchers = filteredVouchers.filter { it.voucherType == VoucherType.CONTRA }
    val totalContra = contraVouchers.sumOf { it.totalAmount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("dashboard_screen_scroll"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Business Header with Avatar Badge
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${user.businessType.name} MODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = user.businessName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "GSTIN: ${user.gstin}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = LavenderContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = user.businessName.take(2).uppercase(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkPurpleVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Persistent Quick Balance Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RoyalPurplePrimary.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("persistent_quick_balance_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = RoyalPurplePrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = RoyalPurplePrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "QUICK BALANCE OVERVIEW",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurplePrimary,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Real-Time Liquidity",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Fiscal Year Picker Dropdown Chip
                        var showFyMenu by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = true,
                                onClick = { showFyMenu = true },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(selectedFiscalYear.shortLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LavenderContainer,
                                    selectedLabelColor = RoyalPurplePrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("fy_picker_chip")
                            )

                            DropdownMenu(
                                expanded = showFyMenu,
                                onDismissRequest = { showFyMenu = false }
                            ) {
                                FiscalYearOption.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                fontSize = 12.sp,
                                                fontWeight = if (selectedFiscalYear == option) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedFiscalYear == option) RoyalPurplePrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            selectedFiscalYear = option
                                            showFyMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { showCashBankDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            color = AccountingGreen.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccountingGreen.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).testTag("quick_balance_cash")
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Payments, contentDescription = null, tint = AccountingGreen, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cash", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = IndianFormatter.formatRupee(cashBalance),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cashBalance >= 0) AccountingGreen else AccountingRed
                                )
                            }
                        }

                        Surface(
                            onClick = { showCashBankDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            color = RoyalPurplePrimary.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RoyalPurplePrimary.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).testTag("quick_balance_bank")
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountBalance, contentDescription = null, tint = RoyalPurplePrimary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Bank", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = IndianFormatter.formatRupee(bankBalance),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (bankBalance >= 0) AccountingGreen else AccountingRed
                                )
                            }
                        }

                        val totalFunds = cashBalance + bankBalance
                        Surface(
                            onClick = { showCashBankDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            color = DeepPurpleSecondary.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepPurpleSecondary.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).testTag("quick_balance_total")
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = DeepPurpleSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Total Net", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = IndianFormatter.formatRupee(totalFunds),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (totalFunds >= 0) DeepPurpleSecondary else AccountingRed
                                )
                            }
                        }
                    }
                }
            }

            // GST Statutory Deadline & Filing Alert Card
            GstDeadlineNotificationCard(
                selectedFiscalYear = selectedFiscalYear,
                onViewFullSchedule = { showGstFilingScheduleModal = true },
                filingScheme = user.filingScheme,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Sub-dashboard Selector Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedDashboardTab,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                contentColor = RoyalPurplePrimary,
                divider = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                dashboardTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedDashboardTab == index,
                        onClick = { selectedDashboardTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedDashboardTab == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (selectedDashboardTab) {
            // TAB 0: OVERVIEW DASHBOARD
            0 -> {
                // Quick Actions Horizontal Carousel
                item {
                    QuickActionsCarousel(
                        onOpenQrScanner = { showQrScannerDialog = true },
                        onOpenReports = { selectedDashboardTab = 0 },
                        onOpenPnl = { selectedDashboardTab = 0 },
                        onOpenPrintInvoice = { showCashBankDialog = true },
                        onOpenGstSummary = { showCashBankDialog = true },
                        onOpenLedgerManager = { showLedgerManagementModal = true },
                        onOpenCustomVoucher = { showCustomVoucherModal = true },
                        onOpenGstCalculator = { showGstCalculatorModal = true },
                        onOpenReconciliation = { showReconciliationModal = true },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (unresolvedDiscrepancyCount > 0) {
                    item {
                        Card(
                            onClick = { showReconciliationModal = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AccountingRed.copy(alpha = 0.12f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccountingRed.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("dashboard_reconciliation_alert_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = AccountingRed,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.CompareArrows,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Reconciliation Discrepancy Alert",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccountingRed
                                        )
                                        Text(
                                            text = "$unresolvedDiscrepancyCount invoice vs payment discrepancy flag(s) identified",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                Button(
                                    onClick = { showReconciliationModal = true },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccountingRed),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Review", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Cash & Bank Balances Section
                item {
                    Column(modifier = Modifier.padding(bottom = 16.dp).testTag("frequently_used_section")) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cash & Bank",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Quick Access",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Cash Account Card
                            Surface(
                                onClick = { showCashBankDialog = true },
                                shape = RoundedCornerShape(18.dp),
                                color = RoyalPurplePrimary.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, RoyalPurplePrimary.copy(alpha = 0.3f)),
                                modifier = Modifier.testTag("frequently_used_card_cash")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Payments, contentDescription = null, tint = RoyalPurplePrimary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Cash", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                        Text("Petty Cash Ledger", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            // Bank Account Card
                            Surface(
                                onClick = { showCashBankDialog = true },
                                shape = RoundedCornerShape(18.dp),
                                color = LavenderContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkPurpleVariant.copy(alpha = 0.2f)),
                                modifier = Modifier.testTag("frequently_used_card_bank")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AccountBalance, contentDescription = null, tint = DarkPurpleVariant, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Bank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant)
                                        Text("Bank Book", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            // Payment Out Card
                            Surface(
                                onClick = { onNavigateToVouchers(VoucherType.PAYMENT) },
                                shape = RoundedCornerShape(18.dp),
                                color = AccountingRed.copy(alpha = 0.08f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccountingRed.copy(alpha = 0.2f)),
                                modifier = Modifier.testTag("frequently_used_card_payment")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = AccountingRed, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Payment", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                                        Text("Vendor Payouts", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            // Receipt In Card
                            Surface(
                                onClick = { onNavigateToVouchers(VoucherType.RECEIPT) },
                                shape = RoundedCornerShape(18.dp),
                                color = AccountingGreen.copy(alpha = 0.08f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccountingGreen.copy(alpha = 0.2f)),
                                modifier = Modifier.testTag("frequently_used_card_receipt")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = AccountingGreen, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Receipt", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                        Text("Customer Payments", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Cash & Bank Balance Card (Top Priority)
                item {
                    Card(
                        onClick = { showCashBankDialog = true },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.6f)),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, RoyalPurplePrimary.copy(alpha = 0.25f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cash_bank_balances_card")
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalance,
                                        contentDescription = null,
                                        tint = RoyalPurplePrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Cash & Bank",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                }
                                Text(
                                    text = "Tap for ledger ›",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepPurpleSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 1.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.AccountBalanceWallet,
                                                contentDescription = null,
                                                tint = AccountingGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Cash", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            IndianFormatter.formatRupee(cashBalance),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (cashBalance >= 0) AccountingGreen else AccountingRed
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 1.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.AccountBalance,
                                                contentDescription = null,
                                                tint = RoyalPurplePrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Bank", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            IndianFormatter.formatRupee(bankBalance),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (bankBalance >= 0) AccountingGreen else AccountingRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Quick Action Buttons
                item {
                    Text("Quick Accounting Actions", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(title = "Sales", icon = Icons.Default.ArrowUpward, backgroundColor = AccountingGreen, onClick = { onNavigateToVouchers(VoucherType.SALES) }, modifier = Modifier.weight(1f))
                        QuickActionButton(title = "Purchase", icon = Icons.Default.ArrowDownward, backgroundColor = RoyalPurplePrimary, onClick = { onNavigateToVouchers(VoucherType.PURCHASE) }, modifier = Modifier.weight(1f))
                        QuickActionButton(title = "Payment", icon = Icons.Default.Payments, backgroundColor = DeepPurpleSecondary, onClick = { onNavigateToVouchers(VoucherType.PAYMENT) }, modifier = Modifier.weight(1f))
                        QuickActionButton(title = "Receipt", icon = Icons.Default.Receipt, backgroundColor = DarkPurpleVariant, onClick = { onNavigateToVouchers(VoucherType.RECEIPT) }, modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showLedgerManagementModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("dashboard_manage_ledgers_btn")
                        ) {
                            Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Manage Ledgers", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showCustomVoucherModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepPurpleSecondary),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("dashboard_custom_expense_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Car / Loan / Expense", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Income Expense Chart & GST Summary Card
                item {
                    IncomeExpenseChartCard(
                        monthlyPnlRows = monthlyPnlRows,
                        totalIncome = totalFilteredSales,
                        totalExpense = totalFilteredPurchases
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RoyalPurplePrimary.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overview_gst_summary_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Receipt,
                                        contentDescription = null,
                                        tint = RoyalPurplePrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "GST Summary (${selectedFiscalYear.shortLabel})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = LavenderContainer
                                ) {
                                    Text(
                                        text = selectedFiscalYear.shortLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkPurpleVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            MonetaryRow(label = "GST Output Tax (Sales Tax)", amount = filteredGstOutputTax, amountColor = AccountingGreen)
                            MonetaryRow(label = "GST Input Tax Credit (ITC Purchases)", amount = filteredGstInputTax, amountColor = RoyalPurplePrimary)
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            MonetaryRow(
                                label = if (filteredNetGstLiability >= 0) "Net GST Tax Payable" else "Net GST Refundable",
                                amount = kotlin.math.abs(filteredNetGstLiability),
                                amountColor = if (filteredNetGstLiability >= 0) AccountingRed else AccountingGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            // TAB 1: DEDICATED SALES DASHBOARD
            1 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Sales Dashboard (${selectedFiscalYear.shortLabel})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                                Button(
                                    onClick = { onNavigateToVouchers(VoucherType.SALES) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("+ New Sale", fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            MonetaryRow(label = "Total Gross Sales", amount = totalFilteredSales, amountColor = AccountingGreen)
                            MonetaryRow(label = "Credit Sales (Debtors)", amount = totalCreditSales, amountColor = RoyalPurplePrimary)
                            MonetaryRow(label = "Sales Returns (Debit Notes)", amount = totalSalesReturns, amountColor = AccountingRed)
                            Divider(modifier = Modifier.padding(vertical = 10.dp))
                            MonetaryRow(label = "Net Realized Sales", amount = totalFilteredSales - totalSalesReturns, amountColor = AccountingGreen)
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                item {
                    Text("Sales & Sales Returns History (${selectedFiscalYear.shortLabel})", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val salesAndReturnList = filteredVouchers.filter { it.voucherType == VoucherType.SALES || it.voucherType == VoucherType.SALES_RETURN }
                if (salesAndReturnList.isEmpty()) {
                    item { Text("No Sales or Sales Return vouchers found for selected period.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(salesAndReturnList) { voucher ->
                        VoucherDashboardCard(voucher = voucher) {
                            // Opens the Vouchers screen, which owns the edit dialog. These four
                            // handlers used to assign edit state that nothing in this file ever
                            // read, so tapping a voucher card did nothing at all.
                            onNavigateToVouchers(voucher.voucherType)
                        }
                    }
                }
            }

            // TAB 2: DEDICATED PURCHASE DASHBOARD
            2 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Purchase Dashboard (${selectedFiscalYear.shortLabel})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Button(
                                    onClick = { onNavigateToVouchers(VoucherType.PURCHASE) },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("+ Purchase", fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            MonetaryRow(label = "Total Gross Purchases", amount = totalFilteredPurchases, amountColor = AccountingRed)
                            MonetaryRow(label = "Credit Purchases (Creditors)", amount = totalCreditPurchases, amountColor = RoyalPurplePrimary)
                            MonetaryRow(label = "Purchase Returns (Credit Notes)", amount = totalPurchaseReturns, amountColor = AccountingGreen)
                            Divider(modifier = Modifier.padding(vertical = 10.dp))
                            MonetaryRow(label = "Net Effective Purchases", amount = totalFilteredPurchases - totalPurchaseReturns, amountColor = AccountingRed)
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                item {
                    Text("Purchase & Return Vouchers (${selectedFiscalYear.shortLabel})", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val purchaseAndReturnList = filteredVouchers.filter { it.voucherType == VoucherType.PURCHASE || it.voucherType == VoucherType.PURCHASE_RETURN }
                if (purchaseAndReturnList.isEmpty()) {
                    item { Text("No Purchase vouchers recorded for selected period.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(purchaseAndReturnList) { voucher ->
                        VoucherDashboardCard(voucher = voucher) {
                            // Opens the Vouchers screen, which owns the edit dialog. These four
                            // handlers used to assign edit state that nothing in this file ever
                            // read, so tapping a voucher card did nothing at all.
                            onNavigateToVouchers(voucher.voucherType)
                        }
                    }
                }
            }

            // TAB 3: DEDICATED PETTY CASH DASHBOARD
            3 -> {
                item {
                    var pettyNarration by remember { mutableStateOf("") }
                    var pettyAmountText by remember { mutableStateOf("") }

                    val autoCategory = remember(pettyNarration) {
                        val lower = pettyNarration.lowercase()
                        when {
                            lower.contains("tea") || lower.contains("coffee") || lower.contains("lunch") || lower.contains("snack") || lower.contains("food") || lower.contains("refreshment") || lower.contains("milk") -> "Refreshments & Hospitality"
                            lower.contains("pen") || lower.contains("paper") || lower.contains("print") || lower.contains("stationery") || lower.contains("staple") || lower.contains("file") || lower.contains("xerox") -> "Office Supplies & Printing"
                            lower.contains("taxi") || lower.contains("cab") || lower.contains("auto") || lower.contains("bus") || lower.contains("fuel") || lower.contains("petrol") || lower.contains("diesel") || lower.contains("fare") -> "Local Transport & Fuel"
                            lower.contains("repair") || lower.contains("electric") || lower.contains("plumb") || lower.contains("clean") || lower.contains("fix") -> "Repairs & Maintenance"
                            lower.contains("courier") || lower.contains("speed post") || lower.contains("postage") || lower.contains("parcel") -> "Postage & Freight"
                            lower.contains("bill") || lower.contains("water") || lower.contains("recharge") || lower.contains("wifi") || lower.contains("internet") -> "Utilities & Internet"
                            else -> "General Petty Expenses"
                        }
                    }

                    val pettyCashVouchers = vouchers.filter { it.voucherType == VoucherType.PAYMENT && (it.narration.contains("Petty", ignoreCase = true) || it.partyName.contains("Expense", ignoreCase = true) || it.partyName.contains("Supplies", ignoreCase = true) || it.partyName.contains("Transport", ignoreCase = true) || it.partyName.contains("Hospitality", ignoreCase = true)) }
                    val totalPettyExpenses = pettyCashVouchers.sumOf { it.totalAmount }
                    val categoryBreakdown = pettyCashVouchers.groupBy { it.partyName.ifBlank { "General Petty Expenses" } }
                        .mapValues { entry -> entry.value.sumOf { it.totalAmount } }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("petty_cash_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Cash & Expense", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = LavenderContainer
                                ) {
                                    Text(
                                        text = "Total: ${IndianFormatter.formatRupee(totalPettyExpenses)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkPurpleVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Record Expense", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = pettyNarration,
                                onValueChange = { pettyNarration = it },
                                label = { Text("Expense Description") },
                                placeholder = { Text("Enter description...") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("petty_cash_narration_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = pettyAmountText,
                                    onValueChange = { pettyAmountText = it },
                                    label = { Text("Amount") },
                                    prefix = { Text("₹ ") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).testTag("petty_cash_amount_input")
                                )

                                Button(
                                    onClick = {
                                        val amt = pettyAmountText.toDoubleOrNull() ?: 0.0
                                        if (amt > 0 && pettyNarration.isNotBlank()) {
                                            viewModel.addVoucher(
                                                type = VoucherType.PAYMENT,
                                                partyName = autoCategory,
                                                amountText = pettyAmountText,
                                                gstRateText = "0",
                                                isInterstate = false,
                                                narration = "Petty Expense: $pettyNarration"
                                            )
                                            pettyNarration = ""
                                            pettyAmountText = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.height(56.dp).testTag("save_petty_cash_btn")
                                ) {
                                    Text("Log", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (pettyNarration.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = LavenderContainer.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RoyalPurplePrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Auto-Categorized As: ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(autoCategory, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(12.dp))

                            Text("Month-End Expense Reconciliation Breakdown", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                            Spacer(modifier = Modifier.height(8.dp))

                            if (categoryBreakdown.isEmpty()) {
                                Text("No petty cash expenses recorded yet for this month.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                categoryBreakdown.forEach { (cat, total) ->
                                    val percent = if (totalPettyExpenses > 0) (total / totalPettyExpenses * 100).toInt() else 0
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(cat, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            Text("${IndianFormatter.formatRupee(total)} ($percent%)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        LinearProgressIndicator(
                                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                                            color = RoyalPurplePrimary,
                                            trackColor = LavenderContainer,
                                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                item {
                    Text("Recent Petty Cash Expense Logs", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val pettyLogs = vouchers.filter { it.voucherType == VoucherType.PAYMENT && (it.narration.startsWith("Petty Expense:", ignoreCase = true) || it.partyName.contains("Expense", ignoreCase = true) || it.partyName.contains("Hospitality", ignoreCase = true) || it.partyName.contains("Supplies", ignoreCase = true) || it.partyName.contains("Transport", ignoreCase = true)) }
                if (pettyLogs.isEmpty()) {
                    item { Text("No petty cash entries recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(pettyLogs) { voucher ->
                        VoucherDashboardCard(voucher = voucher) {
                            // Opens the Vouchers screen, which owns the edit dialog. These four
                            // handlers used to assign edit state that nothing in this file ever
                            // read, so tapping a voucher card did nothing at all.
                            onNavigateToVouchers(voucher.voucherType)
                        }
                    }
                }
            }

            // TAB 4: SCHEDULED REMINDERS (Vendor Payments & Customer Collections)
            4 -> {
                item {
                    var reminderPartyName by remember { mutableStateOf("") }
                    var reminderPermissionIssue by remember { mutableStateOf<String?>(null) }
                    var reminderAmountText by remember { mutableStateOf("") }
                    var reminderType by remember { mutableStateOf("COLLECTION") }
                    var reminderDaysOffset by remember { mutableStateOf(1) }
                    val context = LocalContext.current

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("scheduled_reminders_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Payment & Collection Reminders", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = RoyalPurplePrimary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Schedule local device notifications to remind you to collect customer payments or pay vendor bills.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Spacer(modifier = Modifier.height(16.dp))

                            Text("Reminder Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = reminderType == "COLLECTION",
                                    onClick = { reminderType = "COLLECTION" },
                                    label = { Text("Customer Collection") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccountingGreen, selectedLabelColor = Color.White),
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = reminderType == "PAYMENT",
                                    onClick = { reminderType = "PAYMENT" },
                                    label = { Text("Vendor Payment") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccountingRed, selectedLabelColor = Color.White),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = reminderPartyName,
                                onValueChange = { reminderPartyName = it },
                                label = { Text("Party Name (Customer / Vendor)") },
                                placeholder = { Text("e.g. Anand Traders") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("reminder_party_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = reminderAmountText,
                                onValueChange = { reminderAmountText = it },
                                label = { Text("Amount Due (₹)") },
                                prefix = { Text("₹ ") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().testTag("reminder_amount_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Due Schedule:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                mapOf(0 to "Today", 1 to "Tomorrow", 3 to "In 3 Days", 7 to "In 7 Days").forEach { (days, label) ->
                                    FilterChip(
                                        selected = reminderDaysOffset == days,
                                        onClick = { reminderDaysOffset = days },
                                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RoyalPurplePrimary, selectedLabelColor = Color.White)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Says why a reminder could not be scheduled, and offers the
                            // route to fix it. Both permissions are declared in the manifest
                            // and were never requested, so the alarm was accepted and then
                            // silently never delivered.
                            reminderPermissionIssue?.let { issue ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = AccountingRed.copy(alpha = 0.10f),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(issue, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                                        TextButton(onClick = {
                                            if (!com.example.utils.ReminderPermissions.hasNotificationPermission(context)) {
                                                com.example.utils.ReminderPermissions.openAppNotificationSettings(context)
                                            } else {
                                                com.example.utils.ReminderPermissions.openExactAlarmSettings(context)
                                            }
                                            reminderPermissionIssue = null
                                        }) { Text("Open settings to allow it", fontSize = 11.sp) }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val amt = reminderAmountText.toDoubleOrNull() ?: 0.0
                                    if (amt > 0 && reminderPartyName.isNotBlank()) {
                                        val scheduledTime = System.currentTimeMillis() + (reminderDaysOffset * 24 * 3600 * 1000L).coerceAtLeast(3000L)
                                        val newItem = com.example.service.PaymentReminderItem(
                                            id = System.currentTimeMillis(),
                                            partyName = reminderPartyName,
                                            amount = amt,
                                            reminderType = reminderType,
                                            scheduledTimeMillis = scheduledTime
                                        )
                                        // Record it as scheduled only if the system actually
                                        // accepted it. Notifications and exact alarms each
                                        // need a runtime grant that was never requested, so
                                        // this reported success while the alarm never fired.
                                        val failure = com.example.service.PaymentReminderManager
                                            .scheduleReminder(context, newItem)
                                        if (failure == null) {
                                            reminderPermissionIssue = null
                                            reminderScheduledList.add(0, newItem)
                                            reminderPartyName = ""
                                            reminderAmountText = ""
                                        } else {
                                            reminderPermissionIssue = failure
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (reminderType == "COLLECTION") AccountingGreen else AccountingRed),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("schedule_reminder_btn")
                            ) {
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Set Local Device Alarm Reminder", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }

                item {
                    Text("Scheduled Device Alarms", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Renders the alarms the user has actually scheduled in this session. This
                // block previously ignored `reminderScheduledList` entirely and rendered a
                // single invented reminder -- "Anand Traders (Customer Collection) / Pending
                // Amount: ₹24,500 / Due Tomorrow at 10:00 AM" -- unconditionally, so every
                // user saw a debt owed by a customer they had never heard of.
                if (reminderScheduledList.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("No reminders scheduled", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = "Alarms you set above will be listed here.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(reminderScheduledList) { reminder ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${reminder.partyName} (${if (reminder.reminderType == "COLLECTION") "Customer Collection" else "Vendor Payment"})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Pending Amount: ${IndianFormatter.formatRupee(reminder.amount)} • Due ${IndianFormatter.formatDateWithTime(reminder.scheduledTimeMillis)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = AccountingGreen.copy(alpha = 0.15f)
                                ) {
                                    Text("SCHEDULED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingGreen, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }

            // TAB 5: RECEIPT & PAYMENT & CONTRA DASHBOARD
            5 -> {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Receipt, Payment & Contra", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { showContraModal = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Contra", fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = { showEasyEntryModal = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Easy Assistant", fontSize = 11.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            MonetaryRow(label = "Total Receipts (Inflows)", amount = totalReceipts, amountColor = AccountingGreen)
                            MonetaryRow(label = "Total Payments (Outflows)", amount = totalPayments, amountColor = AccountingRed)
                            MonetaryRow(label = "Bank & Cash Contra Volume", amount = totalContra, amountColor = DarkPurpleVariant)
                            Divider(modifier = Modifier.padding(vertical = 10.dp))
                            MonetaryRow(
                                label = if (totalReceipts - totalPayments >= 0) "Net Operating Surplus" else "Net Operating Deficit",
                                amount = Math.abs(totalReceipts - totalPayments),
                                amountColor = if (totalReceipts - totalPayments >= 0) AccountingGreen else AccountingRed
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                item {
                    Text("Cash, Bank & Payment History", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val cashBankList = vouchers.filter {
                    it.voucherType == VoucherType.RECEIPT || it.voucherType == VoucherType.PAYMENT || it.voucherType == VoucherType.CONTRA
                }
                if (cashBankList.isEmpty()) {
                    item { Text("No Receipt/Payment/Contra vouchers recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(cashBankList) { voucher ->
                        VoucherDashboardCard(voucher = voucher) {
                            // Opens the Vouchers screen, which owns the edit dialog. These four
                            // handlers used to assign edit state that nothing in this file ever
                            // read, so tapping a voucher card did nothing at all.
                            onNavigateToVouchers(voucher.voucherType)
                        }
                    }
                }
            }

            // TAB 6: EXCEL / PDF STATEMENT IMPORTER & EXPORTER
            6 -> {
                item {
                    DocumentExtractImportSection(viewModel = viewModel, user = user)
                }
            }
        }
    }

    // Modal: Non-Accountant Easy Entry Assistant
    if (showEasyEntryModal) {
        var easyPartyName by remember { mutableStateOf("") }
        var easyAmount by remember { mutableStateOf("") }
        var easyType by remember { mutableStateOf("PAYMENT") }
        var easyMode by remember { mutableStateOf("CASH") }

        AlertDialog(
            onDismissRequest = { showEasyEntryModal = false },
            title = {
                Text("Smart Layperson Accounting Assistant", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter transactions in natural business terms without needing debit/credit accounting knowledge:", fontSize = 12.sp)

                    OutlinedTextField(
                        value = easyPartyName,
                        onValueChange = { easyPartyName = it },
                        label = { Text("What or who is this for? (e.g. Office Rent, AC Purchase, Client Cash)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = easyAmount,
                        onValueChange = { easyAmount = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text("Transaction Direction:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = easyType == "PAYMENT",
                            onClick = { easyType = "PAYMENT" },
                            label = { Text("Paid Out (Expense/Asset)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = easyType == "RECEIPT",
                            onClick = { easyType = "RECEIPT" },
                            label = { Text("Received In (Income/Client)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Payment Mode:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = easyMode == "CASH",
                            onClick = { easyMode = "CASH" },
                            label = { Text("Cash") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = easyMode == "BANK",
                            onClick = { easyMode = "BANK" },
                            label = { Text("Bank / UPI") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = easyAmount.toDoubleOrNull() ?: 0.0
                        if (easyPartyName.isNotBlank() && amt > 0) {
                            // The party name is no longer mangled to "Cash - <name>".
                            // That prefix existed purely to trigger the old
                            // partyName.contains("cash") inference, and it corrupted the
                            // party ledger's name in the process. The mode is passed
                            // properly now.
                            val vType = if (easyType == "PAYMENT") VoucherType.PAYMENT else VoucherType.RECEIPT
                            viewModel.addVoucher(
                                type = vType,
                                partyName = easyPartyName,
                                amountText = amt.toString(),
                                // Receipts and payments settle a balance; they do not
                                // themselves carry GST, and 18% was being applied here.
                                gstRateText = "0",
                                isInterstate = false,
                                narration = "Quick entry ($easyMode)",
                                paymentMode = if (easyMode == "CASH") "CASH" else "BANK"
                            )
                            showEasyEntryModal = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Record Entry")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEasyEntryModal = false }) { Text("Cancel") }
            }
        )
    }

    // Modal: Bank & Cash Contra Transfer
    if (showContraModal) {
        var contraAmountText by remember { mutableStateOf("") }
        var contraDirection by remember { mutableStateOf("DEPOSIT") } // DEPOSIT = Cash to Bank, WITHDRAWAL = Bank to Cash

        AlertDialog(
            onDismissRequest = { showContraModal = false },
            title = { Text("Bank & Cash Contra Entry", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Transfer cash directly between Bank account and Cash-in-hand:", fontSize = 12.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = contraDirection == "DEPOSIT",
                            onClick = { contraDirection = "DEPOSIT" },
                            label = { Text("Cash Deposit (Cash -> Bank)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = contraDirection == "WITHDRAWAL",
                            onClick = { contraDirection = "WITHDRAWAL" },
                            label = { Text("Cash Withdrawal (Bank -> Cash)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = contraAmountText,
                        onValueChange = { contraAmountText = it },
                        label = { Text("Transfer Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = contraAmountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            val party = if (contraDirection == "DEPOSIT") "Cash Deposit to Bank" else "Cash Withdrawal from Bank"
                            viewModel.addVoucher(
                                type = VoucherType.CONTRA,
                                partyName = party,
                                amountText = amt.toString(),
                                gstRateText = "0",
                                isInterstate = false,
                                narration = "Contra cash transfer",
                                // A deposit ends up in the BANK, a withdrawal in CASH.
                                // Direction was read from the party text, which meant a
                                // deposit worded "Cash to Bank" posted backwards.
                                paymentMode = if (contraDirection == "DEPOSIT") "BANK" else "CASH"
                            )
                            showContraModal = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Transfer Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContraModal = false }) { Text("Cancel") }
            }
        )
    }

    // Cash & Bank Ledgers Modal
    if (showCashBankDialog) {
        AlertDialog(
            onDismissRequest = { showCashBankDialog = false },
            title = { Text("Cash & Bank Ledger Details", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cashAndBankLedgers.isEmpty()) {
                        Text("No Cash/Bank ledgers set up.", fontSize = 13.sp)
                    } else {
                        cashAndBankLedgers.forEach { ledger ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(ledger.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(ledger.groupName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Debit (Dr):", fontSize = 12.sp)
                                        Text(IndianFormatter.formatRupee(ledger.totalDebit), style = MonospaceTabularTextStyle, color = AccountingGreen, fontWeight = FontWeight.SemiBold)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Credit (Cr):", fontSize = 12.sp)
                                        Text(IndianFormatter.formatRupee(ledger.totalCredit), style = MonospaceTabularTextStyle, color = AccountingRed, fontWeight = FontWeight.SemiBold)
                                    }
                                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Net Balance:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("${IndianFormatter.formatRupee(Math.abs(ledger.currentBalance))} ${if (ledger.currentBalance >= 0) "Dr" else "Cr"}", style = MonospaceTabularTextStyle, fontWeight = FontWeight.Bold, color = if (ledger.currentBalance >= 0) AccountingGreen else AccountingRed)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCashBankDialog = false }) { Text("Close") } }
        )
    }

    if (showSignaturePadDialog) {
        SignaturePadDialog(
            onDismissRequest = { showSignaturePadDialog = false },
            onSignatureSaved = { _, _ ->
                showSignaturePadDialog = false
                Toast.makeText(context, "Authorized Signature Saved for Invoices!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showQrScannerDialog) {
        QrCodeScannerDialog(
            onDismissRequest = { showQrScannerDialog = false },
            onInvoiceScanned = { scannedData ->
                showQrScannerDialog = false
                // No longer says "Scanned" -- the dialog has no camera or OCR; these values
                // are typed by the user.
                Toast.makeText(context, "Invoice details entered: ${scannedData.partyName} (₹${scannedData.amount})", Toast.LENGTH_LONG).show()
                onNavigateToVouchers(scannedData.voucherType)
            }
        )
    }

    if (showLedgerManagementModal) {
        LedgerManagementModal(
            viewModel = viewModel,
            onDismiss = { showLedgerManagementModal = false }
        )
    }

    if (showCustomVoucherModal) {
        CustomVoucherModal(
            viewModel = viewModel,
            onDismiss = { showCustomVoucherModal = false }
        )
    }

    if (showGstFilingScheduleModal) {
        GstFilingScheduleModal(
            selectedFiscalYear = selectedFiscalYear,
            onDismiss = { showGstFilingScheduleModal = false },
            filingScheme = user.filingScheme
        )
    }

    if (showGstCalculatorModal) {
        com.example.ui.components.GstCalculatorModal(
            onDismiss = { showGstCalculatorModal = false },
            onApplyToVoucher = { baseAmount, gstRate, isExclusive, isInterstate ->
                showEasyEntryModal = true
            }
        )
    }

    if (showReconciliationModal) {
        com.example.ui.components.LedgerReconciliationModal(
            discrepancies = reconciliationDiscrepancies,
            unresolvedCount = unresolvedDiscrepancyCount,
            isReconciling = isReconciling,
            autoReconciliationEnabled = autoReconciliationEnabled,
            lastReconciliationTime = lastReconciliationTime,
            onDismiss = { showReconciliationModal = false },
            onRunReconciliationNow = { viewModel.triggerAutoReconciliation() },
            onResolveDiscrepancy = { id, notes -> viewModel.resolveDiscrepancy(id, notes) },
            onToggleAutoReconciliation = { enabled -> viewModel.toggleAutoReconciliation(enabled) }
        )
    }
}

@Composable
fun VoucherDashboardCard(
    voucher: VoucherEntity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (voucher.voucherType) {
                            VoucherType.SALES -> AccountingGreen.copy(alpha = 0.15f)
                            VoucherType.PURCHASE -> AccountingRed.copy(alpha = 0.15f)
                            VoucherType.CONTRA -> DarkPurpleVariant.copy(alpha = 0.15f)
                            else -> LavenderContainer
                        }
                    ) {
                        Text(
                            text = voucher.voucherType.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (voucher.voucherType) {
                                VoucherType.SALES -> AccountingGreen
                                VoucherType.PURCHASE -> AccountingRed
                                VoucherType.CONTRA -> DarkPurpleVariant
                                else -> RoyalPurplePrimary
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(voucher.voucherNo, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(voucher.partyName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(IndianFormatter.formatDate(voucher.date), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(IndianFormatter.formatRupee(voucher.totalAmount), style = MonospaceTabularTextStyle, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DocumentExtractImportSection(
    viewModel: AccountingViewModel,
    user: UserEntity
) {
    // Starts empty on purpose. This box used to ship pre-filled with five invented
    // transactions (Salary Receipt 85,000, Vendor Payment SS Corp 24,500, ...). On a
    // fresh install all five validated as VALID, and one tap on "Import Valid Only"
    // wrote them into the user's real ledger. Every other fabrication in the app is a
    // display lie; this one became persisted financial data.
    var rawText by remember { mutableStateOf("") }
    var documentType by remember { mutableStateOf("Bank Statement (PDF/Excel)") }
    var importedSuccessMessage by remember { mutableStateOf<String?>(null) }

    // A line that cannot be read is reported as unreadable, never completed with invented
    // values. This block used to fall back to `?: "Today"` for a missing date,
    // `?: "General Account"` for a missing party, and -- worst of all -- `?: 1000.0` for a
    // missing amount, which turned any unparseable statement line into a ₹1,000 voucher
    // that then validated as VALID and was written to the ledger on import.
    val parsedLines = remember(rawText) {
        val dateRegex = Regex(".*\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}.*")
        rawText.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split(Regex("[,;\t|]+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val date = parts.firstOrNull { it.matches(dateRegex) }.orEmpty()
                val party = parts.firstOrNull { !it.matches(dateRegex) && it.toDoubleOrNull() == null }.orEmpty()
                val amount = parts.mapNotNull { it.replace("Rs", "").replace("₹", "").replace(",", "").toDoubleOrNull() }.lastOrNull()
                val typeStr = line.uppercase()
                val voucherType = when {
                    typeStr.contains("RECEIPT") || typeStr.contains("DEPOSIT") || typeStr.contains("CREDIT") -> VoucherType.RECEIPT
                    typeStr.contains("CONTRA") || typeStr.contains("TRANSFER") -> VoucherType.CONTRA
                    typeStr.contains("SALE") -> VoucherType.SALES
                    typeStr.contains("PURCHASE") -> VoucherType.PURCHASE
                    else -> VoucherType.PAYMENT
                }
                val missing = buildList {
                    if (date.isBlank()) add("date")
                    if (party.isBlank()) add("party name")
                    if (amount == null) add("amount")
                }
                ParsedEntryRow(
                    date = date,
                    party = party,
                    voucherType = voucherType,
                    amount = amount ?: 0.0,
                    missingFields = missing.joinToString(", ")
                )
            } else null
        }
    }

    val vouchersState by viewModel.vouchersState.collectAsState()

    val validatedLines = remember(rawText, vouchersState) {
        val dbVouchers = vouchersState
        parsedLines.map { entry ->
            val isDuplicate = dbVouchers.any { dbV ->
                dbV.partyName.equals(entry.party, ignoreCase = true) &&
                Math.abs(dbV.totalAmount - entry.amount) < 0.01
            }
            val isTypeMismatch = (entry.party.contains("Cash", ignoreCase = true) || entry.party.contains("Bank", ignoreCase = true)) &&
                    (entry.voucherType == VoucherType.SALES || entry.voucherType == VoucherType.PURCHASE)
            val isInvalidAmount = entry.amount <= 0.0

            val status = when {
                entry.missingFields.isNotBlank() -> ValidationStatus.UNPARSEABLE
                isDuplicate -> ValidationStatus.DUPLICATE
                isTypeMismatch -> ValidationStatus.TYPE_MISMATCH
                isInvalidAmount -> ValidationStatus.INVALID_AMOUNT
                else -> ValidationStatus.VALID
            }

            val reason = when (status) {
                ValidationStatus.UNPARSEABLE -> "Could not read ${entry.missingFields} from this line — excluded from import"
                ValidationStatus.DUPLICATE -> "Duplicate entry found in DB with same party & amount"
                ValidationStatus.TYPE_MISMATCH -> "Account type mismatch: Cash/Bank entries should be Receipt/Payment or Contra"
                ValidationStatus.INVALID_AMOUNT -> "Invalid amount: Must be greater than zero"
                ValidationStatus.VALID -> "Passed validation checks"
            }

            ValidatedParsedRow(
                date = entry.date,
                party = entry.party,
                voucherType = entry.voucherType,
                amount = entry.amount,
                status = status,
                reason = reason
            )
        }
    }

    val validCount = validatedLines.count { it.status == ValidationStatus.VALID }
    val duplicateCount = validatedLines.count { it.status == ValidationStatus.DUPLICATE }
    val mismatchCount = validatedLines.count { it.status == ValidationStatus.TYPE_MISMATCH }
    val unreadableCount = validatedLines.count { it.status == ValidationStatus.UNPARSEABLE }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("excel_import_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Excel & PDF Statement OCR Extract", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                    Text("Auto-fill bank statements & ledger entries", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = RoyalPurplePrimary)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("Select Document Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Bank Statement (PDF/Excel)", "Sales Invoices (Excel)", "Vendor Bills (PDF)").forEach { type ->
                    FilterChip(
                        selected = documentType == type,
                        onClick = { documentType = type },
                        label = { Text(type, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RoyalPurplePrimary, selectedLabelColor = Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                label = { Text("Document Extracted Data (CSV / Text)") },
                // The format guidance the seeded rows used to provide — as a placeholder,
                // which cannot be imported, instead of as real text in the box.
                placeholder = {
                    Text(
                        "One transaction per line:\n" +
                            "DD/MM/YYYY, Party Name, TYPE, Amount\n" +
                            "TYPE is SALES, PURCHASE, RECEIPT, PAYMENT or CONTRA",
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier.fillMaxWidth().height(120.dp).testTag("csv_import_input"),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Validation Summary Banner
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = LavenderContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().testTag("validation_summary_banner")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("DATA VALIDATION LAYER RESULTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("✅ Valid Rows: $validCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                        Text("⚠️ Duplicates: $duplicateCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                        Text("⚡ Type Mismatches: $mismatchCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                    }
                    if (unreadableCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🚫 Unreadable rows (skipped): $unreadableCount",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccountingRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Parsed & Validated Entries (${validatedLines.size} rows):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                validatedLines.forEach { row ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (row.status) {
                            ValidationStatus.VALID -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ValidationStatus.DUPLICATE -> Color(0xFFFEF3C7)
                            ValidationStatus.TYPE_MISMATCH -> Color(0xFFFEE2E2)
                            ValidationStatus.INVALID_AMOUNT -> Color(0xFFFEE2E2)
                            ValidationStatus.UNPARSEABLE -> Color(0xFFFEE2E2)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Neutral dash rather than a stand-in account name when the
                                    // line yielded no party.
                                    Text(row.party.ifBlank { "—" }, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = when (row.status) {
                                            ValidationStatus.VALID -> AccountingGreen.copy(alpha = 0.15f)
                                            ValidationStatus.DUPLICATE -> Color(0xFFD97706).copy(alpha = 0.15f)
                                            else -> AccountingRed.copy(alpha = 0.15f)
                                        }
                                    ) {
                                        Text(
                                            text = when (row.status) {
                                                ValidationStatus.VALID -> "VALID"
                                                ValidationStatus.DUPLICATE -> "DUPLICATE"
                                                ValidationStatus.TYPE_MISMATCH -> "MISMATCH"
                                                ValidationStatus.INVALID_AMOUNT -> "INVALID"
                                                ValidationStatus.UNPARSEABLE -> "UNREADABLE"
                                            },
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (row.status) {
                                                ValidationStatus.VALID -> AccountingGreen
                                                ValidationStatus.DUPLICATE -> Color(0xFFD97706)
                                                else -> AccountingRed
                                            },
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = listOf(row.date.ifBlank { "—" }, row.voucherType.name, row.reason).joinToString(" • "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // A row whose amount could not be read shows a dash, not ₹0.00.
                            Text(
                                text = if (row.status == ValidationStatus.UNPARSEABLE && row.amount == 0.0) "—"
                                else IndianFormatter.formatRupee(row.amount),
                                style = MonospaceTabularTextStyle,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons based on Validation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val validRows = validatedLines.filter { it.status == ValidationStatus.VALID }
                        validRows.forEach { entry ->
                            viewModel.addVoucher(
                                type = entry.voucherType,
                                partyName = entry.party,
                                amountText = entry.amount.toString(),
                                gstRateText = "18",
                                isInterstate = false,
                                narration = "Validated Import from $documentType (${entry.date})",
                                paymentMode = "BANK",
                            )
                        }
                        importedSuccessMessage = "Successfully imported ${validRows.size} validated non-duplicate entries!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag("import_valid_only_btn")
                ) {
                    Text("Import Valid Only (${validCount})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        // Auto-fix mismatches (e.g. Convert Cash Sale to Receipt/Contra, skip duplicates)
                        var fixedCount = 0
                        validatedLines.forEach { entry ->
                            // Unreadable rows are skipped here too: "Auto-Fix" must not invent the
                            // date, party or amount the source line never supplied.
                            if (entry.status != ValidationStatus.DUPLICATE &&
                                entry.status != ValidationStatus.UNPARSEABLE &&
                                entry.amount > 0
                            ) {
                                val fixedType = if (entry.status == ValidationStatus.TYPE_MISMATCH) {
                                    VoucherType.RECEIPT
                                } else entry.voucherType
                                viewModel.addVoucher(
                                    type = fixedType,
                                    partyName = entry.party,
                                    amountText = entry.amount.toString(),
                                    gstRateText = "18",
                                    isInterstate = false,
                                    narration = "Auto-Fixed Import from $documentType (${entry.date})",
                                paymentMode = "BANK",
                                )
                                fixedCount++
                            }
                        }
                        importedSuccessMessage = "Auto-fixed and imported $fixedCount entries cleanly!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag("auto_fix_import_btn")
                ) {
                    Text("Auto-Fix & Import", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (importedSuccessMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = importedSuccessMessage!!,
                    color = AccountingGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

data class ParsedEntryRow(
    val date: String,
    val party: String,
    val voucherType: VoucherType,
    val amount: Double,
    /** Comma-separated names of the fields the source line did not yield. Empty when the
     *  line parsed completely. A non-empty value marks the row UNPARSEABLE, which excludes
     *  it from every import path instead of filling the gaps with invented values. */
    val missingFields: String = ""
)

enum class ValidationStatus {
    VALID,
    DUPLICATE,
    TYPE_MISMATCH,
    INVALID_AMOUNT,
    UNPARSEABLE
}

data class ValidatedParsedRow(
    val date: String,
    val party: String,
    val voucherType: VoucherType,
    val amount: Double,
    val status: ValidationStatus,
    val reason: String
)

@Composable
fun QuickActionButton(
    title: String,
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, backgroundColor.copy(alpha = 0.35f)),
        shadowElevation = 3.dp,
        tonalElevation = 2.dp,
        modifier = modifier.testTag("3d_head_button_${title.lowercase().replace(" ", "_")}")
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = backgroundColor,
                shadowElevation = 4.dp,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun IncomeExpenseChartCard(
    monthlyPnlRows: List<MonthlyPnlRow>,
    totalIncome: Double,
    totalExpense: Double
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Monthly Financial Trend",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Posted journal entries, current financial year",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(AccountingGreen, shape = CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Income (Sales)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(AccountingRed, shape = CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Expense (Purchases)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Real monthly series, bucketed from posted journal entries (viewModel.monthlyPnlState).
            // This chart used to invent its own history: a fixed axis of "Apr, May, Jun, Jul,
            // Aug, Current" and two hardcoded ratio lists (0.45/0.60/0.52/0.75/0.85/1.0 for
            // income, 0.35/0.50/0.40/0.65/0.70/... for expense) that smeared the single real
            // period total across six fabricated buckets. Every user saw the same rising
            // "trend" no matter what their books said, including on a brand-new install.
            val monthlySeries = remember(monthlyPnlRows) {
                val revenueByMonth = monthlyPnlRows
                    .filter { it.category == LedgerCategory.REVENUE }
                    .groupBy { it.monthKey }
                    .mapValues { (_, rows) -> rows.sumOf { it.totalCredit - it.totalDebit } }
                val expenseByMonth = monthlyPnlRows
                    .filter { it.category == LedgerCategory.EXPENSE }
                    .groupBy { it.monthKey }
                    .mapValues { (_, rows) -> rows.sumOf { it.totalDebit - it.totalCredit } }

                val keyFmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.ENGLISH)
                val labelFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.ENGLISH)

                (revenueByMonth.keys + expenseByMonth.keys).sorted().map { key ->
                    val label = try {
                        keyFmt.parse(key)?.let { labelFmt.format(it) } ?: key
                    } catch (e: Exception) {
                        key
                    }
                    Triple(label, revenueByMonth[key] ?: 0.0, expenseByMonth[key] ?: 0.0)
                }
            }

            if (monthlySeries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No monthly activity recorded yet.\nPost a sale or an expense to see the trend.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                val maxVal = monthlySeries
                    .maxOf { Math.max(it.second, it.third) }
                    .coerceAtLeast(1.0)

                Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val bottomPadding = 60f
                        val topPadding = 10f
                        val chartHeight = canvasHeight - bottomPadding - topPadding
                        val count = monthlySeries.size
                        val sectionWidth = canvasWidth / count
                        val barWidth = (sectionWidth * 0.32f).coerceAtMost(22f)

                        for (i in 0..3) {
                            val y = topPadding + (chartHeight / 3f) * i
                            drawLine(
                                color = Color.LightGray.copy(alpha = 0.25f),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 1f
                            )
                        }

                        drawLine(
                            color = Color.Gray.copy(alpha = 0.4f),
                            start = Offset(0f, canvasHeight - bottomPadding),
                            end = Offset(canvasWidth, canvasHeight - bottomPadding),
                            strokeWidth = 2f
                        )

                        monthlySeries.forEachIndexed { i, (_, income, expense) ->
                            val centerX = sectionWidth * i + (sectionWidth / 2f)
                            val incHeight = (chartHeight * (income / maxVal)).toFloat().coerceAtLeast(0f)
                            val expHeight = (chartHeight * (expense / maxVal)).toFloat().coerceAtLeast(0f)

                            val incLeft = centerX - barWidth - 2f
                            val expLeft = centerX + 2f

                            // A month with no activity draws no bar, rather than a minimum-height
                            // stub that would read as real turnover.
                            if (incHeight > 0f) {
                                drawRoundRect(
                                    color = AccountingGreen,
                                    topLeft = Offset(incLeft, canvasHeight - bottomPadding - incHeight),
                                    size = Size(barWidth, incHeight),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }
                            if (expHeight > 0f) {
                                drawRoundRect(
                                    color = AccountingRed,
                                    topLeft = Offset(expLeft, canvasHeight - bottomPadding - expHeight),
                                    size = Size(barWidth, expHeight),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        monthlySeries.forEach { (label, _, _) ->
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Net Operating Margin:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val netMargin = totalIncome - totalExpense
                Text(
                    text = IndianFormatter.formatRupee(netMargin),
                    style = MonospaceTabularTextStyle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (netMargin >= 0) AccountingGreen else AccountingRed
                )
            }
        }
    }
}

data class GstDeadline(
    val name: String,
    val period: String,
    val dueDateString: String,
    val daysLeft: Long,
    val description: String
)

/**
 * @param filingScheme "QRMP" or "MONTHLY". QRMP (turnover up to Rs 5 crore) files GSTR-1
 *   quarterly by the 13th of the month following the quarter and pays monthly via PMT-06
 *   by the 25th; the monthly scheme files GSTR-1 by the 11th and GSTR-3B by the 20th.
 *   The dates were hardcoded to the monthly scheme, so a QRMP filer was shown the wrong
 *   deadline every single month (H14).
 */
fun calculateGstDeadlines(
    now: java.util.Calendar = java.util.Calendar.getInstance(),
    filingScheme: String = "MONTHLY"
): List<GstDeadline> {
    val isQrmp = filingScheme.equals("QRMP", ignoreCase = true)
    val gstr1DueDay = if (isQrmp) 13 else 11
    val gstr3bDueDay = if (isQrmp) 25 else 20
    val currentDay = now.get(java.util.Calendar.DAY_OF_MONTH)
    val currentMonth = now.get(java.util.Calendar.MONTH)
    val monthFormat = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.ENGLISH)
    val prevMonthCal = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, -1) }
    val prevMonthName = monthFormat.format(prevMonthCal.time)

    val deadlines = mutableListOf<GstDeadline>()

    // GSTR-1 — 11th monthly, or the 13th after each quarter under QRMP.
    val gstr1DueCal = (now.clone() as java.util.Calendar).apply {
        if (currentDay > gstr1DueDay) {
            add(java.util.Calendar.MONTH, 1)
        }
        if (isQrmp) {
            // Quarter ends Jun/Sep/Dec/Mar; due the 13th of the following month.
            while (get(java.util.Calendar.MONTH) % 3 != 0) add(java.util.Calendar.MONTH, 1)
        }
        set(java.util.Calendar.DAY_OF_MONTH, gstr1DueDay)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
    }
    val diffGstr1Ms = gstr1DueCal.timeInMillis - now.timeInMillis
    val daysGstr1 = (diffGstr1Ms / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    val gstr1DueDateStr = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(gstr1DueCal.time)

    deadlines.add(
        GstDeadline(
            name = "GSTR-1 (Outward Sales)",
            period = if (currentDay <= gstr1DueDay) prevMonthName else monthFormat.format(now.time),
            dueDateString = gstr1DueDateStr,
            daysLeft = daysGstr1,
            description = "Filing of outward sales invoices & GST liability."
        )
    )

    // GSTR-3B: Due on 20th
    val gstr3bDueCal = (now.clone() as java.util.Calendar).apply {
        if (currentDay > gstr3bDueDay) {
            add(java.util.Calendar.MONTH, 1)
        }
        set(java.util.Calendar.DAY_OF_MONTH, gstr3bDueDay)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
    }
    val diffGstr3bMs = gstr3bDueCal.timeInMillis - now.timeInMillis
    val daysGstr3b = (diffGstr3bMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    val gstr3bDueDateStr = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(gstr3bDueCal.time)

    deadlines.add(
        GstDeadline(
            name = if (isQrmp) "PMT-06 (Monthly Payment)" else "GSTR-3B (Summary Return)",
            period = if (currentDay <= gstr3bDueDay) prevMonthName else monthFormat.format(now.time),
            dueDateString = gstr3bDueDateStr,
            daysLeft = daysGstr3b,
            description = "Monthly summary return and net tax payment after ITC."
        )
    )

    // CMP-08 / Quarterly Return: Due on 18th after quarter end
    val cmp08DueCal = (now.clone() as java.util.Calendar).apply {
        val qMonth = when (currentMonth) {
            0, 1, 2 -> 3
            3, 4, 5 -> 6
            6, 7, 8 -> 9
            else -> 0
        }
        if (qMonth == 0 && currentMonth > 8) add(java.util.Calendar.YEAR, 1)
        set(java.util.Calendar.MONTH, qMonth)
        set(java.util.Calendar.DAY_OF_MONTH, 18)
    }
    val diffCmpMs = cmp08DueCal.timeInMillis - now.timeInMillis
    val daysCmp = (diffCmpMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    val cmpDueDateStr = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(cmp08DueCal.time)

    deadlines.add(
        GstDeadline(
            name = "CMP-08 (Composition Scheme)",
            period = "Quarterly Statement",
            dueDateString = cmpDueDateStr,
            daysLeft = daysCmp,
            description = "Quarterly self-assessed tax payment for composition dealers."
        )
    )

    return deadlines
}

@Composable
fun GstDeadlineNotificationCard(
    selectedFiscalYear: FiscalYearOption,
    onViewFullSchedule: () -> Unit,
    filingScheme: String = "MONTHLY",
    modifier: Modifier = Modifier
) {
    // Keyed on the scheme and the current day: `remember {}` with no key froze the
    // countdown at first composition, so "3 days left" stayed 3 days forever.
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
    val deadlines = remember(filingScheme, today) {
        calculateGstDeadlines(filingScheme = filingScheme)
    }
    val urgentCount = deadlines.count { it.daysLeft <= 3 }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (urgentCount > 0) AmberContainer.copy(alpha = 0.45f) else LavenderContainer.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (urgentCount > 0) AmberGold else RoyalPurplePrimary.copy(alpha = 0.3f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("gst_deadline_notification_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (urgentCount > 0) AmberGold else RoyalPurplePrimary,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "GST FILING DEADLINES & ALERTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (urgentCount > 0) Color(0xFFB76E00) else RoyalPurplePrimary,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Schedule for ${selectedFiscalYear.shortLabel}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (urgentCount > 0) AccountingRed else AccountingGreen
                ) {
                    Text(
                        text = if (urgentCount > 0) "$urgentCount URGENT" else "UP TO DATE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deadlines.take(2).forEach { dl ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dl.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Period: ${dl.period} | Due: ${dl.dueDateString}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    dl.daysLeft <= 1 -> AccountingRed
                                    dl.daysLeft <= 3 -> AmberGold
                                    else -> AccountingGreen.copy(alpha = 0.15f)
                                }
                            ) {
                                Text(
                                    text = when {
                                        dl.daysLeft == 0L -> "DUE TODAY"
                                        dl.daysLeft == 1L -> "DUE TOMORROW"
                                        dl.daysLeft <= 3 -> "IN ${dl.daysLeft} DAYS"
                                        else -> "${dl.daysLeft} DAYS LEFT"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (dl.daysLeft <= 3) Color.White else AccountingGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(
                onClick = onViewFullSchedule,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("view_full_gst_schedule_btn")
            ) {
                Text(
                    text = "View Complete Filing Schedule →",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoyalPurplePrimary
                )
            }
        }
    }
}

@Composable
fun GstFilingScheduleModal(
    selectedFiscalYear: FiscalYearOption,
    onDismiss: () -> Unit,
    filingScheme: String = "MONTHLY"
) {
    // Keyed on the scheme and the current day: `remember {}` with no key froze the
    // countdown at first composition, so "3 days left" stayed 3 days forever.
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
    val deadlines = remember(filingScheme, today) {
        calculateGstDeadlines(filingScheme = filingScheme)
    }
    val fyLabel = selectedFiscalYear.label

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = RoyalPurplePrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "GST Statutory Filing Compliance",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary
                            )
                            Text(
                                text = "Statutory Deadlines ($fyLabel)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Upcoming Key Statutory Returns:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    deadlines.forEach { dl ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dl.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (dl.daysLeft <= 3) AccountingRed else AccountingGreen
                                    ) {
                                        Text(
                                            text = if (dl.daysLeft <= 3) "DUE SOON" else "SCHEDULED",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = dl.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Filing Period: ${dl.period}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Due Date: ${dl.dueDateString}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dl.daysLeft <= 3) AccountingRed else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Complete Monthly GST Filing Calendar:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val monthsList = listOf(
                        "April" to Pair("11 May", "20 May"),
                        "May" to Pair("11 Jun", "20 Jun"),
                        "June" to Pair("11 Jul", "20 Jul"),
                        "July" to Pair("11 Aug", "20 Aug"),
                        "August" to Pair("11 Sep", "20 Sep"),
                        "September" to Pair("11 Oct", "20 Oct"),
                        "October" to Pair("11 Nov", "20 Nov"),
                        "November" to Pair("11 Dec", "20 Dec"),
                        "December" to Pair("11 Jan", "20 Jan"),
                        "January" to Pair("11 Feb", "20 Feb"),
                        "February" to Pair("11 Mar", "20 Mar"),
                        "March" to Pair("11 Apr", "20 Apr")
                    )

                    monthsList.forEach { (m, dates) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(m, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                            Text("GSTR-1: ${dates.first}", fontSize = 11.sp, color = RoyalPurplePrimary)
                            Text("GSTR-3B: ${dates.second}", fontSize = 11.sp, color = AccountingGreen)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Close Compliance Calendar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}