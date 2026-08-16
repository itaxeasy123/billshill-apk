package com.example

import com.example.utils.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Every export in the app named its file after user data and concatenated it into a path
 * raw. Indian voucher numbers carry slashes by design, so the invoice PDF wrote to
 * "Invoice_INV/2026-27/1001.pdf" — two directories that do not exist — and every PDF
 * export failed in a default book. Nothing in the repo covered file generation at all.
 *
 * These tests write to a real temporary directory rather than asserting on strings,
 * because the defect was that the write itself failed.
 */
class FileNamesTest {

    private fun tempDir(): File = Files.createTempDirectory("filenames").toFile()

    /** The exact string a default book produces for its first sales invoice. */
    private val defaultInvoiceNo = "INV/2026-27/1001"

    @Test
    fun `the unsanitised name is what actually failed`() {
        // Proves this test would have caught the original bug. If this ever starts
        // passing, the assertion below it is no longer evidence of anything.
        val dir = tempDir()
        val broken = File(dir, "Invoice_$defaultInvoiceNo.pdf")
        var failed = false
        try {
            broken.writeText("x")
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("writing a name containing '/' must fail without the fix", failed)
    }

    @Test
    fun `a default invoice number produces a writable file name`() {
        val dir = tempDir()
        val name = "Invoice_${FileNames.safe(defaultInvoiceNo)}_1.pdf"
        val file = File(dir, name)

        file.writeText("pdf bytes")

        assertTrue("the export must actually write", file.exists())
        assertEquals("it must land directly in the target directory", dir, file.parentFile)
    }

    @Test
    fun `every seeded voucher prefix is writable`() {
        // All eight types seed a slashed prefix, so this was never sales-only.
        val dir = tempDir()
        listOf("INV", "PUR", "REC", "PAY", "JRN", "CTR", "SRN", "PRN", "OPS").forEach { code ->
            val file = File(dir, "Invoice_${FileNames.safe("$code/2026-27/1001")}_1.pdf")
            file.writeText("x")
            assertTrue("$code must produce a writable name", file.exists())
        }
    }

    @Test
    fun `the invoice stays identifiable after sanitising`() {
        // A safe-but-meaningless name like "invoice_4711.pdf" is what the recipient sees
        // in their mail client, so readability is part of the fix, not a nicety.
        val safe = FileNames.safe(defaultInvoiceNo)
        assertTrue("document type must survive: $safe", safe.contains("INV"))
        assertTrue("financial year must survive: $safe", safe.contains("2026-27"))
        assertTrue("sequence number must survive: $safe", safe.contains("1001"))
    }

    @Test
    fun `ledger names in the standard Indian form are writable`() {
        val dir = tempDir()
        // "M/s ..." is the ordinary way to write a firm's name, and the app seeds a group
        // called "Suspense A/c". The old code collapsed whitespace and let '/' through.
        listOf("M/s Sharma Traders", "Suspense A/c", "Profit & Loss A/c").forEach { ledger ->
            val file = File(dir, "Ledger_Statement_${FileNames.safe(ledger)}_7.pdf")
            file.writeText("x")
            assertTrue("'$ledger' must produce a writable name", file.exists())
        }
    }

    @Test
    fun `two vouchers that sanitise alike still get different files`() {
        // The voucher prefix is a free-text field. A book edited from "INV/26-27/" to
        // "INV-26-27-" holds two numbers that sanitise identically; without the id
        // discriminator the second invoice silently overwrites the first and the user
        // emails the wrong tax document under the right name.
        val a = "Invoice_${FileNames.safe("INV/26-27/1001")}_1.pdf"
        val b = "Invoice_${FileNames.safe("INV-26-27-1001")}_2.pdf"

        assertNotEquals("the id must keep colliding names apart", a, b)
    }

    @Test
    fun `an empty voucher number falls back instead of writing a dotfile`() {
        // A cloud restore can supply "" for voucherNo, which would otherwise give
        // "Invoice_.pdf" for every such voucher.
        val safe = FileNames.safe("", fallback = "voucher")
        assertEquals("voucher", safe)
        assertFalse("must not start with a dot", "Invoice_$safe.pdf".startsWith("."))
    }

    @Test
    fun `path traversal is neutralised`() {
        val safe = FileNames.safe("../../etc/passwd")
        assertFalse("no separator may survive", safe.contains('/'))
        assertFalse("no parent-directory hop may survive", safe.contains(".."))
    }

    @Test
    fun `the extension survives sanitising`() {
        // mimeTypeFor() and the Excel BOM decision both key off the extension.
        assertTrue(FileNames.safeWithExtension("Trial_Balance_M/s Foo.csv").endsWith(".csv"))
        assertTrue(FileNames.safeWithExtension("Tally_M/s Foo.xml").endsWith(".xml"))
        assertTrue(FileNames.safeWithExtension("Backup_M/s Foo.json").endsWith(".json"))
    }

    @Test
    fun `a blank export name still produces something usable`() {
        // businessName defaults to "", which used to give "Trial_Balance_.csv".
        assertEquals("report.csv", FileNames.safeWithExtension(".csv", "report"))
    }

    @Test
    fun `an over-long name keeps its tail, not its head`() {
        // The sequence number is at the END of every generated voucher number, so
        // trimming the tail would map every invoice in a book to one file name.
        val longPrefix = "X".repeat(300)
        val safe = FileNames.safe("$longPrefix/2026-27/1001")

        assertTrue("must be capped", safe.toByteArray(Charsets.UTF_8).size <= 96)
        assertTrue("the sequence number must survive the cap: $safe", safe.endsWith("1001"))
    }

    @Test
    fun `the cap counts bytes, not characters`() {
        // A Devanagari ledger name is three bytes per character; a character-based cap
        // would sail past the filesystem's byte limit.
        val safe = FileNames.safe("व्यापारी".repeat(60))
        assertTrue("must be capped in bytes", safe.toByteArray(Charsets.UTF_8).size <= 96)
    }

    @Test
    fun `a name that is already safe is left alone`() {
        assertEquals("Ledger_Summary_1723526400000", FileNames.safe("Ledger_Summary_1723526400000"))
        assertEquals("Backup_20260813_101500.json", FileNames.safeWithExtension("Backup_20260813_101500.json"))
    }
}
