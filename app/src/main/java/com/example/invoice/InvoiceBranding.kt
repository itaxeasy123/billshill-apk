package com.example.invoice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Everything about an invoice the user is allowed to change, and nothing that affects what
 * the document asserts.
 *
 * Deliberately excluded: amounts, tax heads, GSTIN, document number and date. Those are
 * statutory content — a tax invoice the issuer can restyle is fine, one whose totals can
 * be typed over is a forgery tool.
 */
data class InvoiceBranding(
    /** Banner and accent colour, as an opaque ARGB int. */
    val accentArgb: Int = DEFAULT_ACCENT,
    val logo: LogoChoice = LogoChoice.ITAXEASY,
    /** Absolute path to the user's own logo. Only meaningful when [logo] is [LogoChoice.CUSTOM]. */
    val customLogoPath: String = "",
    /** Prints instead of the profile's business name when set. */
    val companyNameOverride: String = "",
    /** Prints instead of the document type's default heading when set. */
    val titleOverride: String = "",
    val terms: String = "",
    val footerNote: String = DEFAULT_FOOTER
) {
    enum class LogoChoice {
        /** The iTaxEasy mark shipped in res/drawable-nodpi/ic_itaxeasy_logo.png. */
        ITAXEASY,

        /** An image the user picked; see [customLogoPath]. */
        CUSTOM,

        /** No mark. The company name carries the letterhead on its own. */
        NONE
    }

    /**
     * The custom logo file, when one is actually usable.
     *
     * Returns null if the choice is not CUSTOM, the path was never set, or the file has
     * gone — a logo the user set months ago must not be able to fail an export today.
     */
    fun resolvedCustomLogo(): File? {
        if (logo != LogoChoice.CUSTOM || customLogoPath.isBlank()) return null
        return File(customLogoPath).takeIf { it.isFile && it.canRead() }
    }

    /**
     * The mark that will actually print, after falling back.
     *
     * A CUSTOM choice whose file is missing degrades to the iTaxEasy mark rather than to a
     * blank space, so a broken path never silently produces an unbranded invoice.
     */
    fun effectiveLogo(): LogoChoice = when (logo) {
        LogoChoice.CUSTOM -> if (resolvedCustomLogo() != null) LogoChoice.CUSTOM else LogoChoice.ITAXEASY
        else -> logo
    }

    companion object {
        /** iTaxEasy brand blue — the #0055d4 the logo itself is drawn in. */
        const val DEFAULT_ACCENT = 0xFF0055D4.toInt()

        const val DEFAULT_FOOTER = "Thank you for your business!"

        /**
         * The accents offered in the customiser.
         *
         * Each is dark enough to carry white text at the contrast the banner needs, which
         * is why the palette is fixed rather than a free colour wheel: the banner title is
         * always white, so a user-chosen pale yellow would print an invisible heading.
         */
        val PRESET_ACCENTS: List<Pair<String, Int>> = listOf(
            "iTaxEasy Blue" to DEFAULT_ACCENT,
            "Slate" to 0xFF2C5265.toInt(),
            "Royal Purple" to 0xFF651FFF.toInt(),
            "Emerald" to 0xFF0F7B5A.toInt(),
            "Crimson" to 0xFFB3123C.toInt(),
            "Charcoal" to 0xFF2E2E38.toInt(),
            "Teal" to 0xFF0E6E78.toInt(),
            "Burnt Orange" to 0xFFB2510E.toInt()
        )
    }
}

private val Context.invoiceBrandingStore: DataStore<Preferences> by
    preferencesDataStore(name = "invoice_branding")

/**
 * Persists [InvoiceBranding].
 *
 * Its own DataStore file rather than a set of keys in user_settings, so branding travels
 * as one unit and clearing it cannot disturb theme or backup preferences.
 */
class InvoiceBrandingStore(private val context: Context) {

    companion object {
        val KEY_ACCENT = intPreferencesKey("accent_argb")
        val KEY_LOGO_CHOICE = stringPreferencesKey("logo_choice")
        val KEY_CUSTOM_LOGO_PATH = stringPreferencesKey("custom_logo_path")
        val KEY_COMPANY_NAME = stringPreferencesKey("company_name_override")
        val KEY_TITLE = stringPreferencesKey("title_override")
        val KEY_TERMS = stringPreferencesKey("terms")
        val KEY_FOOTER = stringPreferencesKey("footer_note")

        /** Where a user-supplied logo is copied to. */
        const val LOGO_FILE_NAME = "invoice_logo.png"

        /**
         * filesDir, not cacheDir.
         *
         * The signature pad next door writes to cacheDir, which Android may evict under
         * storage pressure at any time and which is excluded from backup — so a logo kept
         * there would vanish without the user doing anything and the invoice would quietly
         * revert to unbranded. filesDir is already a FileProvider root (file_paths.xml).
         */
        fun logoFile(context: Context): File = File(context.filesDir, LOGO_FILE_NAME)

        /**
         * The signature drawn in Settings' signature pad, if one has been saved.
         *
         * SignaturePadDialog writes it here, and the invoice stamps it above the signatory
         * rule. The path is cacheDir because that is where the pad has always written it;
         * both sides must agree, so this is the one definition of it.
         */
        fun signatureFile(context: Context): File? =
            File(context.cacheDir, "authorized_signature.png").takeIf { it.isFile && it.canRead() }
    }

    val brandingFlow: Flow<InvoiceBranding> = context.invoiceBrandingStore.data.map { prefs ->
        InvoiceBranding(
            accentArgb = prefs[KEY_ACCENT] ?: InvoiceBranding.DEFAULT_ACCENT,
            logo = prefs[KEY_LOGO_CHOICE]
                ?.let { stored ->
                    runCatching { InvoiceBranding.LogoChoice.valueOf(stored) }
                        .getOrDefault(InvoiceBranding.LogoChoice.ITAXEASY)
                }
                ?: InvoiceBranding.LogoChoice.ITAXEASY,
            customLogoPath = prefs[KEY_CUSTOM_LOGO_PATH].orEmpty(),
            companyNameOverride = prefs[KEY_COMPANY_NAME].orEmpty(),
            titleOverride = prefs[KEY_TITLE].orEmpty(),
            terms = prefs[KEY_TERMS].orEmpty(),
            footerNote = prefs[KEY_FOOTER] ?: InvoiceBranding.DEFAULT_FOOTER
        )
    }

    suspend fun save(branding: InvoiceBranding) {
        context.invoiceBrandingStore.edit { prefs ->
            prefs[KEY_ACCENT] = branding.accentArgb
            prefs[KEY_LOGO_CHOICE] = branding.logo.name
            prefs[KEY_CUSTOM_LOGO_PATH] = branding.customLogoPath
            prefs[KEY_COMPANY_NAME] = branding.companyNameOverride
            prefs[KEY_TITLE] = branding.titleOverride
            prefs[KEY_TERMS] = branding.terms
            prefs[KEY_FOOTER] = branding.footerNote
        }
    }
}
