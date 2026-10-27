package com.athar.ingestion

import com.athar.core.domain.accounting.IncomeClassification
import com.athar.core.domain.accounting.IncomeDecision
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.SmsParseStatus
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.IncomeSourcePreferencesRepository
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import com.athar.ingestion.smsparser.DeclinedAttemptNotice055
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SmsParser
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real ingestion pipeline.
 *
 * For each [RawIngestEvent]:
 *   1. Record the raw event in the SMS audit log (status NEW initially).
 *   2. Run the parser. Update audit with the outcome (PARSED / FAILED / IGNORED).
 *   3. On PARSED: categorize via [CategoryRuleRepository], persist a pending [Transaction],
 *      and link `parsedTransactionId` on the audit entry.
 *
 * Idempotency:
 *   - A source-bound replay returns its unchanged stored transaction inside a Room
 *     lookup/insert transaction; generic edits retain strict collision refusal.
 *   - Audit entries link the returned stable row id, never a fresh replay UUID.
 *   - The audit table uses OnConflictStrategy.IGNORE on insert; duplicate-event records
 *     keep the first row instead of duplicating.
 */
@Singleton
class SmsIngestionPipeline @Inject constructor(
    private val parser: SmsParser,
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
    private val resolver: MerchantCategoryResolver,
    private val audit: SmsAuditRepository,
    private val prefs: UserPreferencesRepository,
    private val incomeSources: IncomeSourcePreferencesRepository,
    private val clock: Clock,
    private val recording: FinancialRecordingAdapter? = null,
) {

    suspend fun process(event: RawIngestEvent) {
        val recordingSession = recording?.beginSessionIfEnabled()
        val auditId = audit.record(event)
        when (val result = parser.parse(event)) {
            ParseResult.Ignored -> {
                audit.updateParseResult(auditId, SmsParseStatus.IGNORED, parsedTransactionId = null, error = null)
                Timber.d("[ingest] ignored sender=%s", event.sender)
            }
            is ParseResult.Failed -> {
                audit.updateParseResult(
                    auditId,
                    SmsParseStatus.FAILED,
                    parsedTransactionId = null,
                    error = "${result.reason} · tried=${result.templateAttempts.joinToString(",")}",
                )
                Timber.w("[ingest] parse failed: %s (tried %s)", result.reason, result.templateAttempts)
            }
            is ParseResult.Success -> {
                // A proposed amount on a positively evidenced refused attempt is not
                // money. Retain its raw audit without a financial row or history repair.
                if (result.posting.state == com.athar.core.domain.model.financial.PostingState.DECLINED &&
                    DeclinedAttemptNotice055.matches(event.body)
                ) {
                    audit.updateParseResult(auditId, SmsParseStatus.IGNORED, parsedTransactionId = null, error = null)
                    return
                }
                val txId = persist(event, result, recordingSession)
                audit.updateParseResult(
                    auditId,
                    SmsParseStatus.PARSED,
                    parsedTransactionId = txId,
                    error = null,
                )
            }
        }
    }

    private suspend fun persist(
        event: RawIngestEvent,
        parsed: ParseResult.Success,
        recordingSession: com.athar.core.data.ledger.api.FinancialRecordingSession?,
    ): String {
        // Self-transfer detection: if the parsed transfer's body or counterparty mentions
        // one of the user's own account-number snippets (e.g. "0930", "4268"), tag the
        // merchant as a self-move so it shows up as a "Possible savings — confirm" item
        // rather than getting filed away as a generic outgoing transfer.
        val ownAccounts = runCatching { prefs.ownAccountNumbers().first() }.getOrDefault(emptyList())
        val incomeEligible = runCatching {
            incomeSources.settings().first().eligibleSenders
        }.getOrDefault(emptySet())
        // A bank-evidenced card repayment is not a possible savings contribution.
        // Only the enabled fresh-ingestion path gains this treatment; legacy rows
        // and unresolved transfers are never reinterpreted here.
        val isSupportedRepayment = recordingSession != null && supportedCardRepaymentDraft(event, parsed) != null
        val isSelfTransfer = !isSupportedRepayment && parsed.type.name == "TRANSFER" && ownAccounts.isNotEmpty() && (
            ownAccounts.any { acct ->
                acct.isNotBlank() && (
                    parsed.counterparty?.contains(acct, ignoreCase = true) == true ||
                    event.body.contains(acct, ignoreCase = true)
                )
            }
        )
        val routedAccountId = accounts.resolveForIngest(
            sender = event.sender,
            body = event.body,
            counterparty = parsed.counterparty,
        )?.id ?: MANUAL_ACCOUNT_ID
        // Owned-account set for income demotion: any configured account (except pure manual).
        val ownedAccountIds = runCatching {
            accounts.observeActive().first().map { it.id }.toSet()
        }.getOrDefault(emptySet())
        val incomeDecision = IncomeClassification.decide(
            parsedType = parsed.type,
            sender = event.sender,
            body = event.body,
            accountId = routedAccountId,
            incomeEligibleSenders = incomeEligible,
            ownedAccountIds = ownedAccountIds,
            matchedAsInternalTransfer = isSelfTransfer,
        )
        val effectiveType = when (incomeDecision) {
            IncomeDecision.INTERNAL_TRANSFER -> TxType.TRANSFER
            IncomeDecision.REFUND_OR_REVERSAL -> parsed.type
            IncomeDecision.COUNT_AS_INCOME -> TxType.INCOME
            IncomeDecision.NEEDS_REVIEW -> parsed.type
            IncomeDecision.NOT_INCOME -> parsed.type
        }
        val effectiveMerchant = when {
            incomeDecision == IncomeDecision.INTERNAL_TRANSFER || isSelfTransfer ->
                "تحويل داخلي · ادخار محتمل"
            else -> parsed.merchant ?: parsed.counterparty ?: event.sender
        }
        val merchantNormalized = effectiveMerchant.lowercase().trim()
        val resolution = resolver.resolve(merchantNormalized, effectiveType)
        val suggestion = resolution.suggestion
        val now = clock.now()
        val occurredAt = parsed.occurredAt ?: event.receivedAt
        val date = occurredAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
        val txId = UUID.randomUUID().toString()

        // A matched amount/type alone is not proof that funds posted. Keep contradictory
        // lifecycle evidence in review; categorization cannot grant financial authority.
        // CONFIRMED only when a trusted LOCAL rule matched (personal, auto-learned, or
        // bundled seed) — ENH-009: a catalog suggestion alone can never flip a row into a
        // financial posting, and it cannot change legacy eligibility for merchants already
        // covered by local rules. Everything else lands in PENDING for explicit review.
        // Spam-shaped messages are filtered out earlier by IgnorePatterns and never reach
        // this point (they're tagged "ignored" in the SMS audit log instead).
        val confidence = suggestion.confidence
        val needsReview = incomeDecision == IncomeDecision.NEEDS_REVIEW
        val initialStatus = when {
            recordingSession != null || parsed.posting.state != com.athar.core.domain.model.financial.PostingState.POSTED ->
                TxStatus.PENDING // Category cannot override hold, decline or other contradictory evidence.
            isSelfTransfer || incomeDecision == IncomeDecision.INTERNAL_TRANSFER -> TxStatus.PENDING
            needsReview -> TxStatus.PENDING
            effectiveType == TxType.INCOME && incomeDecision != IncomeDecision.COUNT_AS_INCOME ->
                TxStatus.PENDING
            resolution.hadTrustedLocalRule && suggestion.categoryId != null -> TxStatus.CONFIRMED
            else -> TxStatus.PENDING
        }
        val notes = when {
            incomeDecision == IncomeDecision.INTERNAL_TRANSFER || isSelfTransfer ->
                "تحويل بين حساباتك — أكِّد ما إذا كان ادخارًا"
            needsReview -> "يحتاج مراجعة — ليس دخلاً مؤكداً بعد"
            else -> null
        }

        val tx = Transaction(
            id = txId,
            accountId = routedAccountId,
            type = effectiveType,
            amount = parsed.amount,
            date = date,
            occurredAt = occurredAt,
            merchant = effectiveMerchant,
            merchantNormalized = merchantNormalized,
            categoryId = if (effectiveType == TxType.TRANSFER) null else suggestion.categoryId,
            notes = notes,
            source = if (event.source == IngestSource.NOTIFICATION) IngestSource.NOTIFICATION else IngestSource.SMS,
            sourceRefId = event.rawId,
            status = initialStatus,
            confidence = confidence,
            createdAt = now,
            updatedAt = now,
            categorySource = suggestion.categoryId?.let { suggestion.source }
                .takeIf { effectiveType != TxType.TRANSFER },
        )
        val storedId = transactions.upsertIngest(tx)
        // A returned older owner is replay: never reinterpret or mass-confirm history.
        if (recordingSession != null && storedId == txId && !isSelfTransfer) {
            recording?.recordNewPurchase(event, parsed, storedId, now, recordingSession)
        }
        Timber.i(
            "[ingest] OK %s %s %s → cat=%s (%s, conf=%.2f) template=%s",
            parsed.type, parsed.amount.amount.toPlainString(),
            parsed.merchant ?: "?",
            suggestion.categoryId ?: "—",
            suggestion.source,
            suggestion.confidence,
            parsed.templateId,
        )
        return storedId
    }

    private companion object {
    }
}
