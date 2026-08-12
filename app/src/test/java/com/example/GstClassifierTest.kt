package com.example

import com.example.data.gst.GstClassifier
import com.example.data.gst.SupplyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GSTR-1 supply classification (H8, H12, H25).
 *
 * There were three implementations that disagreed with each other, and all three tested
 * the SELLER's registration — so once the business had a GSTIN, every sale it made was
 * classified B2B and Table 7 came out empty.
 */
class GstClassifierTest {

    private val buyerGstin = "27AAPFU0939F1ZV"   // 15 chars, Maharashtra

    @Test
    fun `a registered buyer is B2B`() {
        assertEquals(
            SupplyCategory.B2B,
            GstClassifier.classify(buyerGstin, isInterstate = false, invoiceValue = 5_000.0)
        )
    }

    @Test
    fun `a walk-in consumer is never B2B, however large the sale`() {
        // The defining bug: `user.gstin.isNotBlank()` is the seller's registration, so a
        // registered business reported its cash sales to consumers as B2B invoices.
        listOf(500.0, 50_000.0, 5_00_000.0).forEach { value ->
            val category = GstClassifier.classify(null, isInterstate = false, invoiceValue = value)
            assertTrue("$value should not be B2B", category != SupplyCategory.B2B)
        }
        assertEquals(
            SupplyCategory.B2CS,
            GstClassifier.classify("", isInterstate = false, invoiceValue = 5_00_000.0)
        )
    }

    @Test
    fun `B2CL threshold is one lakh, not two and a half`() {
        // Rule 59(4) as amended by Notification 12/2024-CT, with effect from 1 Aug 2024.
        // At the old 2.5L figure every interstate consumer sale between 1L and 2.5L was
        // reported in the aggregate B2CS table instead of invoice-wise in 5A.
        assertEquals(100_000.0, GstClassifier.B2CL_THRESHOLD, 0.0)

        assertEquals(
            SupplyCategory.B2CL,
            GstClassifier.classify(null, isInterstate = true, invoiceValue = 150_000.0)
        )
        assertEquals(
            SupplyCategory.B2CS,
            GstClassifier.classify(null, isInterstate = true, invoiceValue = 99_999.0)
        )
    }

    @Test
    fun `the threshold is exclusive at exactly one lakh`() {
        assertEquals(
            SupplyCategory.B2CS,
            GstClassifier.classify(null, isInterstate = true, invoiceValue = 100_000.0)
        )
        assertEquals(
            SupplyCategory.B2CL,
            GstClassifier.classify(null, isInterstate = true, invoiceValue = 100_000.01)
        )
    }

    @Test
    fun `a large intrastate consumer sale stays B2CS`() {
        // B2CL is an inter-state category only; an intra-state consumer sale of any size
        // is reported in aggregate.
        assertEquals(
            SupplyCategory.B2CS,
            GstClassifier.classify(null, isInterstate = false, invoiceValue = 10_00_000.0)
        )
    }

    @Test
    fun `registration requires a full fifteen character GSTIN`() {
        assertTrue(GstClassifier.isRegistered(buyerGstin))
        assertFalse(GstClassifier.isRegistered(null))
        assertFalse(GstClassifier.isRegistered(""))
        assertFalse(GstClassifier.isRegistered("27AAPFU0939F1Z"))    // 14
        assertFalse(GstClassifier.isRegistered("27AAPFU0939F1ZVX"))  // 16
        assertTrue(GstClassifier.isRegistered("  $buyerGstin  "))    // trimmed
    }

    @Test
    fun `each category reports into its own GSTR-1 table`() {
        assertEquals("4A", SupplyCategory.B2B.table)
        assertEquals("5A", SupplyCategory.B2CL.table)
        assertEquals("7", SupplyCategory.B2CS.table)
    }
}
