package com.athar.ingestion.smsparser.universal

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxType
import com.athar.ingestion.smsparser.BankTemplate
import com.athar.ingestion.smsparser.KnownBankSenders
import com.athar.ingestion.smsparser.Normalize
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SenderMatcher
import kotlinx.datetime.Instant

/**
 * Currency-and-language-agnostic last-resort parser — restricted to KNOWN bank senders.
 *
 * The earlier any-sender behaviour was a bug: a promotional SMS from an unrelated
 * shortcode ("Earn SAR 10,000 today!") would get treated as a transaction because
 * it contained a currency-amount substring. The fix is structural: this template
 * only runs when the sender is in [KnownBankSenders.builtIn], so messages from
 * marketing senders, OTP shortcodes, etc. never reach it.
 *
 * Detects:
 *   - amount: decimal with optional thousands separators
 *   - currency: 3-letter ISO codes (SAR, AED, USD, EUR, GBP, INR, …) OR symbols ($, €, £, ﷼, ₹)
 *   - direction: action keywords in Arabic + English + Spanish + French + Urdu + Turkish
 *
 * Always returns Success with low confidence (≤0.55) so the user is forced to
 * confirm/correct in the pending tray. This is the bridge layer until the
 * on-device ML classifier ships (see ADR-005).
 */
class UniversalAmountTemplate : BankTemplate {
    override val id: String = "universal-amount"
    override val senderMatcher: SenderMatcher = SenderMatcher.AnyOf(KnownBankSenders.builtIn)

    // (currency-symbol|ISO-code)?  amount  (ISO-code)?  — covers `$200`, `200 SAR`, `SAR 200`, `₹500`.
    private val amountWithCurrency = Regex(
        """(?:(?<lead>CA\$|C\$|AU\$|A\$|[\$€£﷼₹¥₺د\.ك]|SAR|SR|AED|USD|EUR|GBP|CAD|AUD|CHF|INR|PKR|TRY|EGP|KWD|QAR|BHD|OMR|JOD|JPY|CNY|HKD|SGD|SEK|NOK|DKK|ZAR|BRL|MXN|THB|IDR|MYR|PHP|VND|KRW)\s*)?(?<num>${Normalize.LOCALIZED_AMOUNT_PATTERN})(?:\s*(?<trail>CA\$|C\$|AU\$|A\$|[\$€£﷼₹¥₺د\.ك]|SAR|SR|AED|USD|EUR|GBP|CAD|AUD|CHF|INR|PKR|TRY|EGP|KWD|QAR|BHD|OMR|JOD|JPY|CNY|HKD|SGD|SEK|NOK|DKK|ZAR|BRL|MXN|THB|IDR|MYR|PHP|VND|KRW|ر\.?\s*س|د\.?\s*إ))?""",
        RegexOption.IGNORE_CASE,
    )

