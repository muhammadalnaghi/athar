package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxType
import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * Candidate leg for cross-account own-transfer pairing.
 *
 * [direction] is the economic direction relative to [accountId]:
 * OUT = money leaving this owned account, IN = money entering it.
 */
data class TransferLeg(
    val id: String,
    val accountId: String,
    val direction: TransferDirection,
    val amount: Money,
    val occurredAt: Instant?,
    val type: TxType,
    /** Optional bank reference shared across both SMS legs when present. */
    val bankReference: String? = null,
)

enum class TransferDirection { OUT, IN }

data class TransferMatch(
    val outgoingId: String,
    val incomingId: String,
    val amount: Money,
)

data class TransferMatchResult(
    val matches: List<TransferMatch>,
    /** Legs that look like own-account moves but lacked a safe counterpart. */
    val unmatchedNeedsReviewIds: List<String>,
)

/**
 * One-to-one pairing of owned-account OUT/IN legs.
 *
 * Never matches by amount alone: both legs must reference different owned accounts,
 * share currency + exact amount, and fall within [windowMillis]. When multiple
 * identical amounts exist in the window, the nearest unused counterpart wins.
 */
object InternalTransferMatcher {

    fun match(
        legs: List<TransferLeg>,
        ownedAccountIds: Set<String>,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): TransferMatchResult {
        require(windowMillis >= 0L)
        if (ownedAccountIds.size < 2) {
            return TransferMatchResult(emptyList(), emptyList())
        }

        val owned = legs.filter { it.accountId in ownedAccountIds }
        val outs = owned.filter { it.direction == TransferDirection.OUT }.toMutableList()
        val inns = owned.filter { it.direction == TransferDirection.IN }.toMutableList()
        val matches = mutableListOf<TransferMatch>()
        val usedIn = mutableSetOf<String>()
        val usedOut = mutableSetOf<String>()

        // Prefer exact bank-reference matches first (strongest evidence).
        for (out in outs.sortedBy { it.occurredAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }) {
            val ref = out.bankReference?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val candidate = inns.firstOrNull { inn ->
                inn.id !in usedIn &&
                    inn.accountId != out.accountId &&
                    inn.amount == out.amount &&
                    inn.bankReference?.trim().equals(ref, ignoreCase = true) &&
                    withinWindow(out.occurredAt, inn.occurredAt, windowMillis)
            } ?: continue
            usedOut += out.id
            usedIn += candidate.id
            matches += TransferMatch(out.id, candidate.id, out.amount)
        }

        // Then time-proximate amount+currency matches across different owned accounts.
        for (out in outs.sortedBy { it.occurredAt?.toEpochMilliseconds() ?: Long.MAX_VALUE }) {
            if (out.id in usedOut) continue
            val candidate = inns
                .filter { inn ->
                    inn.id !in usedIn &&
                        inn.accountId != out.accountId &&
                        inn.amount == out.amount &&
                        withinWindow(out.occurredAt, inn.occurredAt, windowMillis)
                }
                .minByOrNull { inn -> timeDistanceMillis(out.occurredAt, inn.occurredAt) }
                ?: continue
            usedOut += out.id
            usedIn += candidate.id
            matches += TransferMatch(out.id, candidate.id, out.amount)
        }

        val unmatched = (outs.map { it.id } + inns.map { it.id })
            .filter { it !in usedOut && it !in usedIn }
            .distinct()

        return TransferMatchResult(matches = matches, unmatchedNeedsReviewIds = unmatched)
    }

    private fun withinWindow(a: Instant?, b: Instant?, windowMillis: Long): Boolean {
        if (a == null || b == null) return windowMillis == Long.MAX_VALUE
        return timeDistanceMillis(a, b) <= windowMillis
    }

    private fun timeDistanceMillis(a: Instant?, b: Instant?): Long {
        if (a == null || b == null) return Long.MAX_VALUE
        return abs(a.toEpochMilliseconds() - b.toEpochMilliseconds())
    }

    const val DEFAULT_WINDOW_MILLIS: Long = 36L * 60L * 60L * 1000L // 36h
}
