package com.athar.feature.plan.portfolio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for the PORTFOLIO input codec: strict version typing (a quoted
 * `"1"` is a malformation, never coerced), exact decimal-string and literal-currency
 * round-trips (KWD's three decimals included), and retained-unloadable outcomes for
 * every malformed shape. These guard the durable-input contract at the feature edge.
 */
class PortfolioPlanCodecTest {

    private fun validJson(version: String = "1", currency: String = "SAR") = """
        {
          "portfolioPlanVersion": $version,
          "currency": "$currency",
          "openingCashAmount": "1000.500",
          "securities": [
            {"securityId": "ALRAJHI", "name": "الراجحي", "assetClass": "EQUITY"}
          ],
          "events": [
            {"kind": "BUY", "eventId": "e1", "date": "2026-01-15", "securityId": "ALRAJHI",
             "quantity": "10.5", "unitPrice": "95.123", "fee": "0.250"}
          ],
          "quotes": [
            {"securityId": "ALRAJHI", "date": "2026-02-01", "unitPrice": "97.850"}
          ]
        }
    """.trimIndent()

    @Test
    fun `version 1 document round-trips exact decimals and literal currency`() {
        val result = PortfolioPlanCodec.decode(validJson(currency = "KWD"))
        assertThat(result).isInstanceOf(PortfolioDecodeResult.Success::class.java)
        val document = (result as PortfolioDecodeResult.Success).document
        assertThat(document.currency).isEqualTo("KWD")
        // Exact decimal strings — never converted through a number.
        assertThat(document.openingCashAmount).isEqualTo("1000.500")
        val buy = document.events.single()
        assertThat(buy.unitPrice).isEqualTo("95.123")
        assertThat(buy.fee).isEqualTo("0.250")
        assertThat(document.quotes.single().unitPrice).isEqualTo("97.850")
        // The codec re-encodes to the same literal values (key order may differ).
        val reEncoded = PortfolioPlanCodec.encode(document)
        assertThat(PortfolioPlanCodec.decode(reEncoded))
            .isInstanceOf(PortfolioDecodeResult.Success::class.java)
        val reDecoded = (PortfolioPlanCodec.decode(reEncoded) as PortfolioDecodeResult.Success).document
        assertThat(reDecoded.openingCashAmount).isEqualTo("1000.500")
        assertThat(reDecoded.currency).isEqualTo("KWD")
    }

    @Test
    fun `quoted version string one is malformed not silently accepted`() {
        // The historical bug: (JsonPrimitive)?.content coerced "1" into an accepted version.
        val quoted = validJson(version = "\"1\"")
        val result = PortfolioPlanCodec.decode(quoted)
        assertThat(result).isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE))
    }

    @Test
    fun `numeric future version is unsupported and retained`() {
        val result = PortfolioPlanCodec.decode(validJson(version = "2"))
        assertThat(result).isEqualTo(PortfolioDecodeResult.UnsupportedVersion("2"))
    }

    @Test
    fun `numeric amount fields are rejected as type errors`() {
        val numericAmount = validJson().replace("\"openingCashAmount\": \"1000.500\"", "\"openingCashAmount\": 1000.5")
        val result = PortfolioPlanCodec.decode(numericAmount)
        assertThat(result).isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE))
    }

    @Test
    fun `unknown root key makes the document retained-unloadable`() {
        val result = PortfolioPlanCodec.decode(validJson().replace("\"quotes\":", "\"quotesX\":"))
        assertThat(result).isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.MISSING_OR_EXTRA_ROOT_KEY))
    }

    @Test
    fun `non ISO date is rejected`() {
        val result = PortfolioPlanCodec.decode(validJson().replace("2026-01-15", "15/01/2026"))
        assertThat(result).isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_DATE))
    }

    @Test
    fun `non json input decodes as malformed`() {
        assertThat(PortfolioPlanCodec.decode("not json"))
            .isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.NOT_JSON_OBJECT))
        assertThat(PortfolioPlanCodec.decode("[1,2]"))
            .isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.NOT_JSON_OBJECT))
    }
}
