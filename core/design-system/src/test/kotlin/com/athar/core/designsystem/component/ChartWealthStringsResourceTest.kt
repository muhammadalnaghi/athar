package com.athar.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 028-P-CHARTS regression: the lane-owned chart string resources stay complete and
 * consistent across the Arabic default and English locales — every accessibility key
 * exists in both, every pair carries the same positional placeholders, and the
 * selected-point/point templates keep their contract wording. Pure JVM: parses the
 * XML directly (Gradle runs unit tests with the module dir as working directory).
 */
class ChartWealthStringsResourceTest {

    private fun load(dir: String): Map<String, String> {
        val file = File("src/main/res/$dir/chart_wealth_strings.xml")
        assertThat(file.exists()).isTrue()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            map[element.getAttribute("name")] = element.textContent
        }
        return map
    }

    private fun placeholders(template: String): List<String> =
        Regex("%\\d+\\$[sd]").findAll(template).map { it.value }.toList()

    @Test
    fun `every lane key exists in both locales with matching placeholders`() {
        val arabic = load("values")
        val english = load("values-en")
        val expected = listOf(
            "chart_point_a11y",
            "chart_series_a11y",
            "chart_series_target_a11y",
            "chart_series_average_a11y",
            "chart_series_selected_a11y",
            "chart_series_join",
            "chart_bar_row_a11y",
        )
        assertThat(arabic.keys).containsExactlyElementsIn(expected)
        assertThat(english.keys).containsExactlyElementsIn(expected)
        for (key in expected) {
            assertThat(placeholders(arabic.getValue(key)))
                .isEqualTo(placeholders(english.getValue(key)))
        }
    }

    @Test
    fun `point template names month and value in both locales`() {
        val arabic = load("values")
        val english = load("values-en")
        assertThat(arabic.getValue("chart_point_a11y")).isEqualTo("الشهر %1\$s، القيمة %2\$s")
        assertThat(english.getValue("chart_point_a11y")).isEqualTo("Month %1\$s, value %2\$s")
    }

    @Test
    fun `selected state template carries the real recorded identity wording`() {
        val arabic = load("values")
        val english = load("values-en")
        assertThat(arabic.getValue("chart_series_selected_a11y"))
            .isEqualTo("المحدد: %1\$s، القيمة %2\$s")
        assertThat(english.getValue("chart_series_selected_a11y"))
            .isEqualTo("Selected: %1\$s, value %2\$s")
    }

    @Test
    fun `series overview carries span count and value parts`() {
        val arabic = load("values")
        val english = load("values-en")
        assertThat(placeholders(arabic.getValue("chart_series_a11y")))
            .containsExactly("%1\$s", "%2\$s", "%3\$d")
            .inOrder()
        assertThat(arabic.getValue("chart_series_target_a11y")).contains("%1\$s")
        assertThat(arabic.getValue("chart_series_average_a11y")).contains("%1\$s")
        assertThat(arabic.getValue("chart_bar_row_a11y")).contains("%2\$s")
    }
}
