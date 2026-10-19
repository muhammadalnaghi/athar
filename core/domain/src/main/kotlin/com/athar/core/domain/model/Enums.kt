package com.athar.core.domain.model

/** Master Brief §4.5 — Transaction.type. */
enum class TxType { EXPENSE, INCOME, TRANSFER }

/** Master Brief §4.5 — Transaction.source. */
enum class IngestSource { MANUAL, SMS, NOTIFICATION, SHARE, IMPORT, RECURRING }

/** Master Brief §4.5 — Transaction.status. */
enum class TxStatus { PENDING, CONFIRMED, DISMISSED }

/** Master Brief §4.5 — Category.kind. */
enum class CategoryKind { EXPENSE, INCOME }

/**
 * Account types. v2 (G-4) expanded from the original DEBIT/CREDIT/CASH/OTHER
 * to the six-bucket TMOAP-style taxonomy. Migration MIGRATION_4_5 remaps
 * legacy DEBIT → CHECKING and CREDIT → CREDIT_CARD.
 */
enum class AccountType { CHECKING, SAVINGS, CREDIT_CARD, CASH, INVESTMENT, OTHER }

/** Master Brief §4.7 — CategoryRule.patternType. */
enum class PatternType { SUBSTRING, REGEX, EXACT }

/** Source of an auto-categorization decision. Master Brief §4.7. */
enum class CategorySource { RULE_EXACT, RULE_SUBSTRING, RULE_REGEX, CLASSIFIER, CATALOG, USER, UNKNOWN }

/** SMS audit parse status. Master Brief §4.5. */
enum class SmsParseStatus { PARSED, FAILED, IGNORED }
