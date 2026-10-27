package com.athar.feature.plan

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Resource contract only; these JVM checks are not native RTL/screenshot acceptance. */
class ResultRendererResourcesTest {
    private fun load(locale: String, file: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$locale/$file")).getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as Element
            assertThat(result.put(node.getAttribute("name"), node.textContent)).isNull()
        }
        return result
    }

    private fun checkParity(file: String) {
        val arabic = load("values", file)
        val english = load("values-en", file)
        assertThat(arabic.keys).containsExactlyElementsIn(english.keys)
        val placeholder = Regex("%[0-9]+" + Regex.escape("$" ) + "[sd]")
        assertThat(placeholder.findAll("%1\$s %2\$d").count()).isEqualTo(2)
        for (key in arabic.keys) {
            val left = placeholder.findAll(arabic.getValue(key)).map { it.value }.sorted().toList()
            val right = placeholder.findAll(english.getValue(key)).map { it.value }.sorted().toList()
            assertThat(left).isEqualTo(right)
            assertThat(arabic.getValue(key)).isNotEmpty()
            assertThat(english.getValue(key)).isNotEmpty()
        }
    }

    @Test fun `debt locales retain every key and positional placeholder`() = checkParity("debt_result_strings.xml")
    @Test fun `cash locales retain every key and positional placeholder`() = checkParity("cash_path_result_strings.xml")

    @Test fun `debt labels explain allocation and name nominal interest accurately`() {
        val ar = load("values", "debt_result_strings.xml")
        assertThat(ar.getValue("debt_result_strategy_avalanche")).isEqualTo("الأعلى فائدة أولًا")
        assertThat(ar.getValue("debt_result_strategy_snowball")).isEqualTo("الأقل رصيدًا أولًا")
        assertThat(ar.getValue("debt_result_total_interest")).isEqualTo("إجمالي الفوائد")
    }

    @Test fun `cash conditional and precision labels make no guaranteed or ISO-minor-unit claim`() {
        val ar = load("values", "cash_path_result_strings.xml")
        val en = load("values-en", "cash_path_result_strings.xml")
        assertThat(ar.getValue("plan_cash_path_result_status_confident")).isEqualTo("مسار مشروط")
        assertThat(en.getValue("plan_cash_path_result_status_confident")).isEqualTo("Conditional path")
        assertThat(en.getValue("plan_cash_path_result_disc_precision")).doesNotContain("minor units")
    }
}
