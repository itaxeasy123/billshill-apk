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

    fun convertNumberToWords(amount: Double): String {
        val longVal = amount.toLong()
        if (longVal == 0L) return "Rupees Zero Only"
        
        val units = arrayOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
        val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

        fun convertLessThanThousand(n: Int): String {
            if (n == 0) return ""
            if (n < 20) return units[n]
            val tenStr = tens[n / 10]
            val unitStr = units[n % 10]
            return if (unitStr.isEmpty()) tenStr else "$tenStr $unitStr"
        }

        var num = longVal
        val cr = (num / 10000000).toInt()
        num %= 10000000
        val lakh = (num / 100000).toInt()
        num %= 100000
        val thousand = (num / 1000).toInt()
        num %= 1000
        val hundred = (num / 100).toInt()
        val rest = (num % 100).toInt()

        val result = StringBuilder("Rupees ")
        if (cr > 0) result.append("${convertLessThanThousand(cr)} Crore ")
        if (lakh > 0) result.append("${convertLessThanThousand(lakh)} Lakh ")
        if (thousand > 0) result.append("${convertLessThanThousand(thousand)} Thousand ")
        if (hundred > 0) result.append("${units[hundred]} Hundred ")
        if (rest > 0) result.append("${convertLessThanThousand(rest)} ")
        result.append("Only")

        return result.toString().replace("\\s+".toRegex(), " ").trim()
    }
}
