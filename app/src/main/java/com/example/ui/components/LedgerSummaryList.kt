package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.LedgerWithBalance
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.AccountingRed
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.MonospaceTabularTextStyle
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerSummaryList(
    ledgers: List<LedgerWithBalance>,
    onLedgerClick: ((LedgerWithBalance) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredLedgers = remember(ledgers, searchQuery) {
        if (searchQuery.isBlank()) {
            ledgers
        } else {
            val q = searchQuery.trim().lowercase()
            ledgers.filter { l ->
                l.name.lowercase().contains(q) ||
                        l.groupName.lowercase().contains(q) ||
                        l.category.name.lowercase().contains(q)
            }
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ledger_summary_list_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "LEDGER ACCOUNTS & BALANCES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RoyalPurplePrimary,
                letterSpacing = 1.sp
            )
            Text(
                text = "Summary of ledger accounts, total debits, total credits, and closing balances",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar for Ledgers
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ledgers by name or group...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ledger_search_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Column Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ledger Name",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.2f)
                )
                Text(
                    text = "Total Debit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccountingGreen,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Total Credit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccountingRed,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Closing Balance",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoyalPurplePrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (filteredLedgers.isEmpty()) {
                Text(
                    text = if (searchQuery.isNotBlank()) "No ledgers matching '$searchQuery'." else "No ledger accounts found.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filteredLedgers, key = { it.id }) { ledger ->
                        Surface(
                            onClick = { onLedgerClick?.invoke(ledger) },
                            enabled = onLedgerClick != null,
                            shape = RoundedCornerShape(12.dp),
                            color = LavenderContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(
                                        text = ledger.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${ledger.groupName} (${ledger.category.name})",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    text = IndianFormatter.formatRupee(ledger.totalDebit),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 11.sp,
                                    color = AccountingGreen,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = IndianFormatter.formatRupee(ledger.totalCredit),
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 11.sp,
                                    color = AccountingRed,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )

                                val isDebit = ledger.currentBalance >= 0
                                Text(
                                    text = "${if (isDebit) "+" else "-"}${IndianFormatter.formatRupee(Math.abs(ledger.currentBalance))}",
                                    style = MonospaceTabularTextStyle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDebit) AccountingGreen else AccountingRed,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
