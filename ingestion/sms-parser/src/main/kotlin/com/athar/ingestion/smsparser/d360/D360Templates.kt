package com.athar.ingestion.smsparser.d360

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxType
import com.athar.ingestion.smsparser.BankTemplate
import com.athar.ingestion.smsparser.Normalize
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SenderMatcher
import com.athar.ingestion.smsparser.parseSmsDate
import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * D360 Bank — Riyad-affiliated digital bank. Format observed in user export.
 *
 * Multi-currency body shape:
 *   Online Purchase
 *   Amount: CNY 1.80 (SAR 0.94)
 *   Card: *6169 - VISA (Ecommerce)
 *   At: ALP*HelloBike
 *   On: 2025-07-11 11:35
 *
 * The parser ALWAYS captures the SAR-in-parens amount when present; the foreign
 * currency is metadata.
 */
internal val D360_SENDER_SET = setOf("D360 Bank", "D360", "D360Bank", "D360BANK")
private val D360_SENDERS = SenderMatcher.AnyOf(D360_SENDER_SET)

private val NUM_RE = Regex("""\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
private fun parseAmount(s: String): BigDecimal? =
    runCatching { BigDecimal(s.replace(" ", "").replace(",", "")) }.getOrNull()

private fun sarFromAmountLine(text: String): BigDecimal? {
    // Multi-currency: "Amount: CNY 1.80 (SAR 0.94)" — prefer SAR-in-parens.
    val parens = Regex("""\(\s*SAR\s+(${NUM_RE.pattern})\s*\)""", RegexOption.IGNORE_CASE).find(text)
    if (parens != null) return parens.groupValues[1].let(::parseAmount)
    // Single-currency SAR: "Amount: SAR 25.00"
    val sar = Regex("""SAR\s+(${NUM_RE.pattern})""", RegexOption.IGNORE_CASE).find(text)
    return sar?.groupValues?.get(1)?.let(::parseAmount)
}

class D360OnlinePurchaseTemplate : BankTemplate {
    override val id: String = "d360-online-purchase"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^Online\s+Purchase\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Online Purchase", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount line", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR amount in: $amtTxt", listOf(id))
        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = merchant,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.94f else 0.7f,
            templateId = id,
        )
    }
}

class D360InternationalPurchaseTemplate : BankTemplate {
    override val id: String = "d360-international-purchase"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^International\s+Purchase\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val countryLine = Regex("""^Country\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not International Purchase", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount line", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR amount in: $amtTxt", listOf(id))
        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()
        val country = countryLine.find(n)?.groupValues?.get(1)?.trim()
        val combined = if (merchant != null && country != null) "$merchant ($country)" else merchant
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = combined,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.93f else 0.7f,
            templateId = id,
        )
    }
}

class D360LocalPurchaseTemplate : BankTemplate {
    override val id: String = "d360-local-purchase"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^Purchase\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*SAR\s+(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Purchase", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = merchant,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.94f else 0.7f,
            templateId = id,
        )
    }
}

class D360AccountFundingTemplate : BankTemplate {
    override val id: String = "d360-account-funding"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^Account\s+Funding\b""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""Amount\s*:?\s*SAR\s+(${NUM_RE.pattern})""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Account Funding", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(amount),
            merchant = "Account funding",
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.85f,
            templateId = id,
        )
    }
}

class D360IncomingTransferTemplate : BankTemplate {
    override val id: String = "d360-incoming-transfer"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^Incoming\s+Transfer\s*:?""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*SAR\s+(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val fromLine = Regex("""^From\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Incoming Transfer", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val from = fromLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(amount),
            merchant = from,
            counterparty = from,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (from != null) 0.94f else 0.75f,
            templateId = id,
        )
    }
}

class D360InternationalTransferTemplate : BankTemplate {
    override val id: String = "d360-international-transfer"
    override val senderMatcher: SenderMatcher = D360_SENDERS

    private val header = Regex("""^International\s+transfer\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val toLine = Regex("""^To\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not International transfer", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount line", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR amount", listOf(id))
        val to = toLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount),
            merchant = null,
            counterparty = to,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (to != null) 0.92f else 0.75f,
            templateId = id,
        )
    }
}

class D360DeclinedTemplate : BankTemplate {
    override val id: String = "d360-declined"
    override val senderMatcher: SenderMatcher = D360_SENDERS
    private val marker = Regex("""Transaction\s+Declined""", RegexOption.IGNORE_CASE)
    override fun tryParse(body: String, receivedAt: Instant): ParseResult =
        if (marker.containsMatchIn(body)) ParseResult.Ignored else ParseResult.Failed("not declined", listOf(id))
}
