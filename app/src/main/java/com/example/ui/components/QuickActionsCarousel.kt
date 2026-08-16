package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.LavenderContainer

data class QuickActionItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun QuickActionsCarousel(
    onOpenQrScanner: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenPnl: () -> Unit,
    onOpenPrintInvoice: () -> Unit,
    onOpenGstSummary: () -> Unit,
    onOpenLedgerManager: (() -> Unit)? = null,
    onOpenCustomVoucher: (() -> Unit)? = null,
    onOpenGstCalculator: (() -> Unit)? = null,
    onOpenReconciliation: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val quickActions = buildList {
        if (onOpenReconciliation != null) {
            add(
                QuickActionItem(
                    title = "Auto Reconcile",
                    subtitle = "Match Ledgers",
                    icon = Icons.Default.CompareArrows,
                    onClick = onOpenReconciliation
                )
            )
        }
        if (onOpenGstCalculator != null) {
            add(
                QuickActionItem(
                    title = "GST Calculator",
                    subtitle = "Slabs & IGST/CGST",
                    icon = Icons.Default.Calculate,
                    onClick = onOpenGstCalculator
                )
            )
        }
        add(
            QuickActionItem(
                title = "Quick Invoice",
                subtitle = "Manual entry",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                onClick = onOpenQrScanner
            )
        )
        if (onOpenLedgerManager != null) {
            add(
                QuickActionItem(
                    title = "Ledgers (CRUD)",
                    subtitle = "Create & Modify",
                    icon = Icons.Default.Assessment,
                    onClick = onOpenLedgerManager
                )
            )
        }
        if (onOpenCustomVoucher != null) {
            add(
                QuickActionItem(
                    title = "Custom Expense",
                    subtitle = "Car, Loans & Misc",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    onClick = onOpenCustomVoucher
                )
            )
        }
        add(
            QuickActionItem(
                title = "Reports",
                subtitle = "All Ledgers",
                icon = Icons.Default.Assessment,
                onClick = onOpenReports
            )
        )
        add(
            QuickActionItem(
                title = "Profit & Loss",
                subtitle = "Income & Exp",
                icon = Icons.Default.PieChart,
                onClick = onOpenPnl
            )
        )
        add(
            QuickActionItem(
                title = "Print Invoice",
                subtitle = "PDF Print / Share",
                icon = Icons.Default.Print,
                onClick = onOpenPrintInvoice
            )
        )
        add(
            QuickActionItem(
                title = "GST Summary",
                subtitle = "GSTR-1 & 3B",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                onClick = onOpenGstSummary
            )
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "QUICK ACTIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ElectricPurple,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Compact Access →",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .testTag("quick_actions_carousel_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickActions.forEach { action ->
                Surface(
                    onClick = action.onClick,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        ElectricPurple.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.width(104.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(LavenderContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = action.title,
                                tint = ElectricPurple,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = action.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = action.subtitle,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
