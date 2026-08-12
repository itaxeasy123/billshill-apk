package com.example.utils

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rounding for money.
 *
 * There was none anywhere in this codebase — no `BigDecimal`, no `RoundingMode` — and
 * that omission, not floating-point drift, is what produced the real defects:
 *
 *  - `IndianFormatter` truncated the rupee part while rounding the paise part, so a
 *    taxable value of exactly ₹19,262.00 rendered as "₹19,261.100" — a wrong rupee digit
 *    and a three-digit paise field, on the tax invoice handed to the customer.
 *  - CGST and SGST were each rounded independently downstream, so they need not add up
 *    to the total GST: a ₹1,000.05 base at 18% gives halves summing to ₹180.00 against a
 *    total of ₹180.01.
 *  - Raw Doubles went straight into the GSTR JSON, emitting values like
 *    `762.7118644067797`. Every monetary field in the GSTR-1/3B schema is capped at two
 *    decimal places, so the portal rejects that outright.
 *
 * Binary FP error in the posting path itself measures around 1e-11 per voucher — nine
 * orders of magnitude below a paisa — so storage stays `Double` and no column changes.
 * BigDecimal is used as the rounding *engine* at the point money is created, not as a
 * storage type.
 */
object Money {

    /**
     * Two decimal places, HALF_UP — the granularity every GST monetary field uses.
     *
     * Constructed from `toString()`, never `BigDecimal(double)`: the double constructor
     * carries the full binary expansion (0.1 becomes 0.1000000000000000055511...) and
     * would defeat the rounding it is being asked to perform.
     */
    fun paise(value: Double): Double =
        BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()

    /**
     * Whole rupees, HALF_UP — CGST Act s.170.
     *
     * "…shall be rounded off to the nearest rupee and… if such part is fifty paise or
     * more, it shall be increased to one rupee and if such part is less than fifty paise
     * it shall be ignored."
     *
     * Applies to tax *payable*, per tax head separately — CGST, SGST and IGST are levies
     * under separate Acts and are never rounded as a combined total. Not for line items,
     * which stay at paise.
     */
    fun rupees(value: Double): Double =
        BigDecimal(value.toString()).setScale(0, RoundingMode.HALF_UP).toDouble()

    /** True when [value] is already exactly representable at paise precision. */
    fun isQuantised(value: Double): Boolean = paise(value) == value
}
