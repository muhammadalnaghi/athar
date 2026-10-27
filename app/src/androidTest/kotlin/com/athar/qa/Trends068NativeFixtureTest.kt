package com.athar.qa

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.common.money.Money
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Parent-owned synthetic native fixture; never run without explicit owned-emulator opt-in. */
@RunWith(AndroidJUnit4::class)
class Trends068NativeFixtureTest {
    @Test
    fun guardedFixtureStage() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("atharQa068.optIn") == "native-trends-068")
        check(Build.FINGERPRINT.contains("sdk_gphone") || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "com.athar.personal.debug")
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, LocalQaEntryPoint::class.java)
        check(!entry.financialRecordingRollout().isEnabled())
        val pipeline = entry.smsIngestionPipeline()
        val accounts = dependency(pipeline, AccountRepository::class.java)
        val resolverField = pipeline.javaClass.getDeclaredField("resolver").apply { isAccessible = true }
        val categories = dependency(requireNotNull(resolverField.get(pipeline)), CategoryRepository::class.java)
        val txs = entry.transactionRepository()
        val backup = entry.backupRepository()
        val dir = File(ctx.filesDir, "qa_wealth_068")
        val baseline = File(dir, "baseline.athar")
        val secret = File(dir, "baseline.passphrase")
        val metadata = File(dir, "before.json")
        val restored = File(dir, "restored.marker")
        suspend fun fingerprints() = JSONObject()
            .put("transactions", digest(txs.observeAll().first().sortedBy { it.id }.toString()))
            .put("accounts", digest(accounts.observeAll().first().sortedBy { it.id }.toString()))
            .put("categories", digest(categories.observeAll(includeArchived = true).first().sortedBy { it.id }.toString()))
            .put("transactionCount", txs.observeAll().first().size)
        when (args.getString("atharQa068.stage")) {
            "seed" -> {
                check(!dir.exists()) { "Existing fixture/recovery state must be reconciled, never reseeded" }
                val before = txs.observeAll().first()
                check(before.isNotEmpty() && before.none { it.id.startsWith("qa068-") })
                val beforeFingerprints = fingerprints()
                dir.mkdirs()
                val pass = CharArray(48) { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[SecureRandom().nextInt(62)] }
                try {
                    baseline.outputStream().use { backup.export(it, pass) }
                    check(baseline.length() > 0)
                    secret.writeText(String(pass))
                    metadata.writeText(beforeFingerprints.toString())
                } finally { pass.fill('\u0000') }
                val now = Clock.System.now()
                val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                val prior = LocalDate(today.year, today.monthNumber, 1).minus(DatePeriod(months = 1))
                for ((id, currency) in listOf("a" to "SAR", "b" to "SAR", "usd" to "USD")) {
                    accounts.upsert(Account("qa068-account-$id", "QA068 Account ${id.uppercase()}", AccountType.CASH,
                        currency, Money.zero(currency), emptyList(), null, 900, false, now, now))
                }
                for (i in 1..10) {
                    val suffix = i.toString().padStart(2, '0')
                    categories.upsert(Category("qa068-cat-$suffix", "QA068 Category $suffix", "تصنيف $suffix",
                        CategoryKind.EXPENSE, null, null, false, 900 + i))
                }
                suspend fun add(id: String, amount: String, category: String? = null,
                    type: TxType = TxType.EXPENSE, source: IngestSource = IngestSource.MANUAL,
                    status: TxStatus = TxStatus.CONFIRMED, date: LocalDate = today,
                    account: String = "a", currency: String = "SAR", ref: String = "qa068-$id") {
                    txs.upsert(Transaction("qa068-$id", "qa068-account-$account", type, Money.of(amount, currency), date,
                        null, "QA068 $id", "qa068 $id", category, null, source, ref, status, null, now, now))
                }
                for (i in 1..10) {
                    val suffix = i.toString().padStart(2, '0')
                    add("expense-$suffix", "${110 - i * 10}.01", "qa068-cat-$suffix",
                        source = if (i % 2 == 1) IngestSource.SMS else IngestSource.MANUAL,
                        account = if (i % 2 == 1) "a" else "b")
                }
                add("uncategorized", "7.07", source = IngestSource.NOTIFICATION)
                add("income", "1000.01", type = TxType.INCOME, source = IngestSource.IMPORT)
                add("foreign", "17.25", currency = "USD", account = "usd")
                add("transfer", "50.00", type = TxType.TRANSFER)
                add("pending", "99.00", status = TxStatus.PENDING)
                add("dismissed", "88.00", status = TxStatus.DISMISSED)
                add("reconcile", "999.00", ref = "reconcile-qa068")
                add("future", "5.00", date = today.plus(DatePeriod(days = 1)))
                add("prior-other", "11.11", "qa068-cat-09", date = prior)
                add("prior-visible", "22.22", "qa068-cat-01", date = prior)
                add("prior-uncategorized", "13.13", date = prior)
                val after = txs.observeAll().first()
                check(after.size == before.size + 21 && after.filter { !it.id.startsWith("qa068-") }.sortedBy { it.id } == before.sortedBy { it.id })
                report(dir, JSONObject().put("stage", "seed").put("fixtureRows", 21)
                    .put("baselineRows", before.size).put("currentDate", today.toString()).put("priorDate", prior.toString()))
            }
            "restore" -> {
                check(baseline.isFile && secret.isFile && metadata.isFile && !restored.exists())
                val expected = JSONObject(metadata.readText())
                val pass = secret.readText().toCharArray()
                try { baseline.inputStream().use { backup.import(it, pass) } }
                finally { pass.fill('\u0000') }
                // A completed replacement is never repeated to repair a later readback/capture failure.
                restored.writeText("authenticated replacement completed")
                val actual = fingerprints()
                check(listOf("transactions", "accounts", "categories", "transactionCount").all { expected[it] == actual[it] })
                check(!entry.financialRecordingRollout().isEnabled())
                report(dir, JSONObject().put("stage", "restore").put("restored", true)
                    .put("baselineFingerprintsMatch", true).put("transactionRows", actual.getInt("transactionCount")))
                check(baseline.delete() && secret.delete())
            }
            else -> error("Explicit seed or restore stage required")
        }
    }

    private fun <T> dependency(owner: Any, type: Class<T>): T {
        val field = owner.javaClass.declaredFields.single { type.isAssignableFrom(it.type) }.apply { isAccessible = true }
        return type.cast(field.get(owner))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun report(dir: File, values: JSONObject) {
        values.put("runId", UUID.randomUUID().toString())
        File(dir, "report-${values.getString("stage")}.json").writeText(values.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("atharQa068.report", values.toString())
        })
    }
}
