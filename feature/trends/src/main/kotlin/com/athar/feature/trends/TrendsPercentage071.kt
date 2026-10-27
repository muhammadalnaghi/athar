package com.athar.feature.trends

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * WM071 — locale-explicit percentage display for Trends.
 *
 * The app overrides the UI locale ([com.athar.core.designsystem.display.currentUiLocale]),
 * so `String.format` with the OS default locale mixed Latin digits into Arabic text and
 * left huge delta percentages ungrouped. These helpers format through the JDK
 * [NumberFormat] percent instance of the *given* locale, preserving the app's existing
 * rounding policy (0 or 1 fractional digits) and adding locale digit shapes, grouping
 * and the localized percent sign.
 *
 * Inputs are ALREADY percentages: 44.3 means 44.3%, so the value is divided by 100
 * only because the JDK percent formatter multiplies by 100. Callers never re-scale.
 *
 * Non-finite values return null so call sites withhold a meaningless ratio (income of
 * zero, unbounded delta) instead of rendering NaN or Infinity; money is never involved.
 */
object TrendsPercentage071 {

    private const val ARABIC_INDIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    /**
     * Android's ICU-backed java.text formatter ignores both
     * DecimalFormatSymbols.zeroDigit and the -u-nu-arab locale extension on
     * device (desktop JVM honors them, so unit tests alone can't catch it).
     * Transliterate ASCII digits deterministically after formatting instead.
     */
    private fun String.withArabicIndicDigits(): String = buildString {
        for (c in this@withArabicIndicDigits) {
            when {
                c in '0'..'9' -> append(ARABIC_INDIC_DIGITS[c - '0'])
                // On device the ar formatter emits ASCII separators; Arabic copy uses ٫ / ٬.
                c == '.' -> append('٫')
                c == ',' -> append('٬')
                else -> append(c)
            }
        }
    }

    /** Number only — no percent sign — for resource strings that append their own ٪/%. */
    fun number(value: Double, locale: Locale, fractionDigits: Int = 1): String? {
        if (!value.isFinite()) return null
        val format = percentFormat(locale, fractionDigits)
        return stripPercentSign(format.format(value / PERCENT_SCALE), format)
            .let { if (locale.language == "ar") it.withArabicIndicDigits() else it }
    }

    /** Full percent token — localized digits, grouping and percent sign — for delta badges. */
    fun token(value: Double, locale: Locale, fractionDigits: Int = 0, alwaysSigned: Boolean = false): String? {
        if (!value.isFinite()) return null
        val format = percentFormat(locale, fractionDigits)
        val body = format.format(value / PERCENT_SCALE)
        val prefix = if (alwaysSigned && value > 0.0) "+" else ""
        return (prefix + body).let { if (locale.language == "ar") it.withArabicIndicDigits() else it }
    }

    /** Magnitude token for arrow badges: the caller supplies the arrow and the sign. */
    fun magnitudeToken(value: Double, locale: Locale, fractionDigits: Int = 0): String? {
        if (!value.isFinite()) return null
        return token(abs(value), locale, fractionDigits, alwaysSigned = false)
    }

    private const val PERCENT_SCALE = 100

    private fun percentFormat(locale: Locale, fractionDigits: Int): DecimalFormat {
        // Android's ICU keeps Latin digit shapes for ar locales and IGNORES
        // DecimalFormatSymbols.zeroDigit — the only reliable way to get Arabic-Indic
        // digits is the Unicode locale numbering-system extension (WM071 on-device).
        val effective = if (locale.language == "ar") {
            val region = if (locale.country.isNullOrEmpty()) "" else "-${locale.country}"
            Locale.forLanguageTag("ar$region-u-nu-arab")
        } else locale
        val format = NumberFormat.getPercentInstance(effective) as DecimalFormat
        format.maximumFractionDigits = fractionDigits
        format.minimumFractionDigits = 0
        format.isGroupingUsed = true
        return format
    }

    /** Percent instance always appends the locale percent sign; number() must not. */
    private fun stripPercentSign(formatted: String, format: DecimalFormat): String {
        val symbols = format.decimalFormatSymbols
        val percent = symbols.percent.toString()
        val text = formatted.trimEnd()
        return if (text.endsWith(percent)) text.removeSuffix(percent).trim() else text
    }
}
