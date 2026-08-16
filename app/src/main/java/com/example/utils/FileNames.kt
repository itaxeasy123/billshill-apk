package com.example.utils

/**
 * Turns data-derived text into something safe to use as a file name.
 *
 * Every export in this app named its file after user data — a voucher number, a ledger
 * name, a business name — and concatenated it into a path raw. Indian voucher numbers
 * carry slashes by design (`DatabaseSeedEngine` seeds `INV/2026-27/`, and Rule 46(b)
 * numbering conventionally uses them), so `File(cacheDir, "Invoice_INV/2026-27/1001.pdf")`
 * named two directories that do not exist and every PDF export failed in a default book.
 * Ledger and party names reach the same code and routinely contain `/` too — `M/s Sharma
 * Traders` is the standard form, and the app seeds a group called `Suspense A/c`.
 *
 * Note there is a similarly-named [com.example.ai.LocalAiReconciliationEngine]
 * `sanitizeInvoiceNumber`. It is **not** interchangeable with this and must not be used
 * for file names: it deliberately preserves `/` (it cleans a GST document number, where
 * the slash is legitimate) and caps length at 16 characters.
 */
object FileNames {

    /** Anything outside this set is replaced. Deliberately excludes the path separator. */
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private val RUNS = Regex("_{2,}")

    /**
     * A file-name-safe rendering of [raw], falling back to [fallback] when nothing
     * usable survives.
     *
     * Length is capped in UTF-8 **bytes**, not characters, because that is what the
     * filesystem limits — a Devanagari ledger name is three bytes per character. When
     * the cap bites, the **tail** is kept: sequence numbers sit at the end of every
     * voucher number this app generates, so trimming the tail would map every invoice
     * in a book to one name. Callers should still add their own unique discriminator
     * rather than rely on this being injective — it is not, and cannot be.
     */
    fun safe(raw: String, fallback: String = "untitled", maxBytes: Int = 96): String {
        val cleaned = raw
            .replace(UNSAFE, "_")
            .replace(RUNS, "_")
            .trim('_', '.', '-')

        if (cleaned.isBlank()) return fallback
        return trimToBytes(cleaned, maxBytes).ifBlank { fallback }
    }

    /**
     * Keeps [name]'s extension intact while making the stem safe, so a caller-supplied
     * `"Trial_Balance_M/s Foo.csv"` stays a `.csv`. MIME type and the Excel BOM decision
     * are both taken from the extension downstream, so losing it would change how the
     * file is shared, not merely what it is called.
     */
    fun safeWithExtension(name: String, fallbackStem: String = "export"): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return safe(name, fallbackStem)

        // dot == 0 means the name is nothing but an extension, which is what a blank
        // business name produced: "Trial_Balance_${businessName}.csv" collapses toward
        // ".csv". The stem falls back rather than the extension being dropped, so the
        // file stays a .csv and is still shared with the right MIME type.
        val stem = name.substring(0, dot)
        val ext = name.substring(dot + 1)
        val safeExt = ext.replace(UNSAFE, "")
        return if (safeExt.isBlank()) safe(stem, fallbackStem)
        else "${safe(stem, fallbackStem)}.$safeExt"
    }

    /** Drops leading characters until the string fits [maxBytes] encoded as UTF-8. */
    private fun trimToBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var start = 0
        while (start < value.length &&
            value.substring(start).toByteArray(Charsets.UTF_8).size > maxBytes
        ) {
            start++
        }
        return value.substring(start).trim('_', '.', '-')
    }
}
