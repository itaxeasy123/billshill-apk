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
import androidx.compose.ui.text.style.TextOverflow
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
    /**
     * Per-ledger REVENUE/EXPENSE movement for the same range as [monthlyPnlRows].
     *
     * Named for what it must be. This took the unbounded trial balance, so the donut
     * showed lifetime spend beside a date-ranged trend under one date header.
     */
    periodExpenseLedgers: List<LedgerWithBalance>,
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
    val expenseItems = remember(periodExpenseLedgers) { expenseBreakdown(periodExpenseLedgers) }

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
                // The title block carries weight(1f) and the margin badge is measured
                // first. Without it the title claimed the entire row, the badge was
                // measured against 0dp of leftover, and "Margin: 0.0%" wrapped to one
                // character per line — a 12-line-tall sliver at the right edge that
                // dragged the header (and the blank space around its centred contents)
                // to roughly three times its proper height.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
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
                            letterSpacing = 0.8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Profit and loss trends, and expenses by ledger",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = LavenderContainer
                ) {
                    Text(
                        text = "Margin ${String.format("%.1f", profitMargin)}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricPurple,
                        maxLines = 1,
                        softWrap = false,
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
                            tint = if (selectedChartTab == 0) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Monthly P&L Trend",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedChartTab == 0) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant
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
                            tint = if (selectedChartTab == 1) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Expenses",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedChartTab == 1) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
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

                        // Headroom on real data, no invented floor. `max(observed, 50000.0)`
                        // meant the axis never dropped below Rs 57,500 — and with nothing on
                        // screen naming the scale, a trader with Rs 4,000 of income that month
                        // got a bar 7% of chart height that read as "nothing happened".
                        // ChartAxis, not an inline copy: this arithmetic lived inside the
                        // Canvas lambda where no test in this repo could execute it, which
                        // is how an invented Rs 50,000 floor survived. See ChartMathTest.
                        val axis = ChartAxis.forValues(
                            monthlyData.flatMap { listOf(it.income, it.expense, it.netProfit) }
                        )

                        fun getY(v: Double): Float = axis.y(v, height, padY, drawH)

                        // Where zero actually sits. With no negatives this is the baseline.
                        val zeroY = axis.zeroY(height, padY, drawH)

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
                            drawCircle(ChartMarker, radius = 2.dp.toPx(), center = Offset(x, yNet))
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

                        // Same tested ChartAxis as the line canvas. These were two separate
                        // inline copies and had already drifted — the bar canvas carried no
                        // floor for negatives at all, so losses were drawn from the baseline
                        // downward into 16dp of padding and clipped: a Rs 78,000 loss and a
                        // Rs 5,00,000 loss rendered identically. ChartMathTest proves no bar
                        // can leave the canvas at any scale.
                        val axis = ChartAxis.forValues(
                            monthlyData.flatMap { listOf(it.income, it.expense, it.netProfit) }
                        )
                        val zeroY = axis.zeroY(height, padY, drawH)

                        fun barHeight(value: Double): Float = axis.barHeight(value, drawH)
                        fun barTop(value: Double, h: Float): Float =
                            axis.barTop(value, height, padY, drawH)

                        drawLine(
                            color = SubtleBorder.copy(alpha = 0.35f),
                            start = Offset(padX, zeroY),
                            end = Offset(width - padX, zeroY),
                            strokeWidth = 1.dp.toPx()
                        )

                        val groupW = drawW / monthlyData.size
                        val barW = groupW * 0.28f

                        monthlyData.forEachIndexed { idx, pt ->
                            val groupLeft = padX + (idx * groupW) + (groupW * 0.1f)

                            val hInc = barHeight(pt.income)
                            val hExp = barHeight(pt.expense)
                            val hNet = barHeight(pt.netProfit)
                            val netIsLoss = pt.netProfit < 0

                            // Income Bar
                            drawRect(
                                color = AccountingGreen,
                                topLeft = Offset(groupLeft, barTop(pt.income, hInc)),
                                size = Size(barW, hInc)
                            )

                            // Expense Bar
                            drawRect(
                                color = AccountingRed,
                                topLeft = Offset(groupLeft + barW + 2.dp.toPx(), barTop(pt.expense, hExp)),
                                size = Size(barW, hExp)
                            )

                            // Net Profit Bar
                            drawRect(
                                color = if (netIsLoss) AccountingRed else ElectricPurple,
                                topLeft = Offset(
                                    groupLeft + (barW * 2) + 4.dp.toPx(),
                                    barTop(pt.netProfit, hNet)
                                ),
                                size = Size(barW, hNet)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // X-Axis Month Labels. One equal slot per month, centred under its data
                // point: twelve intrinsic-width labels under SpaceBetween needed more
                // width than the card had, so a full financial year ran off the edge.
                Row(modifier = Modifier.fillMaxWidth()) {
                    monthlyData.forEach { pt ->
                        Text(
                            text = pt.monthName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Equal thirds. Under SpaceBetween these three columns sized to their
                    // amounts, so a book with lakh-scale figures pushed the last one past
                    // the edge of the card.
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Revenue", fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(totalIncome), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AccountingGreen)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Expense", fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(totalExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AccountingRed)
                    }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("Net profit", fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(IndianFormatter.formatRupee(netProfit), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ElectricPurple)
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
                            // The ring covers all expenses now, not the top five.
                            Text("All", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricPurple)
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


/**
 * The expense donut's slices, as pure data.
 *
 * Percentages are of TOTAL expenses. The denominator used to be computed AFTER `.take(5)`,
 * so the five slices always summed to 100% and the ring always closed 360 degrees: twelve
 * ledgers totalling 10,00,000 with a top five of 6,00,000 showed Rent at 33.3% when it was
 * 20% — every slice inflated 1.67x, with nothing on screen admitting a remainder existed.
 *
 * The remainder gets its own slice rather than the ring being left open. A gap does not
 * read as "there is more"; it reads as a rendering glitch, and the reader most likely to be
 * hurt is the one who does not notice it. "Other (7 ledgers)" answers the question a trader
 * actually has — how much am I not seeing, and how many things are in it — and it is what
 * makes true percentages and a closed ring compatible.
 *
 * Extracted from the composable so the arithmetic can be tested on the JVM.
 */
fun expenseBreakdown(ledgers: List<LedgerWithBalance>): List<ExpenseCategoryItem> {
        val colorPalette = listOf(
            ElectricPurple,
            DeepPurpleSecondary,
            ChartSlice1,
            ChartSlice2,
            ChartSlice3,
            ChartSlice4
        )

        val otherColor = ChartSlice5

        val allExpenses = ledgers
            .filter { it.category == LedgerCategory.EXPENSE && it.currentBalance > 0.0 }
            .sortedByDescending { it.currentBalance }

        val totalExp = allExpenses.sumOf { it.currentBalance }
        fun pctOf(amount: Double): Float =
            if (totalExp > 0.0) ((amount / totalExp) * 100).toFloat() else 0f

        val top = allExpenses.take(5)
        val rest = allExpenses.drop(5)

        val topItems = top.mapIndexed { idx, l ->
            ExpenseCategoryItem(
                categoryName = l.name,
                amount = l.currentBalance,
                percentage = pctOf(l.currentBalance),
                color = colorPalette[idx % colorPalette.size]
            )
        }

        return if (rest.isEmpty()) {
            topItems
        } else {
            val restTotal = rest.sumOf { it.currentBalance }
            topItems + ExpenseCategoryItem(
                categoryName = "Other (${rest.size} ledgers)",
                amount = restTotal,
                percentage = pctOf(restTotal),
                color = otherColor
            )
        }
}