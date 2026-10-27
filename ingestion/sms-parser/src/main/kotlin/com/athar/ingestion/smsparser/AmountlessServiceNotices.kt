package com.athar.ingestion.smsparser

/**
 * Positively identified service notices, not a blanket no-amount discard rule.
 * A numeric body always continues to existing templates: mixed payment/admin
 * messages, reference numbers and all supported numeral scripts stay diagnosable.
 */
internal object AmountlessServiceNotices {
    private val patterns = listOf(
        Regex("""\bbeneficiary\s+(?:added|activated|deleted|removed)\s*:""", RegexOption.IGNORE_CASE),
        Regex("""\bbeneficiary\b[^\r\n]{0,120}\bhas\s+been\s+(?:added|activated|deleted|removed)\b""", RegexOption.IGNORE_CASE),
        Regex("""\byour\s+wallet\s+is\s+unclaimed\b""", RegexOption.IGNORE_CASE),
        Regex("""\byour\s+(?:international\s+)?transfer\s+to\b[^\r\n]{1,160}\b(?:is\s+(?:on\s+the\s+way|complete)|has\s+been\s+declined\s+by\s+the\s+receiving\s+bank)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bregistered\s+for\s+service\s*:""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:fingerprint|quick)\s+login\s+service\s+has\s+been\s+(?:successfully\s+)?activated\b""", RegexOption.IGNORE_CASE),
        Regex("""\ba\s+new\s+pin\s+has\s+been\s+(?:successfully\s+)?created\s+for\s+your\s+card\b""", RegexOption.IGNORE_CASE),
        Regex("""\byour\s+password\s+has\s+been\s+changed\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwe['’]ve\s+noticed\s+a\s+suspicious\s+behaviou?r\s+on\s+your\s+wallet\b""", RegexOption.IGNORE_CASE),
        Regex("""طلب\s+التسجيل\s+لخدمة\s+الدخول\s+السريع"""),
        Regex("""تم\s+[إا]يقاف\s+خدمة\s+الأهلي"""),
        Regex("""تحديث\s+سياسة\s+الخصوصية"""),
        Regex("""تحديث\s+تاريخ\s+انتهاء\s+الهوية"""),
        Regex("""تحويل\s+حالة\s+حساب(?:كم|ك)?\s+البنكي\s+لراكد"""),
    )

    fun matches(body: String): Boolean =
        body.none(Char::isDigit) && patterns.any { it.containsMatchIn(body) }
}
