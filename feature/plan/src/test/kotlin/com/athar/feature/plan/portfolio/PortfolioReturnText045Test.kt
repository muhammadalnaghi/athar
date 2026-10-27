package com.athar.feature.plan.portfolio

import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.feature.plan.R
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * WM045 presentation regressions: locale-exact percent rendering of BigDecimal engine
 * rates at display precision, and the undefined-return mapping that keeps a fixed
 * no-evidence sentence for the structural empty-id case, the real security ids for
 * named ones, and honest generic text otherwise. The formatting cases are first-green
 * coverage of the new pure helper, not a RED-driven fix.
 */
class PortfolioReturnText045Test {

    @Test fun `formats positive rate at two decimals keeping sign`() {
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("109.47101751"), Locale.ENGLISH))
            .isEqualTo("\u2066109.47%\u2069")
    }

    @Test fun `formats negative rate keeping sign`() {
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("-48.26856424"), Locale.ENGLISH))
            .isEqualTo("\u2066-48.27%\u2069")
    }

    @Test fun `formats zero without inventing a value`() {
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("0.00000000"), Locale.ENGLISH))
            .isEqualTo("\u20660%\u2069")
    }

    @Test fun `formats large rate with grouping`() {
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("2531.93813328"), Locale.ENGLISH))
            .isEqualTo("\u20662,531.94%\u2069")
    }

    @Test fun `formats arabic digits separators and percent sign in arabic locale`() {
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("2531.93813328"), Locale("ar")))
            .isEqualTo("\u2066٢٬٥٣١٫٩٤٪\u2069")
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("12.5"), Locale("ar")))
            .isEqualTo("\u2066١٢٫٥٪\u2069")
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("-48.26856424"), Locale("ar")))
            .isEqualTo("\u2066-٤٨٫٢٧٪\u2069")
        assertThat(PortfolioReturnText045.formatPercent(BigDecimal("0.00000000"), Locale("ar")))
            .isEqualTo("\u2066٠٪\u2069")
    }

    @Test fun `never rescales the engine result and never multiplies by hundred`() {
        val engineRate = BigDecimal("12.50000000")
        val rendered = PortfolioReturnText045.formatPercent(engineRate, Locale.ENGLISH)
        assertThat(engineRate.toPlainString()).isEqualTo("12.50000000")
        assertThat(rendered).contains("12.5")
        assertThat(rendered).doesNotContain("1250")
    }

    @Test fun `maps structural missing boundary with empty ids to fixed no-evidence text`() {
        val res = PortfolioReturnText045.undefinedTextRes(
            ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION,
            emptyList(),
        )
        assertThat(res).isEqualTo(R.string.plan_portfolio_return045_boundary_missing_no_evidence)
    }

    @Test fun `keeps per security disclosure when securities are named`() {
        val res = PortfolioReturnText045.undefinedTextRes(
            ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION,
            listOf("SEC-1", "SEC-2"),
        )
        assertThat(res).isEqualTo(R.string.plan_portfolio_return_undefined_missing)
    }

    @Test fun `keeps honest generic text for ambiguity and solver uncertainty`() {
        for (reason in listOf(
            ReturnUndefinedReason.NO_DATED_FLOWS,
            ReturnUndefinedReason.NON_POSITIVE_PERIOD_BASE,
            ReturnUndefinedReason.NO_CONVERGENCE,
        )) {
            assertThat(PortfolioReturnText045.undefinedTextRes(reason, emptyList()))
                .isEqualTo(R.string.plan_portfolio_return_undefined_generic)
            assertThat(PortfolioReturnText045.undefinedTextRes(reason, listOf("S9")))
                .isEqualTo(R.string.plan_portfolio_return_undefined_generic)
        }
    }

    @Test fun `tiny negative rate retains its sign after display rounding`() {
        val rate = BigDecimal("-0.00400000")
        assertThat(PortfolioReturnText045.formatPercent(rate, Locale.ENGLISH))
            .isEqualTo("\u2066-0%\u2069")
        assertThat(PortfolioReturnText045.formatPercent(rate, Locale("ar")))
            .isEqualTo("\u2066-٠٪\u2069")
        assertThat(rate.toPlainString()).isEqualTo("-0.00400000")
    }

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

    @Test fun `returns045 locales retain parity and exact no-evidence sentences`() {
        val ar = load("values", "returns045_strings.xml")
        val en = load("values-en", "returns045_strings.xml")
        assertThat(ar.keys).containsExactlyElementsIn(en.keys)
        assertThat(ar.getValue("plan_portfolio_return045_boundary_missing_no_evidence"))
            .isEqualTo("غير متاح: لا تتوفر قيم مؤرخة كافية.")
        assertThat(en.getValue("plan_portfolio_return045_boundary_missing_no_evidence"))
            .isEqualTo("Unavailable: insufficient dated valuation evidence.")
        // No dangling per-security tail when the id list is empty.
        assertThat(ar.getValue("plan_portfolio_return045_boundary_missing_no_evidence")).doesNotContain("لـ ")
    }

    @Test fun `twr evidence explanation no longer claims displayed dated boundaries`() {
        val ar = load("values", "wm040_strings.xml")
        val en = load("values-en", "wm040_strings.xml")
        assertThat(ar.getValue("plan_portfolio_evidence_twr_requires"))
            .doesNotContain("يرتبط العائد المعروض")
        assertThat(ar.getValue("plan_portfolio_evidence_twr_requires"))
            .contains("قبل التدفقات وبعدها")
        assertThat(en.getValue("plan_portfolio_evidence_twr_requires"))
            .doesNotContain("displayed return uses dated")
        assertThat(en.getValue("plan_portfolio_evidence_twr_requires"))
            .contains("before and after flows")
    }
}
