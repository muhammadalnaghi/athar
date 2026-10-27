package com.athar.ingestion.smsparser.barq

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
 * Barq wallet. Format observed in user export.
 *
 *   Online Purchases:
 *   Visa card: **9803
 *   Amount: 6 CNY (3.14 SAR)
 *   Wallet Balance: 2496.86
 *   At: ZUOMENDUNCANYIN
 *   On: 2025-07-11 03:24
 */
internal val BARQ_SENDER_SET = setOf("barq app", "Barq", "BarqApp", "BARQ", "BarqWallet")
private val BARQ_SENDERS = SenderMatcher.AnyOf(BARQ_SENDER_SET)

private val NUM_RE = Regex("""\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
private fun parseAmount(s: String): BigDecimal? =
    runCatching { BigDecimal(s.replace(" ", "").replace(",", "")) }.getOrNull()

private fun sarFromAmountLine(text: String): BigDecimal? {
    // Multi-currency: "6 CNY (3.14 SAR)" — SAR is INSIDE the parens AFTER the value.
    val parens = Regex("""\(\s*(${NUM_RE.pattern})\s*SAR\s*\)""", RegexOption.IGNORE_CASE).find(text)
    if (parens != null) return parens.groupValues[1].let(::parseAmount)
    // Single-currency SAR-only line: "240.00 SAR"
    val sar = Regex("""(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE).find(text)
    return sar?.groupValues?.get(1)?.let(::parseAmount)
}

class BarqOnlinePurchaseTemplate : BankTemplate {
    override val id: String = "barq-online-purchase"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS

    private val header = Regex("""Online\s+Purchases?:""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val atLine = Regex("""(?:^|\n)At\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Online Purchases", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR in: $amtTxt", listOf(id))
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

class BarqPosInternationalTemplate : BankTemplate {
    override val id: String = "barq-pos-international"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS

    private val header = Regex("""POS\s+International\s+Purchase""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val atLine = Regex("""(?:^|\n)At\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
    private val countryLine = Regex("""(?:^|\n)Country\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not POS International", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR amount", listOf(id))
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

class BarqAtmWithdrawalTemplate : BankTemplate {
    override val id: String = "barq-atm-withdrawal"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS

    private val header = Regex("""(?:International\s+)?ATM\s+Withdrawal""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val atLine = Regex("""(?:^|\n)at:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not ATM Withdrawal", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount", listOf(id))
        val amount = sarFromAmountLine(amtTxt) ?: return ParseResult.Failed("no SAR amount", listOf(id))
        val location = atLine.find(n)?.groupValues?.get(1)?.trim() ?: "ATM Withdrawal"
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = "ATM: $location",
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.92f,
            templateId = id,
        )
    }
}

class BarqDebitTransferTemplate : BankTemplate {
    override val id: String = "barq-debit-transfer"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS

    private val header = Regex("""Debit\s+Transfer\s+(?:Internal|Local)""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE)
    private val toLine = Regex("""To\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Debit Transfer", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val to = toLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount),
            merchant = null,
            counterparty = to,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (to != null) 0.93f else 0.75f,
            templateId = id,
        )
    }
}

class BarqCreditTransferTemplate : BankTemplate {
    override val id: String = "barq-credit-transfer"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS

    private val header = Regex("""Credit\s+transfer\s+(?:Internal|Local)""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE)
    private val fromLine = Regex("""From\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Credit Transfer", listOf(id))
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
            confidence = if (from != null) 0.93f else 0.75f,
            templateId = id,
        )
    }
}

class BarqRejectedTemplate : BankTemplate {
    override val id: String = "barq-rejected"
    override val senderMatcher: SenderMatcher = BARQ_SENDERS
    private val marker = Regex("""Rejected\s+transaction""", RegexOption.IGNORE_CASE)
    override fun tryParse(body: String, receivedAt: Instant): ParseResult =
        if (marker.containsMatchIn(body)) ParseResult.Ignored else ParseResult.Failed("not rejected", listOf(id))
}
