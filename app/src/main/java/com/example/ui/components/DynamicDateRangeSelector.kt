package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.FiscalYearUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class DateRangeType(val label: String) {
    FINANCIAL_YEAR("Financial Year"),
    QUARTERLY("Quarterly"),
    MONTHLY("Monthly"),
    CUSTOM("Custom Range")
}

data class DateRangeFilterState(
    var selectedType: DateRangeType = DateRangeType.FINANCIAL_YEAR,
    // Defaults track the FY we are actually in. These used to be hardcoded to
    // "FY 2025-26", so reports silently defaulted to a year with no data once the
    // real financial year rolled over.
    var selectedFy: String = FiscalYearUtils.currentFyLabel(),
    var selectedQuarter: String = "Q1 (Apr-Jun)",
    var selectedMonth: String = "July",
    var customStartDate: String = FiscalYearUtils.fyIsoDates(FiscalYearUtils.fyStartYearFor()).first,
    var customEndDate: String = FiscalYearUtils.fyIsoDates(FiscalYearUtils.fyStartYearFor()).second
) {
    val displayLabel: String
        get() = when (selectedType) {
            DateRangeType.FINANCIAL_YEAR -> selectedFy
            DateRangeType.QUARTERLY -> "$selectedQuarter ($selectedFy)"
            DateRangeType.MONTHLY -> "$selectedMonth ($selectedFy)"
            DateRangeType.CUSTOM -> "$customStartDate to $customEndDate"
        }
}

/**
 * Converts the selector's display state into real inclusive epoch-millis bounds so
 * reports can actually be filtered by it. Until this existed the selector was purely
 * decorative — `displayLabel` was the only thing anything read.
 */
fun DateRangeFilterState.toEpochMillisRange(): Pair<Long, Long> {
    val fyStartYear = FiscalYearUtils.parseFyLabel(selectedFy)
    val fy = FiscalYearUtils.fyBounds(fyStartYear)

    fun monthsFromFyStart(offset: Int, span: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = fy.first
            add(Calendar.MONTH, offset)
        }
        val end = (start.clone() as Calendar).apply {
            add(Calendar.MONTH, span)
            add(Calendar.MILLISECOND, -1)
        }
        return start.timeInMillis to end.timeInMillis
    }

    return when (selectedType) {
        DateRangeType.FINANCIAL_YEAR -> fy

        DateRangeType.QUARTERLY ->
            monthsFromFyStart(FiscalYearUtils.fyQuarterOffset(selectedQuarter), 3)

        DateRangeType.MONTHLY ->
            monthsFromFyStart(FiscalYearUtils.fyMonthOffset(selectedMonth), 1)

        DateRangeType.CUSTOM -> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
            val start = runCatching { fmt.parse(customStartDate)?.time }.getOrNull() ?: fy.first
            val endRaw = runCatching { fmt.parse(customEndDate)?.time }.getOrNull() ?: fy.second
            val end = Calendar.getInstance().apply {
                timeInMillis = endRaw
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
            // Guard against a swapped custom range producing an inverted window.
            if (start <= end) start to end else end to start
        }
    }
}

@Composable
fun DynamicDateRangeSelector(
    state: DateRangeFilterState,
    onStateChange: (DateRangeFilterState) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LavenderContainer.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("date_range_selector_card")
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = RoyalPurplePrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "STATUTORY DATE RANGE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = state.displayLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            OutlinedButton(
                onClick = { showDialog = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("change_date_range_btn")
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Change", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDialog) {
        var tempType by remember { mutableStateOf(state.selectedType) }
        var tempFy by remember { mutableStateOf(state.selectedFy) }
        var tempQuarter by remember { mutableStateOf(state.selectedQuarter) }
        var tempMonth by remember { mutableStateOf(state.selectedMonth) }
        var tempStart by remember { mutableStateOf(state.customStartDate) }
        var tempEnd by remember { mutableStateOf(state.customEndDate) }

        val fyOptions = FiscalYearUtils.recentFyLabels(4)
        val quarterOptions = listOf("Q1 (Apr-Jun)", "Q2 (Jul-Sep)", "Q3 (Oct-Dec)", "Q4 (Jan-Mar)")
        val monthOptions = listOf("April", "May", "June", "July", "August", "September", "October", "November", "December", "January", "February", "March")

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Select Statutory Period", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select timeframe for GST, Balance Sheet & P&L calculations:", fontSize = 12.sp)

                    // Type Selector Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DateRangeType.values().forEach { type ->
                            FilterChip(
                                selected = tempType == type,
                                onClick = { tempType = type },
                                label = { Text(type.label, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Options based on selected type
                    when (tempType) {
                        DateRangeType.FINANCIAL_YEAR -> {
                            Text("Financial Year:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Column {
                                fyOptions.forEach { fy ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        RadioButton(selected = tempFy == fy, onClick = { tempFy = fy })
                                        Text(fy, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        DateRangeType.QUARTERLY -> {
                            Text("Select Financial Year:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fyOptions.take(3).forEach { fy ->
                                    FilterChip(
                                        selected = tempFy == fy,
                                        onClick = { tempFy = fy },
                                        label = { Text(fy, fontSize = 10.sp) }
                                    )
                                }
                            }
                            Text("Select Quarter:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Column {
                                quarterOptions.forEach { q ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = tempQuarter == q, onClick = { tempQuarter = q })
                                        Text(q, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        DateRangeType.MONTHLY -> {
                            Text("Select Financial Year:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fyOptions.take(3).forEach { fy ->
                                    FilterChip(
                                        selected = tempFy == fy,
                                        onClick = { tempFy = fy },
                                        label = { Text(fy, fontSize = 10.sp) }
                                    )
                                }
                            }
                            Text("Select Month:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Column {
                                monthOptions.chunked(3).forEach { chunk ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        chunk.forEach { m ->
                                            FilterChip(
                                                selected = tempMonth == m,
                                                onClick = { tempMonth = m },
                                                label = { Text(m, fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        DateRangeType.CUSTOM -> {
                            OutlinedTextField(
                                value = tempStart,
                                onValueChange = { tempStart = it },
                                label = { Text("Start Date (YYYY-MM-DD)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = tempEnd,
                                onValueChange = { tempEnd = it },
                                label = { Text("End Date (YYYY-MM-DD)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onStateChange(
                            DateRangeFilterState(
                                selectedType = tempType,
                                selectedFy = tempFy,
                                selectedQuarter = tempQuarter,
                                selectedMonth = tempMonth,
                                customStartDate = tempStart,
                                customEndDate = tempEnd
                            )
                        )
                        showDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalPurplePrimary)
                ) {
                    Text("Apply Filter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }
}
