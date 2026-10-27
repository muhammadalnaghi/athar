package com.athar.ingestion.smsparser.stcpay

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxType
import com.athar.ingestion.smsparser.BankTemplate
import com.athar.ingestion.smsparser.Normalize
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SenderMatcher
import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * STC Pay wallet sender variants. Add new ones as observed in real SMS.
 *
 * Common senders: "stcpay", "STCPay", "STC Pay", "stc pay".
 * The sender may also come through as a short-code rather than a name —
 * extend [STC_PAY_SENDERS] in that case.
 */
private val STC_PAY_SENDERS = SenderMatcher.AnyOf(
    setOf("stcpay", "STCPay", "STC Pay", "stc pay", "STCPAY"),
)

/**
 * STC Pay outgoing payment.
 *
 * Common formats observed publicly:
 *   تم دفع 50.00 ر.س لـ STARBUCKS
 *   تم إرسال 100 ر.س إلى أحمد
 *   You paid SAR 50.00 to STARBUCKS
 */
/** A card receipt is distinct from the wallet's outgoing-transfer sentence forms. */
class StcPayOnlinePurchaseTemplate : BankTemplate {
    override val id: String = "stcpay-online-purchase"
    override val senderMatcher: SenderMatcher = STC_PAY_SENDERS
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    private val header = Regex("""^Online\s+Purchase\s*$""", options)
    private val amount = Regex("""^Amount\s*:\s*([^\n\r]+)""", options)
    private val merchant = Regex("""^At\s*:\s*([^\n\r]+)""", options)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val normalized = Normalize.digits(body)
        if (!header.containsMatchIn(normalized)) return ParseResult.Failed("not wallet card receipt", listOf(id))
        val declared = amount.find(normalized)?.groupValues?.get(1)
            ?: return ParseResult.Failed("amount unavailable", listOf(id))
        val money = com.athar.ingestion.smsparser.alrajhi.AlRajhiPurchaseAmounts.read(normalized, declared)
            ?: return ParseResult.Failed("purchase amount unavailable", listOf(id))
        val party = merchant.find(normalized)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.EXPENSE, amount = money, merchant = party, counterparty = null,
            balanceAfter = null,
            occurredAt = com.athar.ingestion.smsparser.parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (party != null) .94f else .7f, templateId = id,
        )
    }
}

class StcPayOutgoingTemplate : BankTemplate {
    override val id: String = "stcpay-outgoing"
    override val senderMatcher: SenderMatcher = STC_PAY_SENDERS

    private val amountAr = Regex("""(?:تم\s+(?:دفع|إرسال|تحويل|سحب))\s+([\d.,]+)\s*ر\.?\s*س""")
    private val amountEn = Regex("""(?:You\s+(?:paid|sent|transferred|withdrew))\s+(?:SAR|SR)\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
    private val counterpartyAr = Regex("""(?:لـ|إلى|الى)\s+([^\n\r]+?)(?:\s*$|\n)""", RegexOption.MULTILINE)
    private val counterpartyEn = Regex("""\bto\s+([^\n\r]+?)(?:\s*$|\n)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val normalized = Normalize.digits(body)
        val amount = amountAr.find(normalized)?.groupValues?.get(1)
            ?: amountEn.find(normalized)?.groupValues?.get(1)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val parsedAmount = runCatching { BigDecimal(amount.replace(",", "")) }.getOrNull()
            ?: return ParseResult.Failed("amount unparseable", listOf(id))

        val to = counterpartyAr.find(normalized)?.groupValues?.get(1)?.trim()
            ?: counterpartyEn.find(normalized)?.groupValues?.get(1)?.trim()

        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(parsedAmount),
            merchant = to,
            counterparty = to,
            balanceAfter = null,
            occurredAt = receivedAt,
            confidence = if (to != null) 0.85f else 0.6f,
            templateId = id,
        )
    }
}

/**
 * STC Pay incoming payment.
 *
 *   تم استلام 200 ر.س من أحمد
 *   You received SAR 200 from AHMED
 */
class StcPayIncomingTemplate : BankTemplate {
    override val id: String = "stcpay-incoming"
    override val senderMatcher: SenderMatcher = STC_PAY_SENDERS

    private val amountAr = Regex("""تم\s+(?:استلام|استلمت)\s+([\d.,]+)\s*ر\.?\s*س""")
    private val amountEn = Regex("""You\s+received\s+(?:SAR|SR)\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
    private val sourceAr = Regex("""من\s+([^\n\r]+?)(?:\s*$|\n)""", RegexOption.MULTILINE)
    private val sourceEn = Regex("""from\s+([^\n\r]+?)(?:\s*$|\n)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val normalized = Normalize.digits(body)
        val amount = amountAr.find(normalized)?.groupValues?.get(1)
            ?: amountEn.find(normalized)?.groupValues?.get(1)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val parsedAmount = runCatching { BigDecimal(amount.replace(",", "")) }.getOrNull()
            ?: return ParseResult.Failed("amount unparseable", listOf(id))

        val from = sourceAr.find(normalized)?.groupValues?.get(1)?.trim()
            ?: sourceEn.find(normalized)?.groupValues?.get(1)?.trim()

        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(parsedAmount),
            merchant = from,
            counterparty = from,
            balanceAfter = null,
            occurredAt = receivedAt,
            confidence = if (from != null) 0.85f else 0.6f,
            templateId = id,
        )
    }
}

/**
 * Marketing/OTP/balance notifications. Always [ParseResult.Ignored].
 */
class StcPayIgnoreTemplate : BankTemplate {
    override val id: String = "stcpay-ignore"
    override val senderMatcher: SenderMatcher = STC_PAY_SENDERS

    private val ignore = Regex(
        """(?:OTP|verification|رمز\s+التحقق|رصيدك|your\s+balance|عرض|offer)""",
        RegexOption.IGNORE_CASE,
    )

    override fun tryParse(body: String, receivedAt: Instant): ParseResult =
        if (ignore.containsMatchIn(body)) ParseResult.Ignored
        else ParseResult.Failed("not an ignore-worthy message", listOf(id))
}
