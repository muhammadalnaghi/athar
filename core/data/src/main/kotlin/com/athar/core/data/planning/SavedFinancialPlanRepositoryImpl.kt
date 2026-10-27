package com.athar.core.data.planning

import android.database.sqlite.SQLiteConstraintException
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WM028 fast-store: real Room-backed [SavedFinancialPlanRepository].
 *
 * Guarantees (WM028-fast-saved-planning-contract):
 *  - validation is main-safe and bounded, and every refusal happens BEFORE any
 *    statement runs - conflicts and validation failures change no storage;
 *  - the precheck read, the CAS write and the caller-visible result run inside
 *    ONE Room transaction ([SavedFinancialPlanTransactionRunner]). There is no
 *    separate post-write re-read, so the returned object is exactly this
 *    successful write and can never reflect a later writer commit;
 *  - `expectedRevision == null` is create-only (duplicate id is a typed
 *    conflict, including under a concurrent race); updates/deletes require the
 *    exact current revision; the repository owns revision values
 *    (`expectedRevision + 1` on update, `1` on create) and ignores any
 *    revision carried on the inbound document;
 *  - id, kind and creation time are immutable; revision overflow refuses;
 *  - currencies and payload decimal strings are stored and returned verbatim;
 *  - the payload is stored as an opaque versioned JSON OBJECT: the guard here
 *    is structural and bounded only (object root, duplicate keys, depth during
 *    the raw token walk, member/string budgets - see
 *    [JsonReaderSavedFinancialPlanPayloadGuard]) - feature lanes own
 *    payload-version codecs, so unsupported and future documents stay storable
 *    and loadable by their owning feature;
 *  - all messages are the frozen constants - stored JSON is never echoed.
 */
@Singleton
internal class SavedFinancialPlanRepositoryImpl @Inject constructor(
    private val dao: SavedFinancialPlanDao,
    private val clock: Clock,
    private val txRunner: SavedFinancialPlanTransactionRunner,
    private val payloadGuard: SavedFinancialPlanPayloadGuard,
) : SavedFinancialPlanRepository {

    override fun observe(kind: SavedFinancialPlanKind): Flow<List<SavedFinancialPlan>> =
        dao.observeByKind(kind.name).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SavedFinancialPlan? = dao.getById(id)?.toDomain()

    override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
        // The inbound object is fully guarded before Room sees any of it -
        // main-safe: the bounded JSON walk runs off the caller thread and is
        // cancellation-preserving.
        validate(plan)
        val now = clock.now().toEpochMilliseconds()
        return txRunner.inTransaction {
            if (expectedRevision == null) createOnly(plan, now) else update(plan, expectedRevision, now)
        }
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        val changed = dao.deleteIfRevisionMatches(id, expectedRevision)
        if (changed == 0) throw SavedFinancialPlanConflictException()
    }

    private suspend fun createOnly(plan: SavedFinancialPlan, now: Long): SavedFinancialPlan {
        // Inside the transaction this read is authoritative: a concurrent
        // creator transaction commits or aborts before ours proceeds, so a
        // loser conflicts here and the ABORT insert below never runs.
        if (dao.getById(plan.id) != null) throw SavedFinancialPlanConflictException()
        val entity = SavedFinancialPlanEntity(
            id = plan.id,
            kind = plan.kind.name,
            title = plan.title,
            currency = plan.currency,
            payloadVersion = plan.payloadVersion,
            payloadJson = plan.payloadJson,
            revision = FIRST_REVISION,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        try {
            dao.insert(entity)
        } catch (e: SQLiteConstraintException) {
            // Race backstop if the ABORT insert still fires on a device: a
            // typed conflict, never a leaked SQLite exception, never an
            // overwrite.
            throw SavedFinancialPlanConflictException()
        }
        return entity.toDomain()
    }

    private suspend fun update(
        plan: SavedFinancialPlan,
        expectedRevision: Long,
        now: Long,
    ): SavedFinancialPlan {
        // Overflow must refuse before any statement runs.
        if (expectedRevision >= Long.MAX_VALUE) throw SavedFinancialPlanValidationException()
        val stored = dao.getById(plan.id) ?: throw SavedFinancialPlanConflictException()
        if (stored.kind != plan.kind.name) throw SavedFinancialPlanValidationException()
        val newRevision = expectedRevision + 1
        val changed = dao.updateIfRevisionMatches(
            id = plan.id,
            title = plan.title,
            currency = plan.currency,
            payloadVersion = plan.payloadVersion,
            payloadJson = plan.payloadJson,
            expectedRevision = expectedRevision,
            newRevision = newRevision,
            updatedAtEpochMillis = now,
        )
        if (changed == 0) throw SavedFinancialPlanConflictException()
        // No re-read: the CAS success means storage now holds exactly these
        // values; kind and creation time are immutable and were captured above.
        // A separate post-CAS read could only observe a LATER writer commit.
        return SavedFinancialPlan(
            id = plan.id,
            kind = plan.kind,
            title = plan.title,
            currency = plan.currency,
            payloadVersion = plan.payloadVersion,
            payloadJson = plan.payloadJson,
            revision = newRevision,
            createdAtEpochMillis = stored.createdAtEpochMillis,
            updatedAtEpochMillis = now,
        )
    }

    private suspend fun validate(plan: SavedFinancialPlan) {
        if (plan.id.isBlank() || plan.id.length > MAX_ID_CHARS) throw SavedFinancialPlanValidationException()
        if (plan.title.isBlank() || plan.title.length > MAX_TITLE_CHARS) throw SavedFinancialPlanValidationException()
        if (plan.currency.isBlank() || plan.currency.length > MAX_CURRENCY_CHARS) {
            throw SavedFinancialPlanValidationException()
        }
        if (plan.payloadVersion < 1) throw SavedFinancialPlanValidationException()
        if (plan.payloadJson.isBlank() || plan.payloadJson.length > MAX_PAYLOAD_CHARS) {
            throw SavedFinancialPlanValidationException()
        }
        if (plan.revision < 0) throw SavedFinancialPlanValidationException()
        withContext(Dispatchers.Default) {
            // Strict structural guard on the raw document: object root,
            // duplicate keys, depth/member/text budgets - all enforced before
            // anything else parses or stores it. Cancellation-preserving.
            payloadGuard.validate(plan.payloadJson)
        }
    }

    private fun SavedFinancialPlanEntity.toDomain(): SavedFinancialPlan = SavedFinancialPlan(
        id = id,
        kind = SavedFinancialPlanKind.valueOf(kind),
        title = title,
        currency = currency,
        payloadVersion = payloadVersion,
        payloadJson = payloadJson,
        revision = revision,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        const val FIRST_REVISION: Long = 1L
        const val MAX_ID_CHARS = 64
        const val MAX_TITLE_CHARS = 200
        const val MAX_CURRENCY_CHARS = 16
        const val MAX_PAYLOAD_CHARS = 262_144

        /** Structural budgets for the opaque payload guard (repair-oracle bound). */
        const val MAX_PAYLOAD_DEPTH = 12
        const val MAX_PAYLOAD_MEMBERS = 1024
        const val MAX_PAYLOAD_STRING_CHARS = 4096
    }
}
