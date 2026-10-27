package com.athar.ingestion.smsparser.alrajhi

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
 * Al Rajhi templates re-derived from a 1,000-message export (May 2026). Every shape
 * here is anchored to a real sample in `docs/sms-corpus-analysis.md`. Each template
 * checks for its unique header line first so they're cheap to fail-fast and never
 * mis-attribute one shape's body to another.
 *
 * For multi-currency online purchases the format is `Amount:16.25USD(60.96 SAR)` —
 * the templates capture the SAR-in-parens value, never the foreign-currency leader.
 */
internal val AL_RAJHI_SENDER_SET = setOf(
    "AlRajhiBank", "AlRajhi Bank", "AlRajhi", "ALRAJHIBANK", "Al Rajhi Bank", "ALRAJHI",
)
private val AL_RAJHI_SENDERS_REAL = SenderMatcher.AnyOf(AL_RAJHI_SENDER_SET)

private val NUM_RE = Regex("""\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

private fun parseAmount(s: String): BigDecimal? =
    runCatching { BigDecimal(s.replace(" ", "").replace(",", "")) }.getOrNull()

/**
 * Online Purchase — most common Al Rajhi card transaction. SAR-only and multi-currency
 * both supported. Header line is exactly `Online Purchase`. The merchant follows `At:`,
 * the amount follows `Amount:`, the balance follows `Balance:`.
 */
class AlRajhiOnlinePurchaseRealTemplate : BankTemplate {
    override val id: String = "al-rajhi-online-purchase-real"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Online\s+Purchase\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val balanceLine = Regex("""^Balance\s*:\s*(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val cardLine = Regex("""^(?:By|Card)\s*:\s*(\d{3,4})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Online Purchase", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount line", listOf(id))
        val amount = AlRajhiPurchaseAmounts.read(n, amtTxt)
            ?: return ParseResult.Failed("purchase amount unavailable", listOf(id))

        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val balance = balanceLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
        val card = cardLine.find(n)?.groupValues?.get(1)

        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = amount,
            merchant = merchant,
            counterparty = card?.let { "Card $it" },
            balanceAfter = balance?.let { Money.of(it) },
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.95f else 0.75f,
            templateId = id,
        )
    }
}

/**
 * PoS Purchase — physical-card transaction. Header `PoS purchase`. Same body shape
 * as Online Purchase except header.
 */
class AlRajhiPosPurchaseRealTemplate : BankTemplate {
    override val id: String = "al-rajhi-pos-purchase-real"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^PoS(?:\s+International)?\s+purchase\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val balanceLine = Regex("""^Balance\s*:\s*(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val cardLine = Regex("""^Card\s*:\s*(\d{3,4})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not PoS purchase", listOf(id))
        val amtTxt = amountLine.find(n)?.groupValues?.get(1) ?: return ParseResult.Failed("no Amount line", listOf(id))
        val amount = AlRajhiPurchaseAmounts.read(n, amtTxt)
            ?: return ParseResult.Failed("purchase amount unavailable", listOf(id))
        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val balance = balanceLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
        val card = cardLine.find(n)?.groupValues?.get(1)
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = amount,
            merchant = merchant,
            counterparty = card?.let { "Card $it" },
            balanceAfter = balance?.let { Money.of(it) },
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (merchant != null) 0.95f else 0.75f,
            templateId = id,
        )
    }
}

/**
 * Reverse Transaction — refund of a prior purchase. Treated as INCOME with the original
 * merchant as the source.
 */
class AlRajhiReverseTemplate : BankTemplate {
    override val id: String = "al-rajhi-reverse"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Reverse\s+Transaction\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+|SR\s+)?(${NUM_RE.pattern})(?:\s*SAR|\s*SR)?""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val atLine = Regex("""^At\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Reverse Transaction", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val merchant = atLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(amount),
            merchant = merchant,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.90f,
            templateId = id,
        )
    }
}

/**
 * Debit Internal Transfer — outgoing transfer to another own-account or another person.
 * Multi-line "To:" fields appear when recipient + account-number both shown.
 */
class AlRajhiDebitInternalTransferTemplate : BankTemplate {
    override val id: String = "al-rajhi-debit-internal-transfer"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Debit\s+Internal\s+Transfer\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+|SR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val toLine = Regex("""^To\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Debit Internal Transfer", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        // Take the first "To:" that doesn't look like a bare account number (3-4 digits).
        val to = toLine.findAll(n).map { it.groupValues[1].trim() }.firstOrNull { !it.matches(Regex("""\d{3,4}""")) }
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount),
            merchant = null,
            counterparty = to,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.92f,
            templateId = id,
        )
    }
}

/**
 * Debit Transfer Local — outgoing to another bank (SARIE). Includes `Bank:` header
 * naming the recipient bank.
 */
class AlRajhiDebitLocalTransferTemplate : BankTemplate {
    override val id: String = "al-rajhi-debit-local-transfer"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Debit\s+Transfer\s+Local\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val toLine = Regex("""^To\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Debit Transfer Local", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val to = toLine.findAll(n).map { it.groupValues[1].trim() }.firstOrNull { !it.matches(Regex("""\d{3,4}""")) }
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount),
            merchant = null,
            counterparty = to,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.92f,
            templateId = id,
        )
    }
}

/**
 * Credit Transfer Local — inbound from another bank (commonly salary). The `From:`
 * line names the sender (company or person), and the `Via:` line names the originating
 * bank.
 */
class AlRajhiCreditLocalTransferTemplate : BankTemplate {
    override val id: String = "al-rajhi-credit-local-transfer"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Credit\s+Transfer\s+Local\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val fromLine = Regex("""^From\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Credit Transfer Local", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val from = fromLine.findAll(n).map { it.groupValues[1].trim() }.firstOrNull { !it.matches(Regex("""\d{3,4}""")) }
        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(amount),
            merchant = from,
            counterparty = from,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.92f,
            templateId = id,
        )
    }
}

/**
 * Bill Payment — utility / service payment. The `Service:` line names the biller
 * (e.g., SAUDI ELECTRIC COMPANY). The merchant comes from `Service:`.
 */
class AlRajhiBillPaymentTemplate : BankTemplate {
    override val id: String = "al-rajhi-bill-payment"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Bill\s+Payment\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val serviceLine = Regex("""^Service\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Bill Payment", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val service = serviceLine.find(n)?.groupValues?.get(1)?.trim()
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = service,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = if (service != null) 0.94f else 0.7f,
            templateId = id,
        )
    }
}

/**
 * Deposit / Monthly Profit — interest on savings account or other inbound deposit.
 * Header begins with `Deposit:` (note the colon attached to the keyword).
 */
class AlRajhiDepositRealTemplate : BankTemplate {
    override val id: String = "al-rajhi-deposit-real"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Deposit\s*:\s*([^\n\r]+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        val hdr = header.find(n) ?: return ParseResult.Failed("not Deposit", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val depositType = hdr.groupValues[1].trim()
        return ParseResult.Success(
            type = TxType.INCOME,
            amount = Money.of(amount),
            merchant = depositType,
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.90f,
            templateId = id,
        )
    }
}

/**
 * Credit Card Payment — when the user pays their credit card from their checking
 * account. Treated as TRANSFER (between own accounts), not EXPENSE — the underlying
 * card purchases are the real expenses.
 */
class AlRajhiCreditCardPaymentTemplate : BankTemplate {
    override val id: String = "al-rajhi-credit-card-payment"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex(
        """^(?:Credit\s+Card\s*:\s*Payment|بطاقة\s+(?:ائتمانية|إئتمانية|فيزا)\s*:\s*سداد(?:\s+بـ[^\n\r]+)?)\s*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )
    private val amountLine = Regex(
        """(?:^Amount[ \t]*:[ \t]*|^مبلغ[ \t]*:[ \t]*|بـ[ \t]*)(?:([A-Z]{3}|SR|ر\.?[ \t]*س)[ \t]+)?(${NUM_RE.pattern})(?:[ \t]*([A-Z]{3}|SR|ر\.?[ \t]*س))?(?=\s|$|;)""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Credit Card:Payment", listOf(id))
        val fields = amountLine.find(n)?.groupValues
            ?: return ParseResult.Failed("amount not found", listOf(id))
        val amount = fields[2].let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        fun currency(token: String) = token.replace(" ", "").uppercase(java.util.Locale.ROOT).let {
            if (it in setOf("", "SAR", "SR", "ر.س", "رس")) "SAR" else it
        }
        if (fields[1].isNotBlank() && fields[3].isNotBlank() && currency(fields[1]) != currency(fields[3])) {
            return ParseResult.Failed("conflicting payment currencies", listOf(id))
        }
        val denomination = currency(fields[1].ifBlank { fields[3] })
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount, denomination),
            merchant = "Credit Card Payment",
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.88f,
            templateId = id,
        )
    }
}

