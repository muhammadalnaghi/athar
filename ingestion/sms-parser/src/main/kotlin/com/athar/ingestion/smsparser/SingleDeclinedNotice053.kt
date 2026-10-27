package com.athar.ingestion.smsparser

import com.athar.core.domain.model.financial.PostingSupport

/**
 * WM053 — a positively identified single declined payment attempt is a notice,
 * not money. The decline must be stated by a line-headed `Reason:`/`Status:`
 * (or conservative Arabic السبب/الحالة) field whose value begins with a decline
 * verb or carries the insufficient-balance cause — never by the word "declined"
 * appearing anywhere in the body. A pure decline supports no movement; the
 * amount on it is proposed, not spent.
 *
 * Guard: any separate monetary clause keeps the body parseable — a debited fee,
 * a later completed/successful retry, a refund/reversal, or any line carrying
 * explicit posted-language evidence. The per-line posted check prevents the
 * aggregate DECLINED state (which the decline header itself produces) from
 * hiding that separate clause. Reason/status fields are not exempt: a later
 * completed status or a same-line booked fee must remain parseable too.
 * Pending/authentication wording alone never matches; a merchant named
 * DECLINED is not a header and never matches.
 */
internal object SingleDeclinedNotice053 {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)

    private val reasonDeclined = Regex(
        """^\s*Reason\s*[:：]\s*(?:declin\w*|reject\w*|unsuccessful|failed)\b|^\s*Reason\s*[:：][^\r\n]{0,60}\binsufficient\s+(?:balance|funds)\b""",
        options,
    )
    private val statusDeclined = Regex(
        """^\s*Status\s*[:：]\s*(?:declin\w*|reject\w*|unsuccessful|failed)\b""",
        options,
    )
    private val arabicStatusDeclined = Regex(
        """^\s*الحالة\s*[:：]\s*(?:مرفوض|فاشل|غير\s+(?:ناجحة?|مكتملة?))""",
        RegexOption.MULTILINE,
    )
    private val arabicReasonDeclined = Regex(
        """^\s*السبب\s*[:：][^\r\n]{0,60}(?:عدم\s+كفاية\s+الرصيد|رصيد\s+غير\s+كاف|غير\s+كاف)""",
        RegexOption.MULTILINE,
    )
    private val declinedHeader = listOf(reasonDeclined, statusDeclined, arabicStatusDeclined, arabicReasonDeclined)

    /** A credit-limit cause alone does not certify a credited movement. */
    private val creditLimit = Regex("""\bcredit\s+limit\b""", RegexOption.IGNORE_CASE)

    /** Real movement vocabulary on any line blocks the ignore. */
    private val separateMoney = Regex(
        """\b(?:fee|charge[sd]?|debit(?:ed)?|credit(?:ed)?|deduct(?:ed)?|refund\w*|revers\w*|posted|completed|successful|paid)\b|رسوم|استرداد|عكس\s+العملية|تم\s+(?:خصم|سداد|دفع|إيداع|ايداع)""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(body: String): Boolean {
        val normalized = Normalize.digits(body)
        if (declinedHeader.none { it.containsMatchIn(normalized) }) return false
        normalized.lineSequence().forEach { line ->
            if (separateMoney.containsMatchIn(creditLimit.replace(line, ""))) return false
            if (BankPostedLanguage.evidence(line).support == PostingSupport.EXPLICIT_POSTED) return false
        }
        return true
    }
}
