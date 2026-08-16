package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ReconciliationDiscrepancyEntity
import com.example.data.model.ReconciliationStatus
import com.example.ui.theme.*
import com.example.utils.IndianFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerReconciliationModal(
    discrepancies: List<ReconciliationDiscrepancyEntity>,
    unresolvedCount: Int,
    isReconciling: Boolean,
    autoReconciliationEnabled: Boolean,
    lastReconciliationTime: String,
    onDismiss: () -> Unit,
    onRunReconciliationNow: () -> Unit,
    onResolveDiscrepancy: (id: Long, notes: String) -> Unit,
    onToggleAutoReconciliation: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("UNRESOLVED") }
    var resolvingDiscrepancy by remember { mutableStateOf<ReconciliationDiscrepancyEntity?>(null) }
    // Starts empty. This field used to open pre-filled with "Verified with bank statement
    // / client confirmed" -- an audit note asserting a verification nobody performed,
    // written straight into the discrepancy record on confirm. The wording survives only
    // as a placeholder hint, which is not submittable. It is also cleared whenever a
    // discrepancy is opened or resolved, so one item's note cannot carry over to the next.
    var resolutionNotesInput by remember { mutableStateOf("") }

    val filteredList = remember(discrepancies, selectedFilter) {
        when (selectedFilter) {
            "UNRESOLVED" -> discrepancies.filter { !it.isResolved }
            "SHORTFALL" -> discrepancies.filter { !it.isResolved && it.status == ReconciliationStatus.SHORTFALL }
            "EXCESS" -> discrepancies.filter { !it.isResolved && it.status == ReconciliationStatus.EXCESS }
            "UNMATCHED" -> discrepancies.filter { !it.isResolved && it.status == ReconciliationStatus.UNMATCHED_PAYMENT }
            "RESOLVED" -> discrepancies.filter { it.isResolved }
            else -> discrepancies
        }
    }

    val totalUnresolvedDiscrepancyAmount = remember(discrepancies) {
        discrepancies.filter { !it.isResolved }.sumOf { it.discrepancyAmount }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .testTag("ledger_reconciliation_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = CircleShape,
                            color = RoyalPurplePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = null,
                                    tint = RoyalPurplePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LEDGER RECONCILIATION SERVICE",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoyalPurplePrimary,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Background worker matching invoices against receipts",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_reconciliation_modal_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Reconciliation Modal")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Service Status Banner
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (unresolvedCount > 0) AccountingRed.copy(alpha = 0.12f) else AccountingGreen.copy(alpha = 0.12f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (unresolvedCount > 0) AccountingRed.copy(alpha = 0.3f) else AccountingGreen.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (unresolvedCount > 0) AccountingRed else AccountingGreen,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (unresolvedCount > 0) "$unresolvedCount Flagged Discrepancies" else "All Ledgers Reconciled Cleanly",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Button(
                                onClick = onRunReconciliationNow,
                                enabled = !isReconciling,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("run_reconciliation_now_btn")
                            ) {
                                if (isReconciling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = OnAccent,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Scanning...", fontSize = 11.sp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Run Reconcile", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Total Variance / Discrepancy Owed",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = IndianFormatter.formatRupee(totalUnresolvedDiscrepancyAmount),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (totalUnresolvedDiscrepancyAmount > 0) AccountingRed else AccountingGreen
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Last Scan Run",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = lastReconciliationTime,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Auto Worker Switch Setting
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automated Background Reconciliation Worker",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Periodic WorkManager service matches ledger entries every 12 hours",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = autoReconciliationEnabled,
                        onCheckedChange = onToggleAutoReconciliation,
                        colors = SwitchDefaults.colors(
                            // The thumb is white ON the purple track. Setting the
                            // thumb to RoyalPurplePrimary made it the same colour as
                            // the track M3 already derives from colorScheme.primary,
                            // so the thumb vanished and the switch rendered as a
                            // solid purple box with no visible on/off position.
                            checkedThumbColor = OnAccent,
                            checkedTrackColor = RoyalPurplePrimary
                        ),
                        modifier = Modifier.testTag("auto_reconciliation_switch")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filters = listOf(
                        "UNRESOLVED" to "Unresolved ($unresolvedCount)",
                        "SHORTFALL" to "Shortfalls",
                        "EXCESS" to "Advance/Excess",
                        "UNMATCHED" to "Unmatched",
                        "RESOLVED" to "Resolved"
                    )

                    filters.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedFilter == key,
                            onClick = { selectedFilter = key },
                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RoyalPurplePrimary,
                                selectedLabelColor = OnAccent
                            ),
                            modifier = Modifier.testTag("filter_chip_$key")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Discrepancies List
                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AccountingGreen,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No reconciliation discrepancies found!",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "All invoices match received transactions or filter is empty.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredList, key = { it.id }) { discrepancy ->
                            DiscrepancyItemCard(
                                discrepancy = discrepancy,
                                onResolveClick = {
                                    resolutionNotesInput = ""
                                    resolvingDiscrepancy = discrepancy
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close Window", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }

    // Resolve Confirmation Dialog
    resolvingDiscrepancy?.let { item ->
        AlertDialog(
            onDismissRequest = {
                resolvingDiscrepancy = null
                resolutionNotesInput = ""
            },
            title = {
                Text(
                    text = "Resolve Discrepancy",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoyalPurplePrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Marking discrepancy for ${item.partyName} (₹${IndianFormatter.formatRupee(item.discrepancyAmount)}) as resolved.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = resolutionNotesInput,
                        onValueChange = { resolutionNotesInput = it },
                        label = { Text("Resolution Notes / Reason") },
                        placeholder = { Text("e.g. Verified with bank statement / Paid in cash / Discount granted") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("resolution_notes_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResolveDiscrepancy(item.id, resolutionNotesInput)
                        resolvingDiscrepancy = null
                        resolutionNotesInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccountingGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("confirm_resolve_discrepancy_btn")
                ) {
                    Text("Mark Resolved", color = OnAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        resolvingDiscrepancy = null
                        resolutionNotesInput = ""
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DiscrepancyItemCard(
    discrepancy: ReconciliationDiscrepancyEntity,
    onResolveClick: () -> Unit
) {
    val statusColor = when (discrepancy.status) {
        ReconciliationStatus.SHORTFALL -> AccountingRed
        ReconciliationStatus.EXCESS -> AmberGold
        ReconciliationStatus.UNMATCHED_PAYMENT -> RoyalPurplePrimary
        ReconciliationStatus.RESOLVED -> AccountingGreen
    }

    val statusBadgeText = when (discrepancy.status) {
        ReconciliationStatus.SHORTFALL -> "SHORTFALL"
        ReconciliationStatus.EXCESS -> "OVERPAYMENT / ADVANCE"
        ReconciliationStatus.UNMATCHED_PAYMENT -> "UNMATCHED RECEIPT"
        ReconciliationStatus.RESOLVED -> "RESOLVED"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discrepancy_card_${discrepancy.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = discrepancy.partyName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (discrepancy.invoiceVoucherNo.isNotBlank()) {
                        Text(
                            text = "Ref: ${discrepancy.invoiceVoucherNo}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusBadgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Amount breakdown grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Expected Invoice", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        IndianFormatter.formatRupee(discrepancy.expectedAmount),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column {
                    Text("Received / Paid", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        IndianFormatter.formatRupee(discrepancy.receivedAmount),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Variance Difference", fontSize = 10.sp, color = statusColor)
                    Text(
                        IndianFormatter.formatRupee(discrepancy.discrepancyAmount),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = discrepancy.discrepancyReason,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            if (!discrepancy.isResolved) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onResolveClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("resolve_discrepancy_btn_${discrepancy.id}")
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mark Resolved", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Resolved: ${discrepancy.resolutionNotes}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccountingGreen
                )
            }
        }
    }
}
