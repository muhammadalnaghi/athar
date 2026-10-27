package com.athar.ingestion.smsparser.alrajhi

import com.athar.core.common.money.Money
import java.math.BigDecimal
import java.util.Locale

/** Read declared money only; exchange-rate estimates are never used as conversion. */
internal object AlRajhiPurchaseAmounts {
    private const val NUMBER = "[+-]?(?:\\d{1,3}(?:[ ,]\\d{3})+|\\d+)(?:\\.\\d+)?"
    private val sarSettlement = Regex("""\(\s*(?:(?:SAR|SR)\s*($NUMBER)|($NUMBER)\s*(?:SAR|SR))\s*\)""", RegexOption.IGNORE_CASE)
    private val currencyFirst = Regex("""^\s*([A-Z]{3}|SR)\s*($NUMBER)(?=\s|$|\()""", RegexOption.IGNORE_CASE)
    private val numberFirst = Regex("""^\s*($NUMBER)\s*([A-Z]{3}|SR)(?=\s|$|\()""", RegexOption.IGNORE_CASE)
    private val bare = Regex("""^\s*($NUMBER)(?=\s+(?:From|At|Date)\s*:|\s*$)""", RegexOption.IGNORE_CASE)
    private val totalDue = Regex("""^\s*Total\s+due\s+amount\s*:\s*([^\r\n]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun read(body: String, amountLine: String): Money? {
        val settlement = sarSettlement.find(amountLine)?.let {
            decimal(it.groupValues[1].ifBlank { it.groupValues[2] })?.let { n -> Money.of(n) }
        }
        val principal = settlement ?: literal(amountLine.substringBefore('(').trim(), Money.SAR) ?: return null
        val total = totalDue.find(body)?.groupValues?.get(1) ?: return principal
        // A total inherits the declared booked denomination, never the foreign leader of an FX pair.
        return literal(total, principal.currency)
    }

    private fun literal(text: String, defaultCurrency: String): Money? {
        currencyFirst.find(text)?.let {
            return decimal(it.groupValues[2])?.let { n -> Money.of(n, currency(it.groupValues[1])) }
        }
        numberFirst.find(text)?.let {
            return decimal(it.groupValues[1])?.let { n -> Money.of(n, currency(it.groupValues[2])) }
        }
        return bare.find(text)?.groupValues?.get(1)?.let(::decimal)?.let { Money.of(it, defaultCurrency) }
    }
    private fun currency(text: String): String = text.uppercase(Locale.ROOT).let { if (it == "SR") Money.SAR else it }
    private fun decimal(text: String): BigDecimal? = runCatching {
        BigDecimal(text.replace(" ", "").replace(",", ""))
    }.getOrNull()
}
