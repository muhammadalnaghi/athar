package com.athar.feature.plan.scenarios

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class ScenarioInputsTest {
    @Test fun `Arabic and Persian digits with correct grouping retain exact decimals`() {
        assertThat(parseScenarioDecimal("١٬٠٠٠٫١٢٣")?.toPlainString()).isEqualTo("1000.123")
        assertThat(parseScenarioDecimal("۱۰٫۱۲۳")?.toPlainString()).isEqualTo("10.123")
    }
    @Test fun `internal whitespace and malformed grouping never join money`() {
        for (value in listOf("1 2", "1,2", "1,,000", "12,34,567", "1e6", "NaN", "")) {
            assertThat(parseScenarioDecimal(value)).isNull()
        }
    }
    @Test fun `bounded parser rejects resource abuse without rounding`() {
        assertThat(parseScenarioDecimal("9".repeat(81))).isNull()
        assertThat(parseScenarioDecimal("0.1234567890123")).isNull()
        assertThat(parseScenarioDecimal("0.000000000001")?.toPlainString()).isEqualTo("0.000000000001")
    }
    @Test fun `signed opening cash and known zero remain exact`() {
        assertThat(parseScenarioDecimal("-0.001")?.toPlainString()).isEqualTo("-0.001")
        assertThat(parseScenarioDecimal("0")?.signum()).isEqualTo(0)
    }
    @Test fun `dates require a real complete ISO date`() {
        assertThat(parseScenarioDate("٢٠٢٦-١١-٢١")).isEqualTo(LocalDate(2026, 11, 21))
        for (date in listOf("2026-02-30", "2026-1-1", "0000-01-01", "2026-11-21 ", "")) {
            assertThat(parseScenarioDate(date)).isNull()
        }
    }
    @Test fun `literal currency is validated not repaired or converted`() {
        assertThat(validScenarioCurrency("SAR")).isTrue()
        assertThat(validScenarioCurrency("KWD")).isTrue()
        assertThat(validScenarioCurrency("JPY")).isTrue()
        for (currency in listOf("sar", " SAR", "XYZ", "XXX", "")) assertThat(validScenarioCurrency(currency)).isFalse()
    }
}
