package com.example.ui.components

/**
 * The arithmetic behind the analytics charts, pulled out of the Canvas lambdas.
 *
 * Both defects these functions exist to prevent lived inside `Canvas { }` blocks, where
 * nothing in this repo can execute them: an invented axis floor that made a real month
 * look like nothing happened, and loss bars drawn into 16dp of padding so that every loss
 * past a threshold rendered as the same stub. Extracted so the maths can be asserted on
 * the JVM, which is the only shape this project has test infrastructure for.
 */
data class ChartAxis(
    val max: Double,
    val min: Double
) {
    /** Never zero, so callers can divide. Only reachable when every figure is zero. */
    val span: Double get() = (max - min).takeIf { it > 0.0 } ?: 1.0

    /**
     * Where zero sits inside the drawable band.
     *
     * With no negatives in the series this is exactly the baseline, so books without a
     * loss month render as they always did.
     */
    fun zeroY(height: Float, padY: Float, drawH: Float): Float =
        height - padY - ((0.0 - min) / span * drawH).toFloat()

    /** Bar length: magnitude scaled to the axis. */
    fun barHeight(value: Double, drawH: Float): Float =
        (kotlin.math.abs(value) / span * drawH).toFloat()

    /** Positives grow up from zero, negatives grow down from it. */
    fun barTop(value: Double, height: Float, padY: Float, drawH: Float): Float {
        val zero = zeroY(height, padY, drawH)
        return if (value >= 0.0) zero - barHeight(value, drawH) else zero
    }

    /** Y for a line-chart point. */
    fun y(value: Double, height: Float, padY: Float, drawH: Float): Float =
        height - padY - (((value - min) / span).toFloat() * drawH)

    companion object {
        /**
         * Headroom on real data, with no invented floor.
         *
         * The axis was `max(observed, 50_000.0) * 1.15`, so it never scaled below
         * Rs 57,500 — and with nothing on screen naming the scale, a trader with Rs 4,000
         * of income that month got a bar 7% of chart height that read as "nothing
         * happened" rather than "a small month". The 1.15 stays: headroom proportional to
         * the data is legitimate; a constant floor is not.
         */
        fun forValues(values: List<Double>, headroom: Double = 1.15): ChartAxis {
            val dataMax = values.maxOrNull() ?: 0.0
            val dataMin = values.minOrNull() ?: 0.0
            return ChartAxis(
                max = if (dataMax > 0.0) dataMax * headroom else 0.0,
                min = if (dataMin < 0.0) dataMin * headroom else 0.0
            )
        }
    }
}