    private val latinIncomeWords = Regex(
        """\b(?:credit|deposit|received|incoming|refund|salary|payment\s+from|recibido|reçu|gelir|گیا)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val arabicIncomeWords = Regex("""(?:إيداع|ايداع|وارد|استلم|تم\s+استلام|راتب|استرداد\s+نقدي|كاسترداد\s+نقدي|تم\s+إضافة)""")
    private val latinExpenseWords = Regex(
        """\b(?:purchase|paid|debit|withdrawal|spent|charge|pos|atm|cobrado|payé|harcanan|خرچ)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val arabicExpenseWords = Regex("""(?:شراء|سحب|خصم|دفع)""")
    private val latinTransferWords = Regex(
        """\b(?:transfer|sent|outgoing|remit|envío|virement|havale|بھیج)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val arabicTransferWords = Regex("""(?:تحويل|حوالة|حوالتكم|حوالتك|الحوالة|إرسال)""")
    private val ignoreWords = Regex(
        """\b(?:otp|verification\s+code|رمز\s+التحقق|verify\s+code|do\s+not\s+share|promo|عرض\s+ترويجي)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val merchantHint = Regex(
        """(?:(?:\bat\b|\bfrom\b|\bto\b|لدى|من|إلى|الى|الجهة|الخدمة|مكان\s+السحب|مفوتر|على|de|à|en|chez)\s*[:\s]\s*|لـ\s*[:\s]?)([A-Za-z\u0600-\u06FF][^\n\r]{1,40})""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    private val primaryAmountLabel = Regex(
        """(?<![\p{L}\p{N}_])(?:amount|المبلغ|مبلغ)\s*[:：][ \t]*""",
        RegexOption.IGNORE_CASE,
    )
    // Reuse the existing currency/grouping grammar without truncating the
    // fractional token in an explicit field to two digits (e.g. KWD fils).
    private val labelledAmountWithCurrency = Regex(
        amountWithCurrency.pattern.replace(
            Normalize.LOCALIZED_AMOUNT_PATTERN,
            Normalize.LOCALIZED_AMOUNT_PATTERN.replace("\\d{1,2}", "\\d+"),
        ),
        RegexOption.IGNORE_CASE,
    )

    /** A labelled value must not fall forward to a later identifier or balance. */
    private fun transactionAmount(body: String): MatchResult? {
        val labels = primaryAmountLabel.findAll(body).toList()
        if (labels.isEmpty()) return amountWithCurrency.find(body)
        if (labels.size != 1) return null
        val start = labels.single().range.last + 1
        val match = labelledAmountWithCurrency.find(body, start) ?: return null
        if (match.range.first != start) return null
        // Do not repair a partial exponent, malformed separator, or unknown suffix
        // into a different amount. Whitespace still permits following labelled
        // fields and literal foreign/settlement countervalues; never add them.
        val next = body.getOrNull(match.range.last + 1)
        if (next != null && (next.isLetter() || next.isDigit() || next in ".,٫٬_")) return null
        return match
    }

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        val normalized = Normalize.digits(body)

        if (ignoreWords.containsMatchIn(normalized) &&
            !hasIncomeAction(normalized) &&
            !hasExpenseAction(normalized) &&
            !hasTransferAction(normalized)
        ) {
            return ParseResult.Ignored
        }

        val match = transactionAmount(normalized)
            ?: return ParseResult.Failed("no monetary value detected", listOf(id))
        val rawNum = match.groups["num"]?.value
            ?: return ParseResult.Failed("amount group missing", listOf(id))
        val currency = (match.groups["lead"]?.value ?: match.groups["trail"]?.value)?.trim()
        val currencyCode = Normalize.currencyCode(currency)
        val labelled = primaryAmountLabel.containsMatchIn(normalized)
        val singleThreeDecimal = labelled && Regex("""\d+\.\d{3}""").matches(rawNum)
        val parsed = if (singleThreeDecimal) {
            val precision = runCatching { java.util.Currency.getInstance(currencyCode).defaultFractionDigits }.getOrNull()
            if (precision != 3) return ParseResult.Failed("ambiguous labelled amount separator", listOf(id))
            rawNum.toBigDecimalOrNull()
        } else Normalize.amount(rawNum)
        parsed ?: return ParseResult.Failed("amount unparseable", listOf(id))

        // Heuristic: prefer the most specific verb. Income > Transfer > Expense (default).
        val type = when {
            hasIncomeAction(normalized) -> TxType.INCOME
            hasTransferAction(normalized) -> TxType.TRANSFER
            else -> TxType.EXPENSE
        }

        val party = merchantHint.find(normalized)?.groupValues?.get(1)?.trim()

        // Confidence model: bare amount = very low, +currency = +0.15, +merchant = +0.10,
        // explicit action verb = +0.10. Caps at 0.55 so user always confirms.
        var confidence = 0.20f
        if (currency != null) confidence += 0.15f
        if (party != null) confidence += 0.10f
        if (hasIncomeAction(normalized) ||
            hasTransferAction(normalized) ||
            hasExpenseAction(normalized)
        ) confidence += 0.10f

        return ParseResult.Success(
            type = type,
            amount = Money.of(parsed, currencyCode),
            merchant = party.takeIf { type == TxType.EXPENSE },
            counterparty = party.takeUnless { type == TxType.EXPENSE },
            balanceAfter = null,
            occurredAt = receivedAt,
            confidence = confidence.coerceAtMost(0.55f),
            templateId = id,
        )
    }

    private fun hasIncomeAction(body: String): Boolean =
        latinIncomeWords.containsMatchIn(body) || arabicIncomeWords.containsMatchIn(body)

    private fun hasExpenseAction(body: String): Boolean =
        latinExpenseWords.containsMatchIn(body) || arabicExpenseWords.containsMatchIn(body)

    private fun hasTransferAction(body: String): Boolean =
        latinTransferWords.containsMatchIn(body) || arabicTransferWords.containsMatchIn(body)
}
