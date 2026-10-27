package com.athar.core.domain.model

fun specificMerchantKey(
    merchantNormalized: String,
    merchant: String,
): String? {
    // Do not normalize away evidence that this is a processor authorization label.
    if (HeldProcessorDescriptor.containsMatchIn(merchantNormalized) ||
        HeldProcessorDescriptor.containsMatchIn(merchant)
    ) return null
    return merchantNormalized
        .ifBlank { merchant }
        .trim()
        .lowercase()
        .takeIf { it.isSpecificMerchantKey() }
}

fun String.isSpecificMerchantKey(): Boolean {
    if (HeldProcessorDescriptor.containsMatchIn(this)) return false
    if (length < 3) return false
    if (all { it.isDigit() || it.isWhitespace() || it == '-' || it == '+' }) return false
    if (this in GenericMerchantKeys) return false
    if (GenericMerchantKeys.any { this == it || startsWith("$it ") }) return false
    return true
}

private val HeldProcessorDescriptor = Regex("""\*\s*PEND(?:ING)?\b""", RegexOption.IGNORE_CASE)

private val GenericMerchantKeys = setOf(
    "unknown",
    "merchant",
    "bank",
    "cash",
    "purchase",
    "online purchase",
    "transfer",
    "payment",
    "manual adjustment",
    "غير معروف",
    "تاجر",
    "بنك",
    "كاش",
    "شراء",
    "تحويل",
    "دفع",
    "تسوية",
    "تسوية يدوية",
)
