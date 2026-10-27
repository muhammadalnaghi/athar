package com.athar.ingestion.smsparser

import com.athar.core.common.money.Money
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Pure-Kotlin parser interface. Master Brief §5.5.
 *
 * Implementations register against a sender (e.g. AlRajhiBank) and run a template
 * registry against the body. JVM-testable — no Android imports allowed in this module.
 */
fun interface SmsParser {
    fun parse(event: RawIngestEvent): ParseResult
}

sealed interface ParseResult {
    data class Success(
        val type: TxType,
        val amount: Money,
        val merchant: String?,
        val counterparty: String?,
        val balanceAfter: Money?,
        val occurredAt: Instant?,
        val confidence: Float,
        val templateId: String,
        /** A successful shape/category score is not proof that money posted. */
        val posting: PostingEvidence = PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY),
        /** Source-stated date only; never the receive-time fallback. */
        val bankEventDate: LocalDate? = null,
    ) : ParseResult

    data class Failed(
        val reason: String,
        val templateAttempts: List<String>,
    ) : ParseResult

    data object Ignored : ParseResult
}

/**
 * Bank-template strategy. Each template owns the regex set for one bank + transaction kind.
 * Templates are tried in priority order; the first Success wins. Ignored short-circuits the rest.
 */
interface BankTemplate {
    val id: String
    val senderMatcher: SenderMatcher
    fun tryParse(body: String, receivedAt: Instant): ParseResult
}

/** Sender filter — exact, regex, or "any of these". */
sealed interface SenderMatcher {
    fun matches(sender: String): Boolean

    data class Exact(val value: String) : SenderMatcher {
        override fun matches(sender: String) =
            sender.trim().equals(value.trim(), ignoreCase = true)
    }

    data class AnyOf(val values: Set<String>) : SenderMatcher {
        override fun matches(sender: String): Boolean {
            val normalized = sender.trim()
            return values.any { it.trim().equals(normalized, ignoreCase = true) }
        }
    }

    data class Regex(val pattern: kotlin.text.Regex) : SenderMatcher {
        override fun matches(sender: String) = pattern.matches(sender.trim())
    }
}
