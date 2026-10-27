package com.athar.core.data.ledger.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM028-13B1 regression: decimal money persists as exact TEXT and roundtrips
 * byte-for-byte — SAR/USD/JPY/KWD literals, signed legs, no REAL conversion,
 * no scale normalization. Also covers queryable accepted columns and the
 * consistent fresh-database revision initialization.
 *
 * Sandbox: uniquely named throwaway database built through the real
 * production builder; `athar.db` is never touched.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalLedgerMoneyRoundTripTest {

    @Test
    fun exactDecimalTextMoneyRoundTripsByteForByte() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "athar-wm028-13b1-money-roundtrip"
        context.deleteDatabase(databaseName)
        val database = atharDatabaseBuilder(context, databaseName).build()
        val dao = database.canonicalLedgerDao()
        try {
            dao.insertEvent(event("rt-sar", "200.00", "SAR", eventDate = "2026-01-01"))
            dao.insertEvent(event("rt-usd", "45.67", "USD", eventDate = null))
            dao.insertEvent(event("rt-jpy", "1200", "JPY", eventDate = "2026-02-03"))
            dao.insertEvent(event("rt-kwd", "15.750", "KWD", eventDate = "2026-02-03"))

            // Exact literal decimal strings and currencies survive a full
            // write/read cycle — "1200" stays "1200", "15.750" keeps its scale.
            assertThat(dao.getEvent("rt-sar")!!.bookedAmount).isEqualTo("200.00")
            assertThat(dao.getEvent("rt-sar")!!.bookedCurrency).isEqualTo("SAR")
            assertThat(dao.getEvent("rt-usd")!!.bookedAmount).isEqualTo("45.67")
            assertThat(dao.getEvent("rt-usd")!!.bookedCurrency).isEqualTo("USD")
            assertThat(dao.getEvent("rt-jpy")!!.bookedAmount).isEqualTo("1200")
            assertThat(dao.getEvent("rt-jpy")!!.bookedCurrency).isEqualTo("JPY")
            assertThat(dao.getEvent("rt-kwd")!!.bookedAmount).isEqualTo("15.750")
            assertThat(dao.getEvent("rt-kwd")!!.bookedCurrency).isEqualTo("KWD")
            assertThat(dao.getEvent("rt-usd")!!.eventDate).isNull()

            database.openHelper.writableDatabase.execSQL(ACCOUNT_SQL)
            dao.insertLeg(CanonicalLedgerLegEntity("rt-sar", "leg-out", "rt-cash", "-200.00", "SAR", null))
            dao.insertLeg(CanonicalLedgerLegEntity("rt-sar", "leg-in", "rt-cash", "12.50", "SAR", null))
            dao.insertLeg(CanonicalLedgerLegEntity("rt-kwd", "leg-kwd", "rt-cash", "-15.750", "KWD", null))

            // Signed legs roundtrip exactly, negative deltas included.
            assertThat(dao.listLegsForEvent("rt-sar").associate { it.legRef to it.delta })
                .isEqualTo(mapOf("leg-out" to "-200.00", "leg-in" to "12.50"))
            assertThat(dao.listLegsForEvent("rt-kwd").single().delta).isEqualTo("-15.750")

            // Queryable accepted columns: currency, kind, date range. The
            // null-date event is never pulled into a date range.
            assertThat(dao.listEventsByCurrency("SAR").single().eventId).isEqualTo("rt-sar")
            assertThat(dao.listEventsByKind("PURCHASE")).hasSize(4)
            assertThat(dao.listEventsByDateRange("2026-01-01", "2026-12-31").map { it.eventId })
                .containsExactly("rt-sar", "rt-jpy", "rt-kwd")
                .inOrder()

            // Fresh databases seed the revision singleton at 0; increment is
            // UPDATE-only metadata, never a financial balance.
            assertThat(dao.getRevision(CanonicalLedgerSchema.REVISION_SINGLETON_ID)!!.revision).isEqualTo(0L)
            assertThat(dao.incrementRevision(CanonicalLedgerSchema.REVISION_SINGLETON_ID)).isEqualTo(1)
            assertThat(dao.getRevision(CanonicalLedgerSchema.REVISION_SINGLETON_ID)!!.revision).isEqualTo(1L)
        } finally {
            database.close()
        }
    }

    private fun event(
        eventId: String,
        amount: String,
        currency: String,
        eventDate: String?,
    ) = CanonicalLedgerEventEntity(
        eventId = eventId,
        economicIdentityIssuer = null,
        economicIdentityReference = null,
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
        eventDate = eventDate,
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
