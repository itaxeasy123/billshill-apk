package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.FundsGroups
import com.example.data.dao.LedgerWithBalance
import com.example.data.model.LedgerCategory
import com.example.ui.theme.*
import com.example.utils.IndianFormatter

@Composable
fun LedgerGroupSummaryCard(
    trialBalance: List<LedgerWithBalance>,
    modifier: Modifier = Modifier
) {
    var selectedSectionTab by remember { mutableStateOf(0) }
    val sectionTabs = listOf("Group Balances", "Cash", "Bank", "Customers & Suppliers")

    // Membership is the ledger's GROUP, never its name — matching the name counted a
    // customer called "HDFC Bank Ltd" as money in the bank, and any group containing both
    // words (the app's own "Cash/Bank Accounts") landed in both buckets at once. Shares
    // the whitelist with the DAO's IS_CASH / IS_BANK predicates.
    val cashLedgers = trialBalance.filter { FundsGroups.isCash(it.groupName) }
    val totalCashBalance = cashLedgers.sumOf { it.currentBalance }

    val bankLedgers = trialBalance.filter { FundsGroups.isBank(it.groupName) }
    val totalBankBalance = bankLedgers.sumOf { it.currentBalance }

    // Filter Party ledgers (Sundry Debtors & Sundry Creditors)
    val debtorLedgers = trialBalance.filter { 
        it.groupName.contains("Debtor", ignoreCase = true) || it.name.contains("Customer", ignoreCase = true)
    }
    val totalDebtorsBalance = debtorLedgers.sumOf { it.currentBalance }

    val creditorLedgers = trialBalance.filter { 
        it.groupName.contains("Creditor", ignoreCase = true) || it.name.contains("Vendor", ignoreCase = true) || it.name.contains("Supplier", ignoreCase = true)
    }
    val totalCreditorsBalance = creditorLedgers.sumOf { it.currentBalance }

    // Grouping all ledgers by groupName
    val groupMap = trialBalance.groupBy { it.groupName.ifBlank { it.category.name } }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ledger_group_summary_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LEDGER GROUP CLOSING BALANCES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Closing balances across all ledger groups",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = RoyalPurplePrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Sub-tabs: Groups, Cash, Bank, Customers & Suppliers
            ScrollableTabRow(
                selectedTabIndex = selectedSectionTab,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                sectionTabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedSectionTab == idx,
                        onClick = { selectedSectionTab = idx },
                        text = {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (selectedSectionTab == idx) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedSectionTab == idx) RoyalPurplePrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedSectionTab) {
                0 -> {
                    // ALL LEDGER GROUPS SUMMARY
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupMap.forEach { (grpName, ledgersInGrp) ->
                            val grpDebit = ledgersInGrp.sumOf { it.totalDebit }
                            val grpCredit = ledgersInGrp.sumOf { it.totalCredit }
                            val grpNetBal = ledgersInGrp.sumOf { it.currentBalance }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = grpName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${ledgersInGrp.size} Accounts • Dr: ${IndianFormatter.formatRupee(grpDebit)} | Cr: ${IndianFormatter.formatRupee(grpCredit)}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = IndianFormatter.formatRupee(Math.abs(grpNetBal)),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (grpNetBal >= 0) AccountingGreen else AccountingRed
                                        )
                                        Text(
                                            text = if (grpNetBal >= 0) "Closing Balance (DR)" else "Closing Balance (CR)",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (grpNetBal >= 0) AccountingGreen else AccountingRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // CASH ACCOUNTS SECTION
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AccountingGreen.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = AccountingGreen)
                                Column {
                                    Text("Cash Accounts Total", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Liquid cash available", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                text = IndianFormatter.formatRupee(totalCashBalance),
                                style = MonospaceTabularTextStyle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccountingGreen
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cashLedgers.isEmpty()) {
                            Text("No Cash ledgers recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            cashLedgers.forEach { ledger ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(ledger.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = IndianFormatter.formatRupee(ledger.currentBalance),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccountingGreen
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // BANK ACCOUNTS SECTION
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DeepPurpleSecondary.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.AccountBalance, contentDescription = null, tint = DeepPurpleSecondary)
                                Column {
                                    Text("Bank Accounts Total", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Current & Savings balances", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                text = IndianFormatter.formatRupee(totalBankBalance),
                                style = MonospaceTabularTextStyle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepPurpleSecondary
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bankLedgers.isEmpty()) {
                            Text("No Bank accounts recorded.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            bankLedgers.forEach { ledger ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(ledger.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = IndianFormatter.formatRupee(ledger.currentBalance),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DeepPurpleSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // CUSTOMERS & SUPPLIERS SECTION
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
                                Text("Customers (Receivables)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RoyalPurplePrimary)
                                Text("${debtorLedgers.size} Parties", fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    IndianFormatter.formatRupee(totalDebtorsBalance),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurplePrimary
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AccountingRed.copy(alpha = 0.12f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Suppliers (Payables)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                                Text("${creditorLedgers.size} Parties", fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    IndianFormatter.formatRupee(Math.abs(totalCreditorsBalance)),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccountingRed
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Party Accounts", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    val allParties = (debtorLedgers + creditorLedgers)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (allParties.isEmpty()) {
                            Text("No party accounts added yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            allParties.forEach { party ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(party.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(party.groupName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            text = IndianFormatter.formatRupee(Math.abs(party.currentBalance)),
                                            style = MonospaceTabularTextStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (party.currentBalance >= 0) AccountingGreen else AccountingRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
