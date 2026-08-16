package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.dao.LedgerWithBalance
import com.example.data.model.UserEntity
import com.example.ui.theme.*
import com.example.utils.CsvExporter
import com.example.utils.IndianFormatter
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialBalanceReportModal(
    user: UserEntity,
    trialBalance: List<LedgerWithBalance>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val currentDateStr = remember {
        SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
    }

    val totalDebit = trialBalance.sumOf { it.totalDebit }
    val totalCredit = trialBalance.sumOf { it.totalCredit }
    val difference = Math.abs(totalDebit - totalCredit)
    val isBalanced = difference < 0.01

    val filteredList = trialBalance.filter { ledger ->
        val matchesSearch = ledger.name.contains(searchQuery, ignoreCase = true) ||
                ledger.groupName.contains(searchQuery, ignoreCase = true)
        val matchesCategory = when (selectedCategoryFilter) {
            "ALL" -> true
            "ASSET" -> ledger.category.name.equals("ASSET", ignoreCase = true)
            "LIABILITY" -> ledger.category.name.equals("LIABILITY", ignoreCase = true)
            "REVENUE" -> ledger.category.name.equals("REVENUE", ignoreCase = true)
            "EXPENSE" -> ledger.category.name.equals("EXPENSE", ignoreCase = true)
            "EQUITY" -> ledger.category.name.equals("EQUITY", ignoreCase = true)
            else -> true
        }
        matchesSearch && matchesCategory
    }

    val filteredDebitSum = filteredList.sumOf { it.totalDebit }
    val filteredCreditSum = filteredList.sumOf { it.totalCredit }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("trial_balance_report_modal"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "TRIAL BALANCE REPORT",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurplePrimary,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "${user.businessName.ifBlank { "Accounting Ledger" }} • As on $currentDateStr",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Close Modal")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    val csvData = CsvExporter.generateLedgerSummaryCsv(trialBalance)
                                    CsvExporter.shareCsvFile(context, "Trial_Balance_${user.businessName.replace(" ", "_")}.csv", csvData)
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share Trial Balance CSV", tint = RoyalPurplePrimary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                bottomBar = {
                    Surface(
                        tonalElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val summaryText = buildString {
                                        appendLine("==========================================")
                                        appendLine("OFFICIAL TRIAL BALANCE REPORT")
                                        appendLine("Business: ${user.businessName}")
                                        if (user.gstin.isNotBlank()) appendLine("GSTIN: ${user.gstin}")
                                        appendLine("Generated: $currentDateStr")
                                        appendLine("==========================================")
                                        appendLine("Status: ${if (isBalanced) "BALANCED (Equilibrium)" else "UNBALANCED (Diff: ${IndianFormatter.formatRupee(difference)})"}")
                                        appendLine("Total Debit Allocations: ${IndianFormatter.formatRupee(totalDebit)}")
                                        appendLine("Total Credit Sources:    ${IndianFormatter.formatRupee(totalCredit)}")
                                        appendLine("------------------------------------------")
                                        appendLine("ACCOUNT SCHEDULE:")
                                        trialBalance.forEach { item ->
                                            appendLine("${item.name} (${item.groupName}) -> Dr: ${IndianFormatter.formatRupee(item.totalDebit)} | Cr: ${IndianFormatter.formatRupee(item.totalCredit)}")
                                        }
                                        appendLine("==========================================")
                                    }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Full Report", summaryText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Trial Balance statement copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Summary", fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }

                            Button(
                                onClick = {
                                    val csvData = CsvExporter.generateLedgerSummaryCsv(trialBalance)
                                    CsvExporter.shareCsvFile(context, "Trial_Balance_Report.csv", csvData)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Double-Entry Verification Banner
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isBalanced) AccountingGreen.copy(alpha = 0.12f) else AccountingRed.copy(alpha = 0.12f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isBalanced) AccountingGreen.copy(alpha = 0.4f) else AccountingRed.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("tb_report_status_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isBalanced) AccountingGreen else AccountingRed,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isBalanced) Icons.Default.CheckCircle else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = OnAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isBalanced) "DOUBLE-ENTRY BOOKS BALANCED" else "TRIAL BALANCE IMBALANCE DETECTED",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBalanced) AccountingGreen else AccountingRed
                                    )
                                    Text(
                                        text = if (isBalanced)
                                            "Total Debits equal Total Credits (${IndianFormatter.formatRupee(totalDebit)}). Accounting equilibrium verified."
                                        else
                                            "Difference of ${IndianFormatter.formatRupee(difference)} identified between total debit and credit entries.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Key Financial Indicators Grid
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("TOTAL DEBIT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = IndianFormatter.formatRupee(totalDebit),
                                        style = MonospaceTabularTextStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                    Text("Allocations & Assets", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("TOTAL CREDIT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = IndianFormatter.formatRupee(totalCredit),
                                        style = MonospaceTabularTextStyle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoyalPurplePrimary
                                    )
                                    Text("Sources & Liabilities", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = LavenderContainer),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("LEDGERS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${trialBalance.size} Accounts",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkPurpleVariant
                                    )
                                    Text("In Master DB", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Search & Category Filters
                    item {
                        Column {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Filter ledger accounts...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth().testTag("tb_modal_search_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("ALL" to "All", "ASSET" to "Assets", "LIABILITY" to "Liabilities", "REVENUE" to "Revenue", "EXPENSE" to "Expenses", "EQUITY" to "Equity").forEach { (key, label) ->
                                    val isSelected = selectedCategoryFilter == key
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedCategoryFilter = key },
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = RoyalPurplePrimary,
                                            selectedLabelColor = OnAccent
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Schedule Table Header
                    item {
                        Card(
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkPurpleVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Ledger Account & Group", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = OnAccent, modifier = Modifier.weight(1.5f))
                                Text("Debit (₹)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = OnAccent, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                                Text("Credit (₹)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = OnAccent, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    if (filteredList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No ledger accounts found matching search criteria.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(filteredList) { item ->
                            Card(
                                shape = RoundedCornerShape(0.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1.5f)) {
                                            Text(
                                                text = item.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${item.groupName} • ${item.category.name}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = if (item.totalDebit > 0) IndianFormatter.formatRupee(item.totalDebit, false) else "-",
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.End,
                                            fontWeight = if (item.totalDebit > 0) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = if (item.totalCredit > 0) IndianFormatter.formatRupee(item.totalCredit, false) else "-",
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.End,
                                            fontWeight = if (item.totalCredit > 0) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    // Table Grand Total Footer
                    item {
                        Card(
                            shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = LavenderContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("SUBTOTAL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkPurpleVariant, modifier = Modifier.weight(1.5f))
                                Text(
                                    text = IndianFormatter.formatRupee(filteredDebitSum, false),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkPurpleVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = IndianFormatter.formatRupee(filteredCreditSum, false),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkPurpleVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
