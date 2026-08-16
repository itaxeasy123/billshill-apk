package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VoucherType
import com.example.data.model.VoucherTypeConfigEntity
import com.example.ui.AccountingViewModel
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.RoyalPurplePrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherTypeManagementModal(
    viewModel: AccountingViewModel,
    onDismiss: () -> Unit
) {
    val configs by viewModel.voucherConfigsState.collectAsState()

    var editingType by remember { mutableStateOf<String?>(null) }
    var editPrefixText by remember { mutableStateOf("") }
    var editNextNumberText by remember { mutableStateOf("1001") }

    val allVoucherTypes = VoucherType.values()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VOUCHER TYPES & PREFIX NUMBERING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RoyalPurplePrimary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Manage numbering format & custom prefixes",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(allVoucherTypes) { vType ->
                        val typeName = vType.name
                        val config = configs.find { it.voucherType == typeName }
                        val prefix = config?.prefix ?: when (vType) {
                            VoucherType.SALES -> "INV/25-26/"
                            VoucherType.PURCHASE -> "PUR/25-26/"
                            VoucherType.RECEIPT -> "REC/25-26/"
                            VoucherType.PAYMENT -> "PAY/25-26/"
                            VoucherType.JOURNAL -> "JRN/25-26/"
                            VoucherType.CONTRA -> "CTR/25-26/"
                            VoucherType.STOCK_OPENING -> "OPS/25-26/"
                            VoucherType.SALES_RETURN -> "SRN/25-26/"
                            VoucherType.PURCHASE_RETURN -> "PRN/25-26/"
                        }
                        val nextNo = config?.nextNumber ?: 1001L

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = vType.displayName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = RoyalPurplePrimary
                                        )
                                        Text(
                                            text = "Sample: $prefix${String.format("%04d", nextNo)}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (editingType == typeName) {
                                        IconButton(
                                            onClick = {
                                                viewModel.updateVoucherConfig(
                                                    type = typeName,
                                                    prefix = editPrefixText,
                                                    nextNumberText = editNextNumberText,
                                                    autoIncrement = true
                                                )
                                                editingType = null
                                            }
                                        ) {
                                            Icon(Icons.Default.Save, contentDescription = "Save", tint = AccountingGreen)
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                editingType = typeName
                                                editPrefixText = prefix
                                                editNextNumberText = nextNo.toString()
                                            }
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Prefix", tint = RoyalPurplePrimary)
                                        }
                                    }
                                }

                                if (editingType == typeName) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = editPrefixText,
                                            onValueChange = { editPrefixText = it },
                                            label = { Text("Prefix (e.g. INV/25-26/)") },
                                            modifier = Modifier.weight(1.2f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        OutlinedTextField(
                                            value = editNextNumberText,
                                            onValueChange = { editNextNumberText = it },
                                            label = { Text("Next No.") },
                                            modifier = Modifier.weight(0.8f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
        }
    }
}
