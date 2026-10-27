package com.athar.core.data.ledger.admission

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.common.money.Money
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.CategoryAssignment
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

/** Synthetic, isolated production-Room fixture; never opens an application database. */
internal class AdmissionNativeDb(val database: AtharDatabase) {
    val ledger = database.canonicalLedgerDao()
    val evidence = database.canonicalEvidenceDao()
    val repository = UnallocatedAdmissionRepository(database, ledger, evidence, Dispatchers.IO)
    val raw: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    suspend fun admit(
        graph: FinancialEventGraph = nativeGraph(),
        bytes: ByteArray = nativeBytes(),
        ref: String = NATIVE_EVIDENCE,
    ) = repository.admit(UnallocatedAdmissionRequest(graph, bytes, ref))

    fun rows(table: String): List<List<String?>> = raw.query("SELECT * FROM `$table` ORDER BY 1, 2").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> "blob:${Base64.getEncoder().encodeToString(cursor.getBlob(column))}"
                    else -> "${cursor.getType(column)}:${cursor.getString(column)}"
                }
            })
        }
    }

    fun state(tables: List<String> = nativeTables) = tables.associateWith(::rows)
    fun financialState() = state(listOf(
        "account", "transactions", "canonical_ledger_event", "canonical_ledger_event_source",
        "canonical_ledger_component", "canonical_ledger_leg", "canonical_ledger_revision",
    ))

    fun seedLegacySentinel() {
        raw.execSQL("""
            INSERT INTO account (
                id,name,type,currency,openingBalanceMinor,openingBalanceCurrency,
                smsSenders,notes,sortOrder,active,archivedAt,createdAt,updatedAt
            ) VALUES ('native-cash','Synthetic cash','CASH','SAR',12345,'SAR','[]',NULL,0,1,NULL,1,1)
        """.trimIndent())
        raw.execSQL("""
            INSERT INTO transactions (
                id,accountId,type,amountMinor,currency,date,occurredAt,merchant,
                merchantNormalized,categoryId,notes,source,sourceRefId,status,
                confidence,categorySource,createdAt,updatedAt
            ) VALUES ('native-legacy','native-cash','EXPENSE',999,'SAR','2026-01-01',NULL,
                'Synthetic legacy','synthetic legacy',NULL,NULL,'MANUAL',NULL,'DISMISSED',NULL,NULL,1,1)
        """.trimIndent())
    }
}

internal const val NATIVE_NAMESPACE = "synthetic-admission"
internal const val NATIVE_EVIDENCE = "synthetic:original:evidence"
internal val nativeTables = listOf(
    "account", "transactions", "canonical_ledger_event", "canonical_ledger_source_observation",
    "canonical_ledger_event_source", "canonical_ledger_component", "canonical_ledger_leg",
    "canonical_ledger_revision", "canonical_ledger_observation_snapshot", "canonical_ledger_conflict",
)
internal fun nativeBytes() = byteArrayOf(0, 13, 10, 127, -128, -1)

internal suspend fun withAdmissionNativeDb(name: String, block: suspend AdmissionNativeDb.() -> Unit) {
    require(name.matches(Regex("[a-z0-9-]+")))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val databaseName = "athar-wm028-b3-parent-$name"
    context.deleteDatabase(databaseName)
    val database = atharDatabaseBuilder(context, databaseName).build()
    try {
        AdmissionNativeDb(database).block()
    } finally {
        database.close()
    }
}

internal fun nativeGraph(
    sourceId: String = "source-a",
    eventId: String = "event-a",
    amount: String = "200.00",
    currency: String = "SAR",
    category: CategoryAssignment? = null,
    identity: EconomicIdentity? = null,
    feeEvidence: String? = null,
    date: LocalDate? = null,
): FinancialEventGraph {
    val money = Money.of(amount, currency)
    val principal = if (feeEvidence == null) money else
        Money.of(money.amount.subtract(BigDecimal.ONE).toPlainString(), currency)
    val components = buildList {
        add(ComponentSpec("principal", ComponentType.PRINCIPAL, principal,
            support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "synthetic:principal:amount"))
        if (feeEvidence != null) add(ComponentSpec("fee", ComponentType.FEE, Money.of("1.00", currency),
            FeeRelation.INCLUDED, feeEvidence, AmountSupport.EXPLICIT_TRANSACTION, "synthetic:fee:amount"))
    }
    val observation = FinancialObservation(
        stableId = StableObservationId(sourceId),
        posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        economicKind = EconomicKind.PURCHASE,
        direction = MovementDirection.OUT,
        original = SupportedMoney(money, AmountSupport.EXPLICIT_BOOKED),
        settlement = null,
        routing = RoutingCoverage.Unresolved,
        eventDate = date,
        legacy = null,
        userDeclaration = null,
    )
    val result = FinancialGraphFactory.build(FinancialGraphDraft(
        eventId = eventId, observation = observation, sourceNamespace = NATIVE_NAMESPACE,
        economicIdentity = identity, components = components, category = category,
    ))
    assertThat(result).isInstanceOf(FinancialGraphOutcome.Created::class.java)
    return (result as FinancialGraphOutcome.Created).graph
}

internal fun nativePrepared(graph: FinancialEventGraph, bytes: ByteArray = nativeBytes()): PreparedAdmission {
    val result = AdmissionPreparation.prepare(graph, bytes, NATIVE_EVIDENCE)
    assertThat(result).isInstanceOf(AdmissionPreparation.Preparation.Ready::class.java)
    return (result as AdmissionPreparation.Preparation.Ready).prepared
}

internal fun requireConflict(result: UnallocatedAdmissionOutcome, reason: AdmissionConflictReason): AdmissionConflictRecord {
    assertThat(result).isInstanceOf(UnallocatedAdmissionOutcome.Conflicted::class.java)
    return (result as UnallocatedAdmissionOutcome.Conflicted).conflict.also { assertThat(it.reason).isEqualTo(reason) }
}
