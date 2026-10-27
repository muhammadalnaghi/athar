package com.athar.ingestion.smsparser.stcbank

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
 * STC Bank — distinct from STC Pay (wallet). Real format observed in user export.
 *
 * Internal transfers have shape:
 *   Internal incoming transfer
 *   Amount:218.00SAR
 *   From:AWS ALGHAMDI
 *   Acc:0847*
 *   At:22/11/25 01:16
 *
 * Note the amount line has NO space between the number and the currency code.
 */
internal val STC_BANK_SENDER_SET = setOf("STC Bank", "STCBank", "STC-Bank", "STCBANK")
private val STC_BANK_SENDERS = SenderMatcher.AnyOf(STC_BANK_SENDER_SET)

private val NUM_RE = Regex("""\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")
private fun parseAmount(s: String): BigDecimal? =
    runCatching { BigDecimal(s.replace(" ", "").replace(",", "")) }.getOrNull()

class StcBankIncomingTransferTemplate : BankTemplate {
    override val id: String = "stc-bank-incoming-transfer"
    override val senderMatcher: SenderMatcher = STC_BANK_SENDERS

    private val header = Regex("""Internal\s+incoming\s+transfer""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE)
    private val fromLine = Regex("""From\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not incoming transfer", listOf(id))
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
            confidence = if (from != null) 0.95f else 0.75f,
            templateId = id,
        )
    }
}

class StcBankOutgoingTransferTemplate : BankTemplate {
    override val id: String = "stc-bank-outgoing-transfer"
    override val senderMatcher: SenderMatcher = STC_BANK_SENDERS

    private val header = Regex("""Internal\s+(?:outward|outgoing)\s+transfer""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE)
    private val toLine = Regex("""To\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not outgoing transfer", listOf(id))
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
            confidence = if (to != null) 0.95f else 0.75f,
            templateId = id,
        )
    }
}

/** SARIE Outward Transfer (to another bank). */
class StcBankSarieOutwardTemplate : BankTemplate {
    override val id: String = "stc-bank-sarie-outward"
    override val senderMatcher: SenderMatcher = STC_BANK_SENDERS

    private val header = Regex("""Outward\s+transfer\s*\(SARIE\)""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""(${NUM_RE.pattern})\s*SAR""")
    private val toLine = Regex("""To\s+(?!ARAB|RAJHI|SAUDI|RIYAD|EMIRATES|ALAHLI|SAB|ALINMA|ALBILAD|ANB|BSF|NCB|SNB)([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not SARIE outward", listOf(id))
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
            confidence = 0.88f,
            templateId = id,
        )
    }
}

/** STC Bank Online Purchase (merchant payment). */
class StcBankOnlinePurchaseTemplate : BankTemplate {
    override val id: String = "stc-bank-online-purchase"
    override val senderMatcher: SenderMatcher = STC_BANK_SENDERS

    private val header = Regex("""Online\s+Purchase(?:\s+Transaction)?\b""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""\bAmount(?:\s*:\s*|\s+)([^\n\r]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val fromLine = Regex("""From\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Online Purchase", listOf(id))
        val declared = amountLine.find(n)?.groupValues?.get(1)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val amount = com.athar.ingestion.smsparser.alrajhi.AlRajhiPurchaseAmounts.read(n, declared)
            ?: return ParseResult.Failed("purchase amount unavailable", listOf(id))
        val merchant = fromLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = amount,
            merchant = merchant,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.93f else 0.7f,
            templateId = id,
        )
    }
}

/** Pay qattah — STC Pay-style split payment. */
class StcBankPayQattahTemplate : BankTemplate {
    override val id: String = "stc-bank-pay-qattah"
    override val senderMatcher: SenderMatcher = STC_BANK_SENDERS

    private val header = Regex("""Pay\s+qattah""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Amount\s*:\s*(${NUM_RE.pattern})\s*SAR""", RegexOption.IGNORE_CASE)
    private val toLine = Regex("""To\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Pay qattah", listOf(id))
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
            confidence = 0.90f,
            templateId = id,
        )
    }
}
