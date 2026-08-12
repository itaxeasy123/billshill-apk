package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.LedgerWithBalance
import com.example.data.dao.MonthlyPnlRow
import com.example.data.model.LedgerCategory
import com.example.ui.theme.*
import com.example.utils.IndianFormatter
import kotlin.math.max
import kotlin.math.min

data class MonthlyTrendItem(
    val monthName: String,
    val income: Double,
    val expense: Double,
    val netProfit: Double = income - expense
)

data class ExpenseCategoryItem(
    val categoryName: String,
    val amount: Double,
    val percentage: Float,
    val color: Color
)

@Composable
fun AnalyticsSummaryChartCard(
    monthlyPnlRows: List<MonthlyPnlRow>,
    rangeStartMillis: Long,
    rangeEndMillis: Long,
    trialBalance: List<LedgerWithBalance>,
    modifier: Modifier = Modifier
) {
    var selectedChartTab by remember { mutableStateOf(0) } // 0: Monthly P&L Trend, 1: Expense Categories
    var isBarChartMode by remember { mutableStateOf(false) }

    // 1. Monthly Profit / Loss trend, bucketed from real posted journal entries.
    //    Revenue ledgers are credit-normal, expense ledgers debit-normal. Months with
    //    no activity are zero-filled so the axis stays continuous.
    val monthlyData = remember(monthlyPnlRows, rangeStartMillis, rangeEndMillis) {
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
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = rangeStartMillis }

        val result = mutableListOf<MonthlyTrendItem>()
        var guard = 0
        while (cal.timeInMillis <= rangeEndMillis && guard < 24) {
            val key = keyFmt.format(cal.time)
            result.add(
                MonthlyTrendItem(
                    monthName = labelFmt.format(cal.time),
                    income = revenueByMonth[key] ?: 0.0,
                    expense = expenseByMonth[key] ?: 0.0
                )
            )
            cal.add(java.util.Calendar.MONTH, 1)
            guard++
        }
        result
    }

    val hasPnlData = monthlyData.any { it.income != 0.0 || it.expense != 0.0 }

    val totalIncome = monthlyData.sumOf { it.income }
    val totalExpense = monthlyData.sumOf { it.expense }
    val netProfit = totalIncome - totalExpense
    val profitMargin = if (totalIncome > 0) (netProfit / totalIncome) * 100 else 0.0

    // 2. Top expense categories, straight from the trial balance. No floor, no filler:
    //    a ledger with no spend simply doesn't appear.
    val expenseItems = remember(trialBalance) {
        val colorPalette = listOf(
            ElectricPurple,
            DeepPurpleSecondary,
            Color(0xFFFF9800),
            Color(0xFF00BCD4),
            Color(0xFFE91E63),
            Color(0xFF4CAF50)
        )

        val items = trialBalance
            .filter { it.category == LedgerCategory.EXPENSE && it.currentBalance > 0.0 }
            .sortedByDescending { it.currentBalance }
            .take(5)
            .mapIndexed { idx, l ->
                ExpenseCategoryItem(
                    categoryName = l.name,
                    amount = l.currentBalance,
                    percentage = 0f,
                    color = colorPalette[idx % colorPalette.size]
                )
            }

        val totalExp = items.sumOf { it.amount }
        items.map { item ->
            val pct = if (totalExp > 0) ((item.amount / totalExp) * 100).toFloat() else 0f
            item.copy(percentage = pct)
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_summary_chart_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(LavenderContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = "Analytics",
                            tint = ElectricPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "FINANCIAL ANALYTICS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricPurple,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Profit/Loss Trends & Expense Categories (accrual)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = LavenderContainer
                ) {
                    Text(
                        text = "Margin: ${String.format("%.1f", profitMargin)}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricPurple,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-Tab Switcher (Monthly P&L vs Expense Categories)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = { selectedChartTab = 0 },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedChartTab == 0) ElectricPurple else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShowChart,
                            contentDescription = null,
                            tint = if (selectedChartTab == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Monthly P&L Trend",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedChartTab == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    onClick = { selectedChartTab = 1 },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedChartTab == 1) ElectricPurple else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PieChart,
                            contentDescription = null,
                            tint = if (selectedChartTab == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Top Expenses",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedChartTab == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedChartTab == 0) {
                // ---------------- MONTHLY PROFIT / LOSS TREND CHART ----------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(AccountingGreen, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Income", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(AccountingRed, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Expense", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(ElectricPurple, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Net Profit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    IconButton(
                        onClick = { isBarChartMode = !isBarChartMode },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isBarChartMode) Icons.Default.ShowChart else Icons.Default.BarChart,
                            contentDescription = "Toggle Chart Mode",
                            tint = ElectricPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (!hasPnlData) {
                    // Nothing posted in this period. Show that honestly rather than
                    // inventing a trend.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ShowChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No transactions in this period",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Record a sale or purchase to see your P&L trend here.",
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (!isBarChartMode) {
                    // Line Spline Canvas Chart
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        val width = size.width
                        val height = size.height
                        val padX = 16.dp.toPx()
                        val padY = 16.dp.toPx()

                        val drawW = width - (padX * 2)
                        val drawH = height - (padY * 2)

                        val maxVal = max(
                            monthlyData.maxOfOrNull { max(it.income, it.expense) } ?: 50000.0,
                            50000.0
                        ) * 1.15
                        // A loss-making month is real information — let the axis drop below
                        // zero instead of clamping the net-profit line flat on the baseline.
                        val lowest = monthlyData.minOfOrNull {
                            min(min(it.income, it.expense), it.netProfit)
                        } ?: 0.0
                        val minVal = if (lowest < 0) lowest * 1.15 else 0.0

                        fun getY(v: Double): Float {
                            val norm = ((v - minVal) / (maxVal - minVal)).toFloat()
                            return height - padY - (norm * drawH)
                        }

                        fun getX(idx: Int): Float {
                            val step = if (monthlyData.size > 1) drawW / (monthlyData.size - 1) else 0f
                            return padX + (idx * step)
                        }

                        val incomePath = Path()
                        val expensePath = Path()
                        val profitPath = Path()

                        monthlyData.forEachIndexed { idx, pt ->
                            val x = getX(idx)
                            val yInc = getY(pt.income)
                            val yExp = getY(pt.expense)
                            val yNet = getY(pt.netProfit)

                            if (idx == 0) {
                                incomePath.moveTo(x, yInc)
                                expensePath.moveTo(x, yExp)
                                profitPath.moveTo(x, yNet)
                            } else {
                                val prevX = getX(idx - 1)
                                val cX = prevX + (x - prevX) / 2
                                incomePath.cubicTo(cX, getY(monthlyData[idx - 1].income), cX, yInc, x, yInc)
                                expensePath.cubicTo(cX, getY(monthlyData[idx - 1].expense), cX, yExp, x, yExp)
                                profitPath.cubicTo(cX, getY(monthlyData[idx - 1].netProfit), cX, yNet, x, yNet)
                            }
                        }

                        // Gradient fill for Profit line
                        val profitFillPath = Path().apply {
                            addPath(profitPath)
                            lineTo(getX(monthlyData.lastIndex), height - padY)
                            lineTo(getX(0), height - padY)
                            close()
                        }
                        drawPath(
                            path = profitFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(ElectricPurple.copy(alpha = 0.2f), Color.Transparent),
                                startY = padY,
                                endY = height - padY
                            )
                        )

                        // Draw Paths
                        drawPath(incomePath, color = AccountingGreen, style = Stroke(width = 2.5.dp.toPx()))
                        drawPath(expensePath, color = AccountingRed, style = Stroke(width = 2.5.dp.toPx()))
                        drawPath(profitPath, color = ElectricPurple, style = Stroke(width = 3.dp.toPx()))

                        // Draw Dots
                        monthlyData.forEachIndexed { idx, pt ->
                            val x = getX(idx)
                            val yNet = getY(pt.netProfit)
                            drawCircle(ElectricPurple, radius = 4.dp.toPx(), center = Offset(x, yNet))
                            drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(x, yNet))
                        }
                    }
                } else {
                    // Grouped Bar Chart Mode
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        val width = size.width
                        val height = size.height
                        val padX = 16.dp.toPx()
                        val padY = 16.dp.toPx()

                        val drawW = width - (padX * 2)
                        val drawH = height - (padY * 2)

                        val maxVal = max(
                            monthlyData.maxOfOrNull { max(it.income, it.expense) } ?: 50000.0,
                            50000.0
                        ) * 1.15

                        val groupW = drawW / monthlyData.size
                        val barW = groupW * 0.28f

                        monthlyData.forEachIndexed { idx, pt ->
                            val groupLeft = padX + (idx * groupW) + (groupW * 0.1f)

                            val hInc = ((pt.income / maxVal) * drawH).toFloat()
                            val hExp = ((pt.expense / maxVal) * drawH).toFloat()
                            // A loss draws downward from the baseline rather than being
                            // clamped to zero, so a bad month is visible as a loss.
                            val hNet = ((kotlin.math.abs(pt.netProfit) / maxVal) * drawH).toFloat()
                            val netIsLoss = pt.netProfit < 0

                            // Income Bar
                            drawRect(
                                color = AccountingGreen,
                                topLeft = Offset(groupLeft, height - padY - hInc),
                                size = Size(barW, hInc)
                            )

                            // Expense Bar
                            drawRect(
                                color = AccountingRed,
                                topLeft = Offset(groupLeft + barW + 2.dp.toPx(), height - padY - hExp),
                                size = Size(barW, hExp)
                            )

                            // Net Profit Bar
                            drawRect(
                                color = if (netIsLoss) AccountingRed else ElectricPurple,
                                topLeft = Offset(
                                    groupLeft + (barW * 2) + 4.dp.toPx(),
                                    if (netIsLoss) height - padY else height - padY - hNet
                                ),
                                size = Size(barW, hNet)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // X-Axis Month Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    monthlyData.forEach { pt ->
                        Text(
                            text = pt.monthName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Summary Numbers Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LavenderContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Total Revenue", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(totalIncome), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingGreen)
                    }
                    Column {
                        Text("Total Expense", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(totalExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccountingRed)
                    }
                    Column {
                        Text("Net Profit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(netProfit), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ElectricPurple)
                    }
                }
            } else if (expenseItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PieChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No expenses recorded yet",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Post a purchase or expense voucher to see the breakdown.",
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // ---------------- TOP EXPENSE CATEGORIES BREAKDOWN ----------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Donut Arc Chart
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var startAngle = -90f
                            expenseItems.forEach { item ->
                                val sweepAngle = (item.percentage / 100f) * 360f
                                drawArc(
                                    color = item.color,
                                    startAngle = startAngle,
                                    sweepAngle = sweepAngle - 2f,
                                    useCenter = false,
                                    style = Stroke(width = 16.dp.toPx())
                                )
                                startAngle += sweepAngle
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Top 5", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricPurple)
                            Text("Expenses", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Category Progress Distribution List
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        expenseItems.forEach { item ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(modifier = Modifier.size(8.dp).background(item.color, CircleShape))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.categoryName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = IndianFormatter.formatRupee(item.amount),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                LinearProgressIndicator(
                                    progress = { item.percentage / 100f },
                                    color = item.color,
                                    trackColor = item.color.copy(alpha = 0.15f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
