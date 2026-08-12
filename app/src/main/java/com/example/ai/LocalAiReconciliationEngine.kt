package com.example.ai

import android.util.Log
import java.util.Locale
import java.util.regex.Pattern

/**
 * On-Device AI Reconciliation Layer for parsing unformatted merchant entry notes
 * (Jama/Udhar/Sales notes) into structured parameters and performing automated error cleanup
 * prior to Room DB mapping and GST filing.
 */
object LocalAiReconciliationEngine {

    private const val TAG = "LocalAiReconciliation"
    private const val MAX_INVOICE_NUMBER_LENGTH = 16 // Strict Government GSTIN inum limit

    /**
     * Result of parsing a raw merchant note. Every extracted field is nullable, and null
     * means "this was not found in the text" -- not a default.
     *
     * These fields used to be non-null and were filled in with invented values whenever
     * extraction failed: customer "Cash Customer" / "Merchant Client" / "Apex Trade
     * Client", tax rate 18.0, state code "07" (Delhi), invoice number "INV-001" or one
     * generated from the clock. The UI then presented all of it as "AI Extracted",
     * so a note that parsed badly still produced a complete, confident-looking, and
     * entirely fictitious GST entry.
     */
    data class ParsedMerchantNote(
        val originalNote: String,
        val customerName: String?,
        val taxableValue: Double?,
        val taxRate: Double?,
        val stateCode: String?,
        val invoiceNumber: String?,
        val cleanedText: String
    )

    /**
     * Parses and reconciles unformatted raw text notes (e.g. "Sharma ji kirana - Rs.15000 with 18% tax state 07")
     * into structured accounting parameters with automated cleanup. Anything the text does
     * not actually contain comes back null.
     */
    fun reconcileAndCleanNote(rawNote: String): ParsedMerchantNote {
        if (rawNote.isBlank()) {
            return ParsedMerchantNote(
                originalNote = rawNote,
                customerName = null,
                taxableValue = null,
                taxRate = null,
                stateCode = null,
                invoiceNumber = null,
                cleanedText = ""
            )
        }

        // 1. Regional shorthand normalization & symbol cleanup
        var normalized = rawNote
            .replace("(?i)Rs\\.|Rupees|INR".toRegex(), " ")
            .replace("(?i)Lakh|Lac".toRegex(), "00000")
            .replace("(?i)pct|Percent|%".toRegex(), " percent ")
            .replace("(?i)Sharma ji|Sharmaji".toRegex(), "Sharma Ji")
            .replace("(?i)kirana store|kirana".toRegex(), "Kirana")
            .replace("(?i)udhar|jama".toRegex(), "")

        // 2. Extract Invoice Number (inum) and truncate strictly to 16 characters
        var extractedInum = extractInvoiceNumber(normalized)
        if (extractedInum != null && extractedInum.length > MAX_INVOICE_NUMBER_LENGTH) {
            extractedInum = extractedInum.substring(0, MAX_INVOICE_NUMBER_LENGTH)
        }

        // 3. Extract Taxable Value (numeric amount)
        val taxableValue = extractAmount(normalized)

        // 4. Extract Tax Rate (e.g. 5, 12, 18, 28)
        val taxRate = extractTaxRate(normalized)

        // 5. Extract State Code (2-digit POS e.g. 07, 27, 09, 33)
        val stateCode = extractStateCode(normalized)

        // 6. Extract Cleaned Customer Name
        val customerName = extractCustomerName(normalized)

        // 7. Strip invalid special characters for clean output
        val cleanedText = stripInvalidSpecialChars(normalized)

        Log.d(TAG, "Reconciled Note: Name='$customerName', Val=$taxableValue, Rate=$taxRate%, State=$stateCode, Inum='$extractedInum'")

        return ParsedMerchantNote(
            originalNote = rawNote,
            customerName = customerName,
            taxableValue = taxableValue,
            taxRate = taxRate,
            stateCode = stateCode,
            invoiceNumber = extractedInum,
            cleanedText = cleanedText
        )
    }

    /**
     * Strips invalid special characters while preserving standard alphanumeric, spaces, hyphens, and slashes.
     */
    fun stripInvalidSpecialChars(input: String): String {
        return input.replace("[^a-zA-Z0-9\\s\\-/]".toRegex(), " ").trim().replace("\\s+".toRegex(), " ")
    }

    /**
     * Truncates invoice strings ('inum') to strictly fit the 16-character government GST limit.
     * Returns an empty string when there is nothing to sanitise: this used to mint an
     * invoice number from the system clock ("INV-<millis>"), which then travelled into the
     * GSTR-1 payload as a document the user had never issued.
     */
    fun sanitizeInvoiceNumber(rawInum: String): String {
        val clean = rawInum.replace("[^a-zA-Z0-9\\-/]".toRegex(), "").uppercase(Locale.US)
        if (clean.isBlank()) return ""
        return if (clean.length > MAX_INVOICE_NUMBER_LENGTH) {
            clean.substring(0, MAX_INVOICE_NUMBER_LENGTH)
        } else {
            clean
        }
    }

    // Each extractor below returns null when the pattern does not match. They previously
    // returned confident defaults -- 0.0, 18.0, "07", a clock-derived invoice number and
    // the invented client names -- which the caller could not distinguish from a real hit.

    private fun extractAmount(text: String): Double? {
        val pattern = Pattern.compile("(?i)(?:val|value|amount|amt|total|rs)?\\s*[:=]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\b")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            matcher.group(1)?.toDoubleOrNull()?.let { return it }
        }

        // Fallback: Find first significant number > 50
        val numbersPattern = Pattern.compile("\\b([0-9]{3,7}(?:\\.[0-9]{1,2})?)\\b")
        val numMatcher = numbersPattern.matcher(text)
        if (numMatcher.find()) {
            numMatcher.group(1)?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun extractTaxRate(text: String): Double? {
        val pattern = Pattern.compile("(?i)(?:tax|gst|rate)?\\s*[:=]?\\s*(5|12|18|28|0)(?:\\s*percent|\\s*%)?")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.toDoubleOrNull()
        }
        return null
    }

    private fun extractStateCode(text: String): String? {
        val pattern = Pattern.compile("(?i)(?:state|pos|code)?\\s*[:=]?\\s*(0[1-9]|[1-3][0-9]|9[7-9])\\b")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    private fun extractInvoiceNumber(text: String): String? {
        val pattern = Pattern.compile("(?i)(?:inv|invoice|num|inum|bill|no)?\\s*[:=]?\\s*([a-zA-Z0-9\\-/]{3,20})\\b")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            val candidate = matcher.group(1)
            if (candidate != null && candidate.length >= 3 && !candidate.equals("STATE", true) && !candidate.equals("RS", true)) {
                return sanitizeInvoiceNumber(candidate).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractCustomerName(text: String): String? {
        val words = text.split("-", ":", ",").firstOrNull()?.trim() ?: return null
        val clean = stripInvalidSpecialChars(words)
        return if (clean.length in 3..40) clean else null
    }
}
