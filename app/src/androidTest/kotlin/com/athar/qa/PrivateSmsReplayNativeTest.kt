package com.athar.qa

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.SmsParseStatus
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.TransactionRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * WM028 bounded opt-in private-SMS replay harness — REAL graph, REAL pipeline, REAL
 * encrypted backup; no mocks, no fake repositories, no direct DB access.
 *
 * Exact contract (docs/specs/WM028-private-replay-native-harness.md):
 *   adb shell am instrument \
 *     -e atharQa.optIn wealth-028-private-replay \
 *     -e atharQa.stage replay            # or "restore"
 *     -e atharQa.inputFile  <absolute files/qa_wealth_028/input-HASH.ndjson path>
 *     -e atharQa.inputSha256 <exact 64-hex>
 *     [-e atharQa.keepForWalkthrough true]   # replay only
 *     -e class com.athar.qa.PrivateSmsReplayNativeTest \
 *     -r -w <compiled-test-package>/androidx.test.runner.AndroidJUnitRunner
 *
 * Preflight refuses — before any financial write — on: missing/wrong opt-in token, non-emulator
 * Build, wrong target package, unsafe input path, oversize input, hash mismatch.
 * Only `directionOriginal == "Received"` rows are ever ingested; sender/body/UTC/QA
 * sourceId are preserved exactly via RawIngestEvent(rawId=sourceId). Only aggregate
 * counts are reported — never row content, IDs, money, or exception causes.
 */
@RunWith(AndroidJUnit4::class)
class PrivateSmsReplayNativeTest {

    private companion object {
        const val OPT_IN = "wealth-028-private-replay"
        const val TARGET_PACKAGE = "com.athar.personal.debug"
        const val QA_DIR = "qa_wealth_028"
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        const val MAX_RECORDS = 10_000
        const val INPUT_PREFIX = "input-"
    }

