package com.athar.core.designsystem.display

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/** WM077 — caption numerals must match the app locale's digit shapes deterministically. */
class AtharNumeralsTest {

    @Test
    fun `arabic locale gets arabic-indic digits and separators without reordering`() {
        assertThat(AtharNumerals.localize("2026/10", Locale.forLanguageTag("ar-SA")))
            .isEqualTo("٢٠٢٦/١٠")
        assertThat(AtharNumerals.localize("1448/04", Locale.forLanguageTag("ar")))
            .isEqualTo("١٤٤٨/٠٤")
    }

    @Test
    fun `arabic decimals and grouping separators use arabic copy forms`() {
        assertThat(AtharNumerals.localize("44.3", Locale.forLanguageTag("ar-SA")))
            .isEqualTo("٤٤٫٣")
        assertThat(AtharNumerals.localize("1,239,788", Locale.forLanguageTag("ar-SA")))
            .isEqualTo("١٬٢٣٩٬٧٨٨")
    }

    @Test
    fun `non-arabic locales are untouched`() {
        assertThat(AtharNumerals.localize("2026/10", Locale.US)).isEqualTo("2026/10")
        assertThat(AtharNumerals.localize("44.3", Locale.ENGLISH)).isEqualTo("44.3")
    }

    @Test
    fun `negative savings-rate style labels keep sign and digits intact`() {
        assertThat(AtharNumerals.localize("-18", Locale.forLanguageTag("ar-SA")))
            .isEqualTo("-١٨")
    }
}
