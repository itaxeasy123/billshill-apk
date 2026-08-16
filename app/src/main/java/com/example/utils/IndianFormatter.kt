package com.example.utils

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IndianFormatter {

    /**
     * Formats Double values into Indian Rupee format with Lakhs and Crores.
     * e.g., 1234567.89 -> ₹12,34,567.89
     */
    fun formatRupee(amount: Double, includeSymbol: Boolean = true): String {
        val absoluteVal = Math.abs(amount)
        val isNegative = amount < 0

        val formattedText = formatIndianNumber(absoluteVal)
        val prefix = if (isNegative) "- " else ""
        val symbol = if (includeSymbol) "₹" else ""

        return "$prefix$symbol$formattedText"
    }

    private fun formatIndianNumber(value: Double): String {
        // Quantise ONCE, then split. The rupee part used to truncate while the paise part
        // rounded, so a value sitting one ulp below an integer produced a rupee digit one
        // too low AND a paise value of 100 — rendering an exact Rs 19,262.00 as
        // "19,261.100". Three-digit paise, wrong rupees, on the printed tax invoice.
        val quantised = java.math.BigDecimal(value.toString())
            .setScale(2, java.math.RoundingMode.HALF_UP)
        val longVal = quantised.toBigInteger().toLong()
        val decimalVal = quantised.subtract(java.math.BigDecimal(longVal))
            .movePointRight(2)
            .toLong()

        val strVal = longVal.toString()
        val length = strVal.length

        if (length <= 3) {
            val decStr = String.format(Locale.ENGLISH, "%02d", decimalVal)
            return "$strVal.$decStr"
        }

        val lastThree = strVal.substring(length - 3)
        val rest = strVal.substring(0, length - 3)

        val sb = StringBuilder()
        var count = 0
        for (i in rest.length - 1 downTo 0) {
            sb.append(rest[i])
            count++
            if (count == 2 && i != 0) {
                sb.append(",")
                count = 0
            }
        }

        val formattedRest = sb.reverse().toString()
        val decStr = String.format(Locale.ENGLISH, "%02d", decimalVal)

        return "$formattedRest,$lastThree.$decStr"
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        return sdf.format(Date(timestamp))
    }

    fun formatDateWithTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
        return sdf.format(Date(timestamp))
    }

    /**
     * A stock quantity as recorded.
     *
     * `Double.toInt()` truncates toward zero, so 12.75 kg printed as "12 kg" and -0.5 kg
     * printed as "0 kg" — inside a badge coloured red for being negative, so the sign
     * vanished from the number while the colour still claimed it. Quantities are Doubles
     * throughout and fractional units are ordinary in trade.
     *
     * Three decimals, trailing zeros dropped, so whole quantities stay clean.
     */
    fun formatQuantity(qty: Double): String {
        if (qty.isNaN() || qty.isInfinite()) return "-"
        return java.math.BigDecimal(qty.toString())
            .setScale(3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Amount in words in the Indian convention a tax invoice requires: whole rupees, then
     * the paise — 1456.78 becomes "Rupees One Thousand Four Hundred Fifty Six and Seventy
     * Eight Paise Only".
     *
     * `amount.toLong()` discarded the paise outright, so every invoice not ending in .00
     * printed a words line that disagreed with the figure beside it on the same page.
     * Quantisation is the same BigDecimal HALF_UP the rest of this object uses, and for
     * the same reason: truncation also disagreed at the rupee boundary, printing
     * "Rupees ... Fifty Six Only" under a figure of Rs 1,457.00.
     *
     * Negative amounts previously produced the bare string "Rupees Only" — every append
     * was guarded `> 0` and Long division truncates toward zero, so no branch fired. They
     * are spelled explicitly now.
     *
     * The old helper was named for a range it could not handle: it indexed `tens[n / 10]`
     * off the end of a 10-element array for any n >= 100, and the crore group was passed
     * to it unbounded — so any amount from Rs 100 crore upward threw mid-PDF.
     */
    fun convertNumberToWords(amount: Double): String {
        if (!amount.isFinite()) return "Rupees Zero Only"

        val quantised = java.math.BigDecimal(amount.toString())
            .setScale(2, java.math.RoundingMode.HALF_UP)
        val isNegative = quantised.signum() < 0
        val absolute = quantised.abs()
        val rupees = absolute.toBigInteger().toLong()
        val paise = absolute.subtract(java.math.BigDecimal(rupees)).movePointRight(2).toLong()

        val units = arrayOf(
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"
        )
        val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

        fun below100(n: Long): String = when {
            n <= 0L -> ""
            n < 20L -> units[n.toInt()]
            else -> listOf(tens[(n / 10).toInt()], units[(n % 10).toInt()])
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }

        // Indian grouping: crore, lakh, thousand, hundred, remainder. Recurses on the
        // crore group so 1,00,00,00,00,000 reads "One Lakh Crore" instead of crashing.
        fun spell(value: Long): String {
            if (value <= 0L) return ""
            val parts = mutableListOf<String>()
            val cr = value / 10_000_000L
            var rest = value % 10_000_000L
            if (cr > 0) parts += "${spell(cr)} Crore"
            val lakh = rest / 100_000L
            rest %= 100_000L
            if (lakh > 0) parts += "${below100(lakh)} Lakh"
            val thousand = rest / 1_000L
            rest %= 1_000L
            if (thousand > 0) parts += "${below100(thousand)} Thousand"
            val hundred = rest / 100L
            rest %= 100L
            if (hundred > 0) parts += "${units[hundred.toInt()]} Hundred"
            if (rest > 0) parts += below100(rest)
            return parts.joinToString(" ")
        }

        val rupeeWords = spell(rupees)
        val paiseWords = below100(paise)

        val body = when {
            rupeeWords.isEmpty() && paiseWords.isEmpty() -> "Zero"
            rupeeWords.isEmpty() -> "Zero and $paiseWords Paise"
            paiseWords.isEmpty() -> rupeeWords
            else -> "$rupeeWords and $paiseWords Paise"
        }

        val prefix = if (isNegative) "Minus Rupees " else "Rupees "
        return "$prefix$body Only".replace("\\s+".toRegex(), " ").trim()
    }
}
