package com.athar.ingestion.smsparser

/** Narrow positive admin/authentication shapes; digits alone never identify a notice. */
internal object NumericServiceNotices049 {
    enum class Disposition { NOT_NOTICE, NON_FINANCIAL, MIXED_FINANCIAL }

    private val beneficiaryAdmin = Regex(
        """\bbene[a-z]*[ec]iary\b[\s\S]{0,200}?\bhas\s+been\s+(?:successfully\s+)?(?:added|activated|deleted|removed)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val codeInstruction = Regex(
        """\b(?:use|enter)\s+the\s+code\s*:\s*\d{3,8}\b""",
        RegexOption.IGNORE_CASE,
    )
    private val arabicOtp = Regex("""(?:لا\s+تشارك[^\n\r]{0,40}الرمز|لديك\s+رمز\s*[:٫.]?\s*\d{3,8})""")
    private val futureTransferCode = Regex(
        """^\s*رمز\s+[^\r\n:：]{1,40}\s+هو\s*[:：]\s*\d{3,8}\b[\s\S]{0,180}لإتمام\s+عملية\s+التحويل""",
    )

    // Monetary fields also protect currencies not listed here, e.g. Amount:JPY500.
    private val monetaryField = Regex(
        """\b(?:amount|total|value)\s*[:：]|(?:المبلغ|مبلغ|قيمة)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )
    private val moneyEvidence = Regex(
        """(?:\b[A-Z]{3}\s*\d|\d[\d.,\u00A0 ]{0,14}\s*(?:[A-Z]{3}\b|SR\b|ر\.?\s*س|ريال|درهم|دينار|﷼|[$€£₹¥₺])|[$€£﷼₹¥₺]\s*\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val completedMovement = Regex(
        """\b(?:debited|credited|deposited|paid|refunded|withdrawn|charged|transferred)\b|\b(?:completed|successful)\s+(?:payment|purchase|transfer)\b|(?<![\p{L}\p{N}_])تم(?:ت)?\s+(?:عملية\s+)?(?:شراء|خصم|دفع|سداد|إيداع|ايداع|استرداد|تحويل)""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(body: String): Disposition {
        val normalized = Normalize.digits(body)
        val futureAuthentication = futureTransferCode.containsMatchIn(normalized)
        val separatePosting = futureAuthentication && normalized.lineSequence().any {
            BankPostedLanguage.evidence(it).support ==
                com.athar.core.domain.model.financial.PostingSupport.EXPLICIT_POSTED
        }
        if (futureAuthentication && !completedMovement.containsMatchIn(normalized) && !separatePosting) {
            return Disposition.NON_FINANCIAL
        }
        val positive = beneficiaryAdmin.containsMatchIn(normalized) ||
            codeInstruction.containsMatchIn(normalized) || arabicOtp.containsMatchIn(normalized) || futureAuthentication
        if (!positive) return Disposition.NOT_NOTICE
        // A real money clause wins over both new and older blanket admin patterns.
        // A future instruction 'use the code to transfer' is not a completed movement.
        return if (monetaryField.containsMatchIn(normalized) ||
            moneyEvidence.containsMatchIn(normalized) || completedMovement.containsMatchIn(normalized)
        ) Disposition.MIXED_FINANCIAL else Disposition.NON_FINANCIAL
    }
}
