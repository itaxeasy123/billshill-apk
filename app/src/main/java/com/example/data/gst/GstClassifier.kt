package com.example.data.gst

/** GSTR-1 supply categories, with the table each one is reported in. */
enum class SupplyCategory(val label: String, val table: String) {
    B2B("B2B", "4A"),
    B2CL("B2C Large", "5A"),
    B2CS("B2C Small", "7")
}

/**
 * The single place a sale is classified for GSTR-1.
 *
 * There were three implementations and they disagreed with each other (H25): the Reports
 * screen, the CSV export, and the JSON automation engine each decided B2B/B2C by their
 * own rule, over the same vouchers. All three were wrong in the same way — they tested
 * `user.gstin.isNotBlank()`, which is the SELLER's registration. Any registered business
 * therefore had every one of its sales classified B2B, including cash sales to walk-in
 * consumers, and the B2CS table came out empty (H8).
 *
 * What actually determines the category:
 *  - **B2B** — the RECIPIENT is registered, i.e. the buyer has a GSTIN. Table 4A.
 *  - **B2CL** — unregistered buyer, inter-state, invoice value above the threshold. 5A.
 *  - **B2CS** — everything else, reported in aggregate rather than invoice-wise. 7.
 */
object GstClassifier {

    /**
     * Invoice-value threshold above which an unregistered inter-state supply is B2CL.
     *
     * **₹1,00,000 with effect from 1 August 2024** — Rule 59(4) as amended by Notification
     * 12/2024-Central Tax. It was ₹2,50,000 before that, which is the figure this app
     * still used (H12), so every interstate consumer sale between ₹1L and ₹2.5L was
     * being reported in the aggregate B2CS table when it should have been listed
     * invoice-wise in 5A.
     *
     * Worth knowing if this is ever re-checked: the GST portal's own tutorial page for
     * B2CL still showed the stale ₹2.5L figure well after the amendment, so the
     * notification is the authority here, not the help text.
     */
    const val B2CL_THRESHOLD = 100_000.0

    /** A GSTIN is 15 characters: 2 state + 10 PAN + 3. */
    fun isRegistered(gstin: String?): Boolean = gstin?.trim()?.length == 15

    /**
     * @param recipientGstin the BUYER's GSTIN — not the seller's.
     * @param invoiceValue total invoice value including tax, which is what the threshold
     *   is measured against.
     */
    fun classify(
        recipientGstin: String?,
        isInterstate: Boolean,
        invoiceValue: Double
    ): SupplyCategory = when {
        isRegistered(recipientGstin) -> SupplyCategory.B2B
        isInterstate && invoiceValue > B2CL_THRESHOLD -> SupplyCategory.B2CL
        else -> SupplyCategory.B2CS
    }
}
