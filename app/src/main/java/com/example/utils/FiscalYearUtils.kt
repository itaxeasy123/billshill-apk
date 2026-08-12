package com.example.utils

import java.util.Calendar

/**
 * Single source of truth for Indian Financial Year date maths.
 *
 * The Indian FY runs 1 April -> 31 March. All bounds are computed in the device's
 * local timezone so they line up with the `'localtime'` modifier used by the
 * reporting queries in AccountingDao — voucher dates are stored as UTC epoch
 * millis, but a shopkeeper's "31 March" is an IST calendar day.
 */
object FiscalYearUtils {

    /** Start year of the FY that [atMillis] falls in (e.g. 2025 for "FY 2025-26"). */
    fun fyStartYearFor(atMillis: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = atMillis }
        return if (cal.get(Calendar.MONTH) >= Calendar.APRIL) {
            cal.get(Calendar.YEAR)
        } else {
            cal.get(Calendar.YEAR) - 1
        }
    }

    /** Inclusive [start, end] epoch-millis bounds of the FY beginning in [startYear]. */
    fun fyBounds(startYear: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            clear()
            set(startYear, Calendar.APRIL, 1, 0, 0, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            clear()
            set(startYear + 1, Calendar.MARCH, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return start to end
    }

    /** Bounds of the FY we are currently in. */
    fun currentFyBounds(): Pair<Long, Long> = fyBounds(fyStartYearFor())

    /** Parses "FY 2025-26" / "2025-26" into its start year, falling back to the current FY. */
    fun parseFyLabel(label: String): Int =
        label.removePrefix("FY").trim().substringBefore("-").trim().toIntOrNull()
            ?: fyStartYearFor()

    /** Renders an FY start year as the conventional "FY 2026-27" label. */
    fun fyLabel(startYear: Int): String =
        "FY $startYear-${((startYear + 1) % 100).toString().padStart(2, '0')}"

    /** Label of the FY we are currently in. */
    fun currentFyLabel(): String = fyLabel(fyStartYearFor())

    /** The current FY plus the [count] - 1 preceding ones, oldest first. */
    fun recentFyLabels(count: Int = 4): List<String> {
        val current = fyStartYearFor()
        return ((current - count + 1)..current).map { fyLabel(it) }
    }

    /** "yyyy-MM-dd" bounds of an FY, for the custom-range text fields. */
    fun fyIsoDates(startYear: Int): Pair<String, String> =
        "$startYear-04-01" to "${startYear + 1}-03-31"

    /**
     * Inclusive bounds of a rolling window of [days] whole local days ending today.
     * A 30-day window therefore spans "29 days ago 00:00:00" .. "today 23:59:59.999".
     */
    fun rollingWindowBounds(days: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -(days - 1))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return start to end
    }

    /** Month offset (0-11) of an FY month name, April being 0. */
    fun fyMonthOffset(monthName: String): Int {
        val order = listOf(
            "April", "May", "June", "July", "August", "September",
            "October", "November", "December", "January", "February", "March"
        )
        return order.indexOf(monthName).coerceAtLeast(0)
    }

    /** Month offset (0/3/6/9) for an FY quarter label like "Q3 (Oct-Dec)". */
    fun fyQuarterOffset(quarterLabel: String): Int = when {
        quarterLabel.startsWith("Q1") -> 0
        quarterLabel.startsWith("Q2") -> 3
        quarterLabel.startsWith("Q3") -> 6
        else -> 9
    }
}
