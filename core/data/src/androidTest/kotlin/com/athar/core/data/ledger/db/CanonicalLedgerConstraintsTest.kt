package com.athar.core.data.ledger.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.db.atharDatabaseBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM028-13B1 regression: uniqueness and foreign keys reject invalid writes
 * cleanly — duplicate event identity, colliding proven economic identity,
 * an observation bound to a second event, dangling observation/event/account
 * references, cross-event component attribution — and an account deletion
 * with surviving legs fails instead of silently erasing financial history.
 * ABORT semantics mean the first insert is never overwritten by the loser.
 *
 * Sandbox: uniquely named throwaway database built through the real
 * production builder (Room enables FK enforcement on open); `athar.db` is
 * never touched.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalLedgerConstraintsTest {

    @Test
    fun uniquenessForeignKeysAndAccountDeletionRestrictionsRejectWithoutOverwrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "athar-wm028-13b1-constraints"
        context.deleteDatabase(databaseName)
        val database = atharDatabaseBuilder(context, databaseName).build()
        val dao = database.canonicalLedgerDao()
        try {
            val raw = database.openHelper.writableDatabase
            raw.execSQL(ACCOUNT_SQL)
            dao.insertObservation(CanonicalLedgerSourceObservationEntity("sms", "obs-1", "evidence-1", "payload-1"))
            dao.insertEvent(event("ev-a", "100.00", "SAR"))
            dao.insertEvent(event("ev-b", "200.00", "SAR"))

            // ABORT, never REPLACE: a conflicting event insert is rejected and
            // the original row keeps its exact values.
            val rejected = runCatching { dao.insertEvent(event("ev-a", "999.99", "USD")) }.exceptionOrNull()
            assertThat(rejected).isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(dao.getEvent("ev-a")!!.bookedAmount).isEqualTo("100.00")
            assertThat(dao.getEvent("ev-a")!!.bookedCurrency).isEqualTo("SAR")

            // Proven economic identity is unique; identity-less events never
            // collide with each other (observation-scoped identities stay separate).
            dao.insertEvent(event("ev-c", "1.00", "USD", issuer = "ISSUER", reference = "REF-1"))
            assertThat(runCatching { dao.insertEvent(event("ev-d", "2.00", "USD", issuer = "ISSUER", reference = "REF-1")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            dao.insertEvent(event("ev-e", "3.00", "USD"))
            dao.insertEvent(event("ev-f", "4.00", "USD"))

            // Each source observation attaches to at most one event.
            dao.insertEventSource(CanonicalLedgerEventSourceEntity("sms", "obs-1", "ev-a"))
            assertThat(runCatching { dao.insertEventSource(CanonicalLedgerEventSourceEntity("sms", "obs-1", "ev-b")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(dao.getEventSource("sms", "obs-1")!!.eventId).isEqualTo("ev-a")

            // Associations reject unknown observations and unknown events.
            assertThat(runCatching { dao.insertEventSource(CanonicalLedgerEventSourceEntity("sms", "missing-obs", "ev-b")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            dao.insertObservation(CanonicalLedgerSourceObservationEntity("sms", "obs-2", null, null))
            assertThat(runCatching { dao.insertEventSource(CanonicalLedgerEventSourceEntity("sms", "obs-2", "missing-event")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            // Isolate the event FK from the observation FK, then prove that
            // another real observation may attach to the same event.
            dao.insertEventSource(CanonicalLedgerEventSourceEntity("sms", "obs-2", "ev-a"))
            assertThat(dao.getEventSource("sms", "obs-2")!!.eventId).isEqualTo("ev-a")

            // Components and legs are event-scoped; same-event attribution only.
            dao.insertComponent(CanonicalLedgerComponentEntity("ev-a", "comp-1", "FEE", "5.00", "SAR", "UNKNOWN", "fee-evidence"))
            assertThat(dao.listComponentsForEvent("ev-a").single().feeRelation).isEqualTo("UNKNOWN")
            dao.insertLeg(CanonicalLedgerLegEntity("ev-a", "leg-1", "rt-cash", "-105.00", "SAR", "comp-1"))
            assertThat(runCatching { dao.insertLeg(CanonicalLedgerLegEntity("missing-event", "leg-x", "rt-cash", "-1.00", "SAR", null)) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(runCatching { dao.insertLeg(CanonicalLedgerLegEntity("ev-b", "leg-y", "missing-account", "-1.00", "SAR", null)) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(runCatching { dao.insertLeg(CanonicalLedgerLegEntity("ev-b", "leg-z", "rt-cash", "-1.00", "SAR", "comp-1")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)

            // Deleting an account that still has financial legs must fail, not
            // cascade away history.
            assertThat(runCatching { raw.execSQL("DELETE FROM account WHERE id = 'rt-cash'") }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(dao.listLegsForEvent("ev-a")).hasSize(1)
            assertThat(dao.listLegsForEvent("ev-a").single().delta).isEqualTo("-105.00")
        } finally {
            database.close()
        }
    }

    private fun event(
        eventId: String,
        amount: String,
        currency: String,
        issuer: String? = null,
        reference: String? = null,
    ) = CanonicalLedgerEventEntity(
        eventId = eventId,
        economicIdentityIssuer = issuer,
        economicIdentityReference = reference,
        snapshotFormatVersion = 1L,
        policyRevision = "WM028-07-R6-v1",
        graphRevision = "WM028-11-M2E-v1",
        snapshotJson = "{\"formatVersion\":1}",
        bookedAmount = amount,
        bookedCurrency = currency,
        bookedBasis = "SETTLEMENT",
        kind = "PURCHASE",
        kindResolved = true,
        incomeNature = "UNCLASSIFIED",
        recordingOutcome = "RECORDED",
        eventDate = "2026-01-01",
        routing = "ROUTED",
        composition = "RESOLVED",
    )

    private companion object {
        val ACCOUNT_SQL = """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES ('rt-cash', 'Cash', 'CASH', 'SAR', 0, 'SAR', '[]', NULL, 0, 1, NULL, 1, 1)
        """.trimIndent()
    }
}