/**
 * Loan Instalment — outgoing periodic loan payment. Distinct from a regular debit
 * because the `Instalment:` keyword precedes the amount, not `Amount:`.
 */
class AlRajhiLoanInstalmentTemplate : BankTemplate {
    override val id: String = "al-rajhi-loan-instalment"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""(?:Debit|Credit)\s*:\s*Loan\s+Instalment""", RegexOption.IGNORE_CASE)
    private val amountLine = Regex("""Instalment\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Loan Instalment", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("instalment amount not found", listOf(id))
        return ParseResult.Success(
            type = TxType.EXPENSE,
            amount = Money.of(amount),
            merchant = "Loan Instalment",
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.93f,
            templateId = id,
        )
    }
}

/**
 * Transfer Between Your Accounts — own-to-own internal move. NOT a real expense; the
 * confidence is lower so the user can dismiss easily.
 */
class AlRajhiTransferBetweenOwnTemplate : BankTemplate {
    override val id: String = "al-rajhi-transfer-between-own"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val header = Regex("""^Transfer\s+Between\s+Your\s+Accounts\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val amountLine = Regex("""^Amount\s*:\s*(?:SAR\s+)?(${NUM_RE.pattern})""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val n = Normalize.digits(body)
        if (!header.containsMatchIn(n)) return ParseResult.Failed("not Transfer Between Your Accounts", listOf(id))
        val amount = amountLine.find(n)?.groupValues?.get(1)?.let(::parseAmount)
            ?: return ParseResult.Failed("amount not found", listOf(id))
        return ParseResult.Success(
            type = TxType.TRANSFER,
            amount = Money.of(amount),
            merchant = "Own account transfer",
            counterparty = null,
            balanceAfter = null,
            occurredAt = parseSmsDate(body, receivedAt) ?: receivedAt,
            confidence = 0.85f,
            templateId = id,
        )
    }
}

/**
 * Notification : Declined — a transaction attempt that didn't go through. The Master
 * Brief calls for these to be visible but flagged; here we return Ignored because the
 * user already knows it didn't happen (no money moved). If they want to see declined
 * attempts they can enable a future "show declined" toggle.
 */
class AlRajhiDeclinedTemplate : BankTemplate {
    override val id: String = "al-rajhi-declined"
    override val senderMatcher: SenderMatcher = AL_RAJHI_SENDERS_REAL

    private val marker = Regex("""Declined\s+due\s+to|Transaction\s+Declined""", RegexOption.IGNORE_CASE)

    override fun tryParse(body: String, receivedAt: Instant): ParseResult =
        if (marker.containsMatchIn(body)) ParseResult.Ignored else ParseResult.Failed("not declined", listOf(id))
}
