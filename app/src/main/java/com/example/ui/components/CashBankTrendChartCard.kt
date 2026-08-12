package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.BalanceTrendPoint
import com.example.ui.theme.AccountingGreen
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary
import com.example.utils.IndianFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Cash & Bank balance history.
 *
 * [points] are genuine running balances derived from dated journal postings
 * (see AccountingRepository.cashBankTrendFlow) — this component does no data
 * generation of its own. "Cash" here means Cash-in-Hand only; the Bank series is
 * separate, so the two never double-count.
 */
@Composable
fun CashBankTrendChartCard(
    points: List<BalanceTrendPoint>,
    hasAnyActivity: Boolean,
    modifier: Modifier = Modifier
) {
    // Keyed on size so a live DB update doesn't reset the user's inspected point,
    // but an empty -> populated transition does.
    var selectedPointIndex by remember(points.size) { mutableStateOf(points.lastIndex) }
    val activePoint = points.getOrNull(selectedPointIndex.coerceIn(0, max(points.size - 1, 0)))

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("cash_bank_trend_chart_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "30-DAY CASH & BANK BALANCE TRENDS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Closing balance per day, from your posted entries",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = LavenderContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "30D",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (!hasAnyActivity || points.isEmpty() || activePoint == null) {
                Spacer(modifier = Modifier.height(20.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No activity yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Record a sale, purchase, receipt or payment to see your cash & bank trend.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                return@Column
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Legend + the currently inspected day's real closing balances
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(AccountingGreen, CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cash in Hand: ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        IndianFormatter.formatRupee(activePoint.cashBalance),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccountingGreen
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(RoyalPurplePrimary, CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Bank: ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        IndianFormatter.formatRupee(activePoint.bankBalance),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "as on ${activePoint.displayLabel}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            val cashColor = AccountingGreen
            val bankColor = RoyalPurplePrimary

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val width = size.width
                val height = size.height
                val paddingX = 20.dp.toPx()
                val paddingY = 20.dp.toPx()

                val drawWidth = width - (paddingX * 2)
                val drawHeight = height - (paddingY * 2)

                val maxVal = max(
                    points.maxOfOrNull { max(it.cashBalance, it.bankBalance) } ?: 10000.0,
                    10000.0
                ) * 1.15
                // Real books can go negative (overdraft, payments ahead of receipts).
                val lowest = points.minOfOrNull { min(it.cashBalance, it.bankBalance) } ?: 0.0
                val minVal = if (lowest < 0) lowest * 1.15 else 0.0

                fun getY(value: Double): Float {
                    val normalized = ((value - minVal) / (maxVal - minVal)).toFloat()
                    return height - paddingY - (normalized * drawHeight)
                }

                fun getX(index: Int): Float {
                    val step = if (points.size > 1) drawWidth / (points.size - 1) else 0f
                    return paddingX + (index * step)
                }

                val cashPath = Path()
                val bankPath = Path()

                points.forEachIndexed { index, point ->
                    val x = getX(index)
                    val yCash = getY(point.cashBalance)
                    val yBank = getY(point.bankBalance)

                    if (index == 0) {
                        cashPath.moveTo(x, yCash)
                        bankPath.moveTo(x, yBank)
                    } else {
                        val prevX = getX(index - 1)
                        val prevYCash = getY(points[index - 1].cashBalance)
                        val prevYBank = getY(points[index - 1].bankBalance)

                        val controlX1 = prevX + (x - prevX) / 2
                        cashPath.cubicTo(controlX1, prevYCash, controlX1, yCash, x, yCash)
                        bankPath.cubicTo(controlX1, prevYBank, controlX1, yBank, x, yBank)
                    }
                }

                val bankFillPath = Path().apply {
                    addPath(bankPath)
                    lineTo(getX(points.lastIndex), height - paddingY)
                    lineTo(getX(0), height - paddingY)
                    close()
                }
                drawPath(
                    path = bankFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(bankColor.copy(alpha = 0.25f), Color.Transparent),
                        startY = paddingY,
                        endY = height - paddingY
                    )
                )

                drawPath(path = bankPath, color = bankColor, style = Stroke(width = 3.dp.toPx()))
                drawPath(path = cashPath, color = cashColor, style = Stroke(width = 3.dp.toPx()))

                // Only mark weekly points plus today — 30 dots would be noise.
                points.forEachIndexed { index, point ->
                    if (index % 7 != 0 && index != points.lastIndex) return@forEachIndexed
                    val x = getX(index)
                    val yCash = getY(point.cashBalance)
                    val yBank = getY(point.bankBalance)

                    drawCircle(color = bankColor, radius = 4.dp.toPx(), center = Offset(x, yBank))
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(x, yBank))

                    drawCircle(color = cashColor, radius = 4.dp.toPx(), center = Offset(x, yCash))
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(x, yCash))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                points.filterIndexed { idx, _ -> idx % 7 == 0 || idx == points.lastIndex }
                    .forEach { pt ->
                        Text(
                            text = pt.displayLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
            }
        }
    }
}
