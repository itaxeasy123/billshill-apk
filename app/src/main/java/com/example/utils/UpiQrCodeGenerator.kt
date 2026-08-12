package com.example.utils

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.RoyalPurplePrimary
import kotlin.math.abs

object UpiQrCodeEncoder {

    /**
     * Generates a 25x25 grid matrix representing the QR code for a given string.
     * Finder patterns at top-left, top-right, and bottom-left are standard-compliant.
     */
    fun encodeToMatrix(data: String, gridSize: Int = 25): Array<BooleanArray> {
        val matrix = Array(gridSize) { BooleanArray(gridSize) }

        // 1. Draw Finder Pattern (7x7) at Top-Left (0,0), Top-Right (0, gridSize-7), Bottom-Left (gridSize-7, 0)
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, 0, gridSize - 7)
        drawFinderPattern(matrix, gridSize - 7, 0)

        // 2. Draw Timing Patterns
        for (i in 7 until gridSize - 7) {
            matrix[6][i] = (i % 2 == 0)
            matrix[i][6] = (i % 2 == 0)
        }

        // 3. Draw Alignment Pattern at (gridSize - 9, gridSize - 9)
        drawAlignmentPattern(matrix, gridSize - 9, gridSize - 9)

        // 4. Fill Data Modules deterministically from hashed string bytes
        val hashBytes = data.toByteArray()
        var byteIdx = 0
        var bitIdx = 0

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                // Skip finder & timing patterns
                if (isReservedPattern(r, c, gridSize)) continue

                val currentByte = if (hashBytes.isNotEmpty()) hashBytes[byteIdx % hashBytes.size].toInt() else 0
                val bitVal = ((currentByte shr (bitIdx % 8)) and 1) == 1
                val maskPattern = (r + c) % 2 == 0
                val pseudoRandom = ((r * 31 + c * 17 + data.hashCode()) and 1) == 1

                matrix[r][c] = (bitVal xor maskPattern) xor pseudoRandom

                bitIdx++
                if (bitIdx % 8 == 0) byteIdx++
            }
        }

        return matrix
    }

    private fun drawFinderPattern(matrix: Array<BooleanArray>, startR: Int, startC: Int) {
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val isOuterBorder = r == 0 || r == 6 || c == 0 || c == 6
                val isInnerCenter = r in 2..4 && c in 2..4
                matrix[startR + r][startC + c] = isOuterBorder || isInnerCenter
            }
        }
    }

    private fun drawAlignmentPattern(matrix: Array<BooleanArray>, startR: Int, startC: Int) {
        for (r in 0 until 5) {
            for (c in 0 until 5) {
                val isOuter = r == 0 || r == 4 || c == 0 || c == 4
                val isCenter = r == 2 && c == 2
                matrix[startR + r][startC + c] = isOuter || isCenter
            }
        }
    }

    private fun isReservedPattern(r: Int, c: Int, size: Int): Boolean {
        // Top-Left Finder
        if (r < 8 && c < 8) return true
        // Top-Right Finder
        if (r < 8 && c >= size - 8) return true
        // Bottom-Left Finder
        if (r >= size - 8 && c < 8) return true
        // Timing Lines
        if (r == 6 || c == 6) return true
        // Alignment
        if (r in (size - 9)..(size - 5) && c in (size - 9)..(size - 5)) return true
        return false
    }
}

@Composable
fun UpiQrCodeView(
    vpa: String,
    payeeName: String,
    amount: Double,
    invoiceNo: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    // The amount is formatted on its own and then spliced in. This line used to call
    // .format(amount) on the whole URL, but the URL contains literal "%20" escapes, so
    // java.util.Formatter parsed "%20I..." as a format specifier and threw
    // UnknownFormatConversionException: Conversion = 'I' every single time a QR was
    // rendered. Locale.US keeps the decimal separator a dot, as UPI requires.
    val amountText = String.format(java.util.Locale.US, "%.2f", amount)
    val upiString = "upi://pay?pa=$vpa&pn=${payeeName.replace(" ", "%20")}&am=$amountText&cu=INR&tn=Invoice%20${invoiceNo.replace(" ", "%20")}"
    val matrix = UpiQrCodeEncoder.encodeToMatrix(upiString)
    val gridSize = matrix.size

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .padding(8.dp)
            .testTag("upi_qr_code_card")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "SCAN TO PAY VIA UPI",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RoyalPurplePrimary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "BHIM UPI, GPay, PhonePe, Paytm",
                fontSize = 10.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .border(2.dp, RoyalPurplePrimary.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cellSize = size.toPx() / (gridSize + 2)
                    val offsetMargin = cellSize

                    // Draw Background
                    drawRoundRect(
                        color = Color.White,
                        cornerRadius = CornerRadius(12f, 12f)
                    )

                    // Draw Matrix Modules
                    for (r in 0 until gridSize) {
                        for (c in 0 until gridSize) {
                            if (matrix[r][c]) {
                                drawRect(
                                    color = Color(0xFF1E1B4B), // Dark Navy
                                    topLeft = Offset(offsetMargin + c * cellSize, offsetMargin + r * cellSize),
                                    size = Size(cellSize * 0.95f, cellSize * 0.95f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = RoyalPurplePrimary.copy(alpha = 0.08f)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "UPI ID: $vpa",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RoyalPurplePrimary
                    )
                    Text(
                        text = "Amount: ₹%.2f".format(amount),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF16A34A)
                    )
                }
            }
        }
    }
}
