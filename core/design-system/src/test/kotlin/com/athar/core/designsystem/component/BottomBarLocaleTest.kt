package com.athar.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class BottomBarLocaleTest {
    private val items = listOf(
        AtharBottomBarItem("today", "Today", "اليوم"),
        AtharBottomBarItem("trends", "Trends", "النمط"),
        AtharBottomBarItem("plan", "Plan", "الخطة"),
    )

    @Test
    fun `Arabic navigation uses only its short Arabic destination labels`() {
        assertThat(items.map { it.labelFor(Locale.forLanguageTag("ar-SA")) })
            .containsExactly("اليوم", "النمط", "الخطة").inOrder()
    }

    @Test
    fun `English navigation uses only its English destination labels`() {
        assertThat(items.map { it.labelFor(Locale.UK) })
            .containsExactly("Today", "Trends", "Plan").inOrder()
    }
}
