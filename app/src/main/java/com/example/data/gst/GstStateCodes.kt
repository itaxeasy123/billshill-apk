package com.example.data.gst

import java.util.Locale

/**
 * The one GST state-code table.
 *
 * It lived as a private function inside [GstAutomationEngine], so the only way to
 * discover that a ledger's state would abort the export was to run the export and watch
 * it throw. The audit checks need the same table to say so beforehand, and two copies of
 * a lookup like this drift — the same argument [GstClassifier] makes for being the single
 * classifier of B2B/B2CL/B2CS.
 *
 * Returns null rather than a default. This used to end in `else -> "07"`, which quietly
 * filed every unrecognised state as Delhi.
 */
object GstStateCodes {

    fun codeFor(stateName: String): String? =
        when (stateName.trim().lowercase(Locale.US)) {
            "jammu & kashmir", "jammu and kashmir" -> "01"
            "himachal pradesh" -> "02"
            "punjab" -> "03"
            "chandigarh" -> "04"
            "uttarakhand" -> "05"
            "haryana" -> "06"
            "delhi", "new delhi" -> "07"
            "rajasthan" -> "08"
            "uttar pradesh" -> "09"
            "bihar" -> "10"
            "sikkim" -> "11"
            "arunachal pradesh" -> "12"
            "nagaland" -> "13"
            "manipur" -> "14"
            "mizoram" -> "15"
            "tripura" -> "16"
            "meghalaya" -> "17"
            "assam" -> "18"
            "west bengal" -> "19"
            "jharkhand" -> "20"
            "odisha" -> "21"
            "chhattisgarh" -> "22"
            "madhya pradesh" -> "23"
            "gujarat" -> "24"
            "daman & diu", "dadra & nagar haveli" -> "26"
            "maharashtra" -> "27"
            "andhra pradesh" -> "28"
            "karnataka" -> "29"
            "goa" -> "30"
            "lakshadweep" -> "31"
            "kerala" -> "32"
            "tamil nadu" -> "33"
            "puducherry" -> "34"
            "andaman & nicobar islands" -> "35"
            "telangana" -> "36"
            "ladakh" -> "38"
            else -> null
        }

    /** The two-digit state prefix of a well-formed GSTIN, or null if there isn't one. */
    fun fromGstin(gstin: String?): String? {
        val g = gstin?.trim().orEmpty()
        return if (g.length >= 2 && g.take(2).all { it.isDigit() }) g.take(2) else null
    }
}