    @Test
    fun privateReplayHarnessStageContract() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "atharQa.* args absent — harness not engaged (skip is not data coverage)",
            args.keySet().any { it.startsWith("atharQa.") },
        )
        dispatch(args)
    }

    /** Non-financial diagnostic: never refreshes, mutates, exports or reads a passphrase. */
    @Test
    fun widgetProjectionDiagnosticContract() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("atharQa.optIn") == OPT_IN)
        check(emulatorEvidence())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == TARGET_PACKAGE)
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, LocalQaEntryPoint::class.java)
        val backup = entry.backupRepository()
        val field = backup.javaClass.declaredFields.single {
            com.athar.core.domain.repo.WidgetRefresher::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val refresher = field.get(backup) as com.athar.core.domain.repo.WidgetRefresher
        val contextField = refresher.javaClass.declaredFields.single {
            Context::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val widgetContext = contextField.get(refresher) as Context
        val manager = android.appwidget.AppWidgetManager.getInstance(widgetContext)
        val monthIds = manager.getAppWidgetIds(android.content.ComponentName(TARGET_PACKAGE,
            "com.athar.feature.widgets.monthly.MonthlyWidgetReceiver"))
        val todayIds = manager.getAppWidgetIds(android.content.ComponentName(TARGET_PACKAGE,
            "com.athar.feature.widgets.today.TodayWidgetReceiver"))
        val snapshot = com.athar.feature.widgets.WidgetDataLoader.loadMonthlySnapshot(widgetContext)
        val work = androidx.work.WorkManager.getInstance(widgetContext)
            .getWorkInfosByTag("androidx.glance.session.SessionWorker").get()
        val report = JSONObject().put("backupImplementation", backup.javaClass.name)
            .put("refresherImplementation", refresher.javaClass.name)
            .put("targetPackage", ctx.packageName).put("widgetContextPackage", widgetContext.packageName)
            .put("applicationClass", ctx.applicationContext.javaClass.name)
            .put("androidMonthIds", monthIds.joinToString(",")).put("androidTodayIds", todayIds.joinToString(","))
            .put("yearMonth", snapshot.yearMonth).put("currency", snapshot.netFlow.currency)
            .put("matchesOriginalStressNet", snapshot.netFlow.amount.compareTo(java.math.BigDecimal("-100000001.01")) == 0)
            .put("matchesOriginalPosition", snapshot.declaredPosition?.net?.amount?.compareTo(java.math.BigDecimal("-1000.02")) == 0)
            .put("sameWidgetClassLoader", refresher.javaClass.classLoader == com.athar.feature.widgets.monthly.MonthlyWidget::class.java.classLoader)
            .put("workerStates", work.joinToString(",") { it.state.name })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("atharQa.widgetDiagnostic", report.toString())
        })
    }

    /** Parent-only setup probe: publishes current projections, never changes financial state. */
    @Test
    fun widgetPublishCurrent060Contract() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("atharQa.optIn") == OPT_IN)
        check(emulatorEvidence())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == TARGET_PACKAGE)
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, LocalQaEntryPoint::class.java)
        val backup = entry.backupRepository()
        val field = backup.javaClass.declaredFields.single {
            com.athar.core.domain.repo.WidgetRefresher::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val refresher = field.get(backup) as com.athar.core.domain.repo.WidgetRefresher
        refresher.refreshNow()
        val report = requireNotNull(com.athar.feature.widgets.WidgetPublicationTelemetry.lastReport.value)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("atharQa.publication060", report.toString())
        })
        val manager = android.appwidget.AppWidgetManager.getInstance(ctx)
        fun expected(receiver: String) = manager.getAppWidgetIds(
            android.content.ComponentName(TARGET_PACKAGE, "com.athar.feature.widgets.$receiver"),
        ).toSet().also { check(it.isNotEmpty()) }
        val monthly = report.monthly as? com.athar.feature.widgets.WidgetPublicationOutcome.FullyPublished
        val today = report.today as? com.athar.feature.widgets.WidgetPublicationOutcome.FullyPublished
        check(monthly?.appWidgetIds?.toSet() == expected("monthly.MonthlyWidgetReceiver"))
        check(today?.appWidgetIds?.toSet() == expected("today.TodayWidgetReceiver"))
        check(report.pending == com.athar.feature.widgets.WidgetPublicationOutcome.DeferredByPolicy)
    }

    /** Read-only, source-bound check of the kept replay; never posts or repairs money. */
    @Test
    fun cardRepayment064ReadbackContract() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("atharQa.optIn") == OPT_IN)
        check(emulatorEvidence())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == TARGET_PACKAGE)
        val hash = requireValidHashArg(args.getString("atharQa.repaymentSourcesSha256"))
        val sandbox = File(ctx.filesDir, QA_DIR).canonicalFile
        check(File(sandbox, "baseline.athar").isFile && File(sandbox, "baseline.passphrase").isFile)
        val input = File(sandbox, "repayments-$hash.tsv").canonicalFile
        check(input.parentFile == sandbox && input.isFile && input.length() in 1L..65_536L)
        check(sha256Hex(input) == hash)
        val rows = input.readLines(Charsets.UTF_8).map { it.split('\t') }
        check(rows.size == 80 && rows.all {
            it.size == 2 && it[0].isNotBlank() && it[0].length <= 256 && it[1].matches(Regex("[0-9a-f]{64}"))
        })
        val expected = rows.associate { it[0] to it[1] }
        check(expected.size == rows.size)
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, LocalQaEntryPoint::class.java)
        val actual = entry.transactionRepository().observeAll().first().filter { it.sourceRefId in expected }
        check(actual.size == expected.size && actual.map { it.sourceRefId }.distinct().size == actual.size)
        for (tx in actual) {
            check(tx.source == IngestSource.SMS && tx.type == com.athar.core.domain.model.TxType.TRANSFER &&
                tx.status == com.athar.core.domain.model.TxStatus.CONFIRMED)
            val literal = tx.amount.amount.stripTrailingZeros().toPlainString() + "|" + tx.amount.currency
            val digest = MessageDigest.getInstance("SHA-256").digest(literal.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            check(digest == expected[tx.sourceRefId]) { "repayment source money differs" }
        }
        emit(JSONObject().put("runId", UUID.randomUUID().toString()).put("stage", "card-repayment-readback")
            .put("targetPackage", TARGET_PACKAGE).put("expected", expected.size).put("checked", actual.size)
            .put("confirmedTransfers", actual.size).put("expenseOrIncomeRows", 0).put("financialWrites", false), sandbox)
    }

    private suspend fun dispatch(args: Bundle) {
        check(args.getString("atharQa.optIn") == OPT_IN) {
            "preflight rejected: opt-in token missing or wrong" }
        check(emulatorEvidence()) { "preflight rejected: target build lacks emulator evidence" }
        val appContext: Context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        check(appContext.packageName == TARGET_PACKAGE) {
            "preflight rejected: target package mismatch" }
        val entry = EntryPointAccessors.fromApplication(appContext, LocalQaEntryPoint::class.java)
        when (args.getString("atharQa.stage")) {
            "replay" -> Replay(entry, appContext, args).run()
            "restore" -> Restore(entry, appContext, args).run()
            else -> error("preflight rejected: stage must be 'replay' or 'restore'")
        }
    }

    // -- stage: replay ------------------------------------------------------------

    private inner class Replay(
        entry: LocalQaEntryPoint,
        private val ctx: Context,
        private val args: Bundle,
    ) {
        private val pipeline = entry.smsIngestionPipeline()
        private val rollout = entry.financialRecordingRollout()
        private val transactions = entry.transactionRepository()
        private val audit = entry.smsAuditRepository()
        private val backup = entry.backupRepository()
        private val sandbox get() = File(ctx.filesDir, QA_DIR)
        private var passphrase: CharArray? = null

        suspend fun run() {
            check(!(baseline().exists() || passphraseFile().exists())) {
                "preflight rejected: stale baseline artifacts present; run stage=restore first" }
            val input = requireSafeInput(ctx, args.getString("atharQa.inputFile"))
            val expected = requireValidHashArg(args.getString("atharQa.inputSha256"))
            val actual = sha256Hex(input)
            check(actual == expected) { "preflight rejected: input SHA-256 mismatch" }
            val rows = parseArchive(input)
            check(rows.failed == 0 && rows.duplicates == 0) { "preflight rejected: invalid or duplicate archive rows" }
            check(rows.received.isNotEmpty()) { "preflight rejected: no received messages" }
            val oracleHash = args.getString("atharQa.oracleSha256")?.let(::requireValidHashArg)
            val sourceOracle = oracleHash?.let { readSourceOracle(it) }.orEmpty()
            check(rows.received.map { it.sourceId }.toSet().containsAll(sourceOracle.keys)) {
                "source oracle references unavailable input"
            }
            val keep = args.getString("atharQa.keepForWalkthrough") == "true"
            val certifiedPurchases = args.getString("atharQa.certifiedPurchases") == "true"
            val previousRecordingEnabled = rollout.isEnabled()
            sandbox.mkdirs()
            val runId = UUID.randomUUID().toString()
            val beforeRows = transactions.observeAll().first()
            val txBefore = beforeRows.size
            val beforeIds = beforeRows.map { it.id }.toSet()
            val auditBeforeIds = audit.observeAll().first().map { it.id }.toSet()
            var restored = false
            var successfulKeep = false
            var replayedRows = 0
            var orphanParsedLinks = -1
            var transactionStatuses = emptyMap<String, Int>()
            var uncategorized = -1
            var pendingDescriptorRows = 0
            var confirmedPendingDescriptorRows = 0
            var txAfterRestore = -1
            var failedFirst = -1
            var failedReplay = -1
            var txAfterIngest = -1
            var txAfterReplay = -1
            var oracleChecked = 0
            var oracleFailures = 0
            var moneyOracleChecked = 0
            var noticeOracleChecked = 0
            var newAuditRows = -1
            var newStatuses: Map<SmsParseStatus, Int> = emptyMap()
            try {
                val pass = randomPassphrase()
                baseline().outputStream().use { backup.export(it, pass) }
                passphraseFile().writeText(String(pass))
                if (certifiedPurchases) rollout.setEnabled(true)
                failedFirst = ingest(rows)
                val afterRows = transactions.observeAll().first()
                txAfterIngest = afterRows.size
                val sourceRows = afterRows.filter { it.id !in beforeIds }.associateBy { it.sourceRefId }
                for ((ref, expectedValue) in sourceOracle) {
                    oracleChecked++
                    val actualRow = sourceRows[ref]
                    if (expectedValue[1] == "IGNORED") {
                        noticeOracleChecked++
                        if (actualRow != null) oracleFailures++
                    } else {
                        moneyOracleChecked++
                        val value = actualRow?.amount?.let { it.amount.stripTrailingZeros().toPlainString() + "|" + it.currency }
                        val digest = value?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                            .joinToString("") { byte -> "%02x".format(byte) } }
                        if (digest != expectedValue[3]) oracleFailures++
                    }
                }
                val sourceRefs = afterRows.filter { it.id !in beforeIds }.mapNotNull { it.sourceRefId }.toSet()
                val replay = Archive().also { subset ->
                    subset.received += rows.received.filter { it.sourceId in sourceRefs }.take(64)
                }
                replayedRows = replay.received.size
                failedReplay = coroutineScope {
                    List(2) { async(Dispatchers.IO) { ingest(replay) } }.awaitAll().sum()
                }
                transactionStatuses = afterRows.filter { it.id !in beforeIds }.groupingBy { it.status.name }.eachCount()
                uncategorized = afterRows.count { it.id !in beforeIds && it.categoryId == null }
                val descriptor = Regex("""\*\s*PEND(?:ING)?\b""", RegexOption.IGNORE_CASE)
                val heldRows = afterRows.filter { it.id !in beforeIds && descriptor.containsMatchIn(it.merchant) }
                pendingDescriptorRows = heldRows.size
                confirmedPendingDescriptorRows = heldRows.count { it.status.name == "CONFIRMED" }
                txAfterReplay = transactions.observeAll().first().size
                assertThat(txAfterReplay).isEqualTo(txAfterIngest)
                assertThat(txAfterIngest).isAtLeast(txBefore)
                assertThat(txAfterIngest).isAtMost(txBefore + rows.received.size)
                val auditAfter = audit.observeAll().first()
                newStatuses = auditAfter.filter { it.id !in auditBeforeIds }
                    .groupingBy { it.status }.eachCount()
                newAuditRows = auditAfter.size - auditBeforeIds.size
                val transactionIds = transactions.observeAll().first().map { it.id }.toSet()
                orphanParsedLinks = auditAfter.count {
                    it.id !in auditBeforeIds && it.status == SmsParseStatus.PARSED &&
                        (it.parsedTransactionId == null || it.parsedTransactionId !in transactionIds)
                }
                // Keep only a successful replay. Emit aggregate diagnostics before
                // asserting failures, so a failed real run remains inspectable after restore.
                successfulKeep = keep && failedFirst == 0 && failedReplay == 0 && orphanParsedLinks == 0 &&
                    (!certifiedPurchases || confirmedPendingDescriptorRows == 0) &&
                    oracleFailures == 0 && oracleChecked == sourceOracle.size
            } finally {
                rollout.setEnabled(previousRecordingEnabled)
                check(rollout.isEnabled() == previousRecordingEnabled) { "recording flag not restored" }
                if (!successfulKeep && baseline().exists()) {
                    backup.import(baseline().readBytes(), requireNotNull(passphrase))
                    restored = true
                    txAfterRestore = transactions.observeAll().first().size
                    check(txAfterRestore == txBefore) { "restored transaction count differs from baseline" }
                    baseline().delete(); passphraseFile().delete()
                }
                passphrase?.fill(0.toChar())
            }
            check(successfulKeep || restored) { "baseline restore did not run" }
            run {
                val report = baseReport(runId, input.length(), actual, rows, successfulKeep)
                    .put("ingest", JSONObject()
                        .put("attempted", rows.received.size)
                        .put("ingestFailedFirstPass", failedFirst)
                        .put("ingestFailedReplayPass", failedReplay)
                        .put("newAuditRows", newAuditRows)
                        .put("newAuditStatusCounts", statusJson(newStatuses))
                        .put("replayedSourceBoundRows", replayedRows)
                        .put("replayConcurrentCopies", 2)
                        .put("replayAttemptedRows", replayedRows * 2)
                        .put("orphanParsedTransactionLinks", orphanParsedLinks))
                    .put("certifiedPurchasesRequested", certifiedPurchases)
                    .put("recordingFlagRestored", rollout.isEnabled() == previousRecordingEnabled)
                    .put("newTransactionStatusCounts", JSONObject(transactionStatuses))
                    .put("newUncategorizedTransactionRows", uncategorized)
                    .put("newPendingDescriptorRows", pendingDescriptorRows)
                    .put("newConfirmedPendingDescriptorRows", confirmedPendingDescriptorRows)
                    .put("sourceOracle", JSONObject().put("sha256", oracleHash)
                        .put("expected", sourceOracle.size).put("checked", oracleChecked)
                        .put("moneyChecked", moneyOracleChecked).put("noticeChecked", noticeOracleChecked)
                        .put("failures", oracleFailures))
                    .put("transactionRows", JSONObject()
                        .put("before", txBefore).put("afterIngest", txAfterIngest)
                        .put("afterReplayPass", txAfterReplay)
                        .put("afterRestore", txAfterRestore))
                emit(report, sandbox)
            }
            check(failedFirst == 0 && failedReplay == 0) { "production ingestion raised an exception" }
            check(orphanParsedLinks == 0) { "parsed audit rows have missing live transaction links" }
            check(oracleFailures == 0 && oracleChecked == sourceOracle.size) { "source-bound financial oracle failed" }
            check(!certifiedPurchases || confirmedPendingDescriptorRows == 0) { "pending descriptor acquired posting authority" }
        }

        private fun baseReport(
            runId: String,
            inputBytes: Long,
            hash: String,
            rows: Archive,
            keep: Boolean,
        ): JSONObject = JSONObject()
            .put("runId", runId).put("stage", "replay")
            .put("targetPackage", TARGET_PACKAGE).put("emulatorEvidence", true)
            .put("inputBytes", inputBytes).put("inputSha256", hash)
            .put("counts", JSONObject()
                .put("totalLines", rows.totalLines).put("read", rows.read)
                .put("received", rows.received.size).put("sentSkipped", rows.sent)
                .put("ignored", rows.ignored).put("failedRows", rows.failed)
                .put("receivedDuplicateSourceIdRows", rows.duplicates))
            .put("baseline", JSONObject().put("captured", true)
                .put("restored", !keep).put("keptForWalkthrough", keep))

        private fun randomPassphrase(): CharArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            return CharArray(48) { alphabet[rnd.nextInt(alphabet.length)] }
                .also { passphrase = it }
        }

        private suspend fun ingest(rows: Archive): Int {
            var failed = 0
            for (row in rows.received) {
                try {
                    pipeline.process(row.toEvent())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    failed++ // counted only; never row content, never a cause
                }
            }
            return failed
        }

        private fun readSourceOracle(hash: String): Map<String, List<String>> {
            val file = File(sandbox, "oracle-$hash.tsv").canonicalFile
            check(file.parentFile == sandbox.canonicalFile && file.isFile && file.length() in 1L..1_048_576L) {
                "source oracle outside owned bounds"
            }
            check(sha256Hex(file) == hash) { "source oracle hash mismatch" }
            val values = file.readLines(Charsets.UTF_8).map { it.split('\t') }
            check(values.size in 1..MAX_RECORDS && values.all {
                it.size == 4 && it[0].isNotBlank() && it[0].length <= 256 &&
                    it[1] in setOf("IGNORED", "MONEY_SHA256") &&
                    (it[1] == "IGNORED" || it[3].matches(Regex("[0-9a-f]{64}")))
            }) { "source oracle shape invalid" }
            check(values.map { it[0] }.distinct().size == values.size) { "duplicate oracle reference" }
            return values.associateBy { it[0] }
        }

        private fun baseline() = File(sandbox, "baseline.athar")
        private fun passphraseFile() = File(sandbox, "baseline.passphrase")
    }

    // -- stage: restore -----------------------------------------------------------

    private inner class Restore(
        entry: LocalQaEntryPoint,
        private val ctx: Context,
        private val args: Bundle,
    ) {
        private val transactions = entry.transactionRepository()
        private val audit = entry.smsAuditRepository()
        private val backup = entry.backupRepository()
        private val sandbox get() = File(ctx.filesDir, QA_DIR)

        suspend fun run() {
            val baseline = File(sandbox, "baseline.athar")
            val passFile = File(sandbox, "baseline.passphrase")
            val hasBaseline = baseline.exists() && passFile.exists()
            check(baseline.exists() == passFile.exists()) {
                "restore refused: incomplete recovery artifacts preserved"
            }
            val report: JSONObject
            if (hasBaseline) {
                val txBefore = transactions.observeAll().first().size
                val auditBefore = audit.observeAll().first().size
                val pass = passFile.readText().toCharArray()
                try {
                    backup.import(baseline.readBytes(), pass)
                } finally {
                    pass.fill(0.toChar())
                }
                val txAfter = transactions.observeAll().first().size
                val auditDelta = audit.observeAll().first().size - auditBefore
                report = JSONObject()
                    .put("runId", UUID.randomUUID().toString()).put("stage", "restore")
                    .put("targetPackage", TARGET_PACKAGE).put("emulatorEvidence", true)
                    .put("baselinePresent", true).put("restored", true)
                    .put("transactionRows", JSONObject()
                        .put("beforeRestore", txBefore).put("afterRestore", txAfter))
                    .put("auditRowDelta", auditDelta)
            } else {
                // A default replay already restored its baseline; this invocation is pure
                // final cleanup. restored=false means "nothing left to roll back", not failure.
                report = JSONObject()
                    .put("runId", UUID.randomUUID().toString()).put("stage", "restore")
                    .put("targetPackage", TARGET_PACKAGE).put("emulatorEvidence", true)
                    .put("baselinePresent", false).put("restored", false)
            }
            if (hasBaseline && args.getString("atharQa.awaitWidgetPublication") == "true") {
                try {
                    report.put("widgetPublication", WidgetPublicationAwait.awaitCurrent(ctx))
                } catch (failure: Exception) {
                    emit(report.put("widgetPublication", "FAILED_AFTER_FINANCIAL_RESTORE"), sandbox)
                    throw failure
                }
            }
            emit(report, sandbox)
            // Final cleanup: sandbox artifacts + reports + input file. The aggregate JSON
            // was printed first; nothing payload-bearing survives.
            println("atharQa cleanup: ${finalCleanup(ctx)}")
        }
    }

    // -- shared helpers -------------------------------------------------------------

    private fun emulatorEvidence(): Boolean {
        val fp = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        val hw = Build.HARDWARE.lowercase(Locale.US)
        val mfr = Build.MANUFACTURER.lowercase(Locale.US)
        return fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
            model.contains("sdk") || model.contains("emulator") ||
            product.contains("sdk") || product.contains("emulator") ||
            hw.contains("goldfish") || hw.contains("ranchu") || mfr == "genymotion"
    }

    private fun requireSafeInput(ctx: Context, raw: String?): File {
        check(!raw.isNullOrBlank() && File(raw).isAbsolute) {
            "preflight rejected: inputFile missing or relative"
        }
        val f = File(raw).canonicalFile
        val owned = File(ctx.filesDir, QA_DIR).canonicalFile
        check(f.parentFile == owned && f.name.startsWith(INPUT_PREFIX) && f.name.endsWith(".ndjson")) {
            "preflight rejected: inputFile outside the exact owned app-private sandbox"
        }
        check(f.isFile && f.length() in 1..MAX_INPUT_BYTES) {
            "preflight rejected: input file missing or outside 1..32MiB bounds"
        }
        return f
    }

    private fun requireValidHashArg(raw: String?): String {
        check(raw != null && Regex("[0-9a-fA-F]{64}").matches(raw)) {
            "preflight rejected: inputSha256 must be exactly 64 hex chars" }
        return raw.lowercase(Locale.US)
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseArchive(file: File): Archive {
        val rows = Archive()
        file.useLines(Charsets.UTF_8) { lines ->
            for (line in lines) {
                rows.totalLines++
                check(rows.totalLines <= MAX_RECORDS) { "preflight rejected: archive record bound exceeded" }
                if (line.isBlank()) continue
                val obj = runCatching { JSONObject(line) }.getOrNull()
                if (obj == null) { rows.failed++; continue }
                fun field(k: String): String? =
                    obj.opt(k) as? String
                val sourceId = field("sourceId")
                val sender = field("sender")
                val body = field("body")
                val direction = field("directionOriginal")
                val utc = field("receivedAtUtc")
                if (sourceId.isNullOrBlank() || sender.isNullOrBlank() || body.isNullOrBlank() ||
                    direction == null || utc == null
                ) { rows.failed++; continue }
                val at = runCatching { Instant.parse(utc) }.getOrNull()
                if (at == null) { rows.failed++; continue }
                rows.read++
                when (direction) {
                    "Received" -> {
                        if (!rows.seenIds.add(sourceId)) rows.duplicates++
                        rows.received += ArchiveRow(sourceId, at, sender, body)
                    }
                    "Sent" -> rows.sent++
                    else -> rows.ignored++
                }
            }
        }
        return rows
    }

    private fun statusJson(counts: Map<SmsParseStatus, Int>): JSONObject {
        val o = JSONObject()
        SmsParseStatus.entries.forEach { o.put(it.name, counts[it] ?: 0) }
        return o
    }

    private fun emit(report: JSONObject, sandbox: File) {
        println("atharQa report: $report")
        sandbox.mkdirs()
        File(sandbox, "report-${report.optString("stage")}-${report.optString("runId")}.json")
            .writeText(report.toString(2))
    }

    private fun finalCleanup(ctx: Context): String {
        val notes = ArrayList<String>()
        val sandbox = File(ctx.filesDir, QA_DIR)
        if (sandbox.exists() && sandbox.deleteRecursively()) notes.add("sandbox")
        return if (notes.isEmpty()) "nothing-to-delete" else notes.joinToString("+")
    }
}

private class Archive {
    var totalLines = 0
    var read = 0
    var sent = 0
    var ignored = 0
    var failed = 0
    var duplicates = 0
    val seenIds = HashSet<String>()
    val received = ArrayList<ArchiveRow>()
}

private class ArchiveRow(val sourceId: String, val receivedAt: Instant, val sender: String, val body: String) {
    fun toEvent() = RawIngestEvent(
        id = sourceId,
        source = IngestSource.SMS,
        sender = sender,
        body = body,
        receivedAt = receivedAt,
        rawId = sourceId,
    )
}
