package com.athar.core.designsystem.display

import java.util.Locale

/**
 * WM077 — deterministic Arabic-Indic digit shaping for short numeric labels
 * (month/year captions, percentage captions) that are built via plain string
 * templates rather than [java.text.NumberFormat].
 *
 * Android's ICU-backed formatters keep Latin digit shapes for `ar` locales and
 * ignore `DecimalFormatSymbols.zeroDigit` / `-u-nu-arab` on device (desktop JVM
 * honors both, so unit tests alone can't catch it — recorded in WM071). The only
 * reliable fix is transliterating ASCII digits after the fact. Money keeps using
 * [MoneyTextFormat]; this helper is for inline caption numerals only.
 */
object AtharNumerals {

    private const val ARABIC_INDIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    /** Map 0-9 → ٠-٩ and ASCII separators → Arabic copy separators (٫ / ٬). */
    fun String.withArabicIndicDigits(): String = buildString {
        for (c in this@withArabicIndicDigits) {
            when {
                c in '0'..'9' -> append(ARABIC_INDIC_DIGITS[c - '0'])
                c == '.' -> append('٫')
                c == ',' -> append('٬')
                else -> append(c)
            }
        }
    }

    /**
     * Localize an already-built numeric label for the given locale: Arabic locales
     * get Arabic-Indic digits and Arabic separators, everything else is unchanged.
     * Never reorders content — digit shapes only.
     */
    fun localize(label: String, locale: Locale): String =
        if (locale.language == "ar") label.withArabicIndicDigits() else label
}
