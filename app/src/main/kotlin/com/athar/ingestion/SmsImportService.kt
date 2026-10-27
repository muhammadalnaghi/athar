package com.athar.ingestion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestDispatcher
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.repo.BackfillProgress
import com.athar.core.domain.repo.SmsImportController
import com.athar.core.domain.repo.SmsImportPreferencesRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.smsimport.DiscoveredSmsSender
import com.athar.core.domain.smsimport.SmsImportFilterGate
import com.athar.core.domain.smsimport.SmsImportPreview
import com.athar.core.domain.smsimport.SmsImportResult
import com.athar.core.domain.smsimport.SmsSenderIds
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SmsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discover / preview / import historical SMS using [SmsImportFilterSettings].
 * Reuses the same [RawIngestDispatcher] path as live SMS and backfill.
 */
@Singleton
class SmsImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SmsImportPreferencesRepository,
    private val dispatcher: RawIngestDispatcher,
    private val parser: SmsParser,
    private val transactions: TransactionRepository,
) : SmsImportController {

    private val _progress = MutableStateFlow<BackfillProgress>(BackfillProgress.Idle)
    override val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    override suspend fun discoverSenders(): List<DiscoveredSmsSender> {
        if (!hasReadSms()) return emptyList()
        val counts = linkedMapOf<String, Int>()
        val order = linkedMapOf<String, String>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            null,
            null,
            null,
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            while (c.moveToNext()) {
                val raw = c.getString(addrIdx).orEmpty()
                val exact = SmsSenderIds.preserve(raw) ?: continue
                val key = SmsSenderIds.normalize(exact)
                order.putIfAbsent(key, exact)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return order.map { (key, exact) ->
            DiscoveredSmsSender(address = exact, messageCount = counts[key] ?: 0)
        }.sortedByDescending { it.messageCount }
    }

    override suspend fun preview(): Result<SmsImportPreview> {
        if (!hasReadSms()) return Result.failure(IllegalStateException("SMS permission not granted"))
        val settings = prefs.settings().first()
        SmsImportFilterGate.validateHistoricalRange(settings)?.let { code ->
            return Result.failure(IllegalStateException(code))
        }
        var matching = 0
        var parseable = 0
        var duplicates = 0
        var skipped = 0
        // ponytail: one snapshot for duplicate counts; upsertIngest still enforces uniqueness.
        val existingRefs = existingSourceRefIds()
        forEachMatching(settings) { sender, body, receivedAt, rawId ->
            matching += 1
            val event = RawIngestEvent(
                id = "preview",
                source = IngestSource.SMS,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                rawId = rawId,
            )
            when (val result = parser.parse(event)) {
                is ParseResult.Success -> {
                    parseable += 1
                    if (rawId in existingRefs) duplicates += 1
                }
                ParseResult.Ignored, is ParseResult.Failed -> skipped += 1
            }
        }
        return Result.success(
            SmsImportPreview(
                matchingSmsCount = matching,
                parseableCount = parseable,
                duplicateCount = duplicates,
                skippedCount = skipped,
            ),
        )
    }

    override suspend fun importSelected(): SmsImportResult {
        if (!hasReadSms()) {
            val failed = SmsImportResult(failed = true, failureReason = "SMS permission not granted")
            _progress.value = BackfillProgress.Failed(failed.failureReason!!)
            return failed
        }
        val settings = prefs.settings().first()
        val validation = SmsImportFilterGate.validateHistoricalRange(settings)
        if (validation != null) {
            val failed = SmsImportResult(failed = true, failureReason = validation)
            _progress.value = BackfillProgress.Failed(validation)
            return failed
        }
        _progress.value = BackfillProgress.Running(scanned = 0)
        var scanned = 0
        var accepted = 0
        var parseable = 0
        var duplicates = 0
        var skipped = 0
        return runCatching {
            val existingRefs = existingSourceRefIds()
            forEachMatching(settings) { sender, body, receivedAt, rawId ->
                scanned += 1
                if (scanned % 25 == 0) _progress.value = BackfillProgress.Running(scanned)
                val event = RawIngestEvent(
                    id = UUID.randomUUID().toString(),
                    source = IngestSource.SMS,
                    sender = sender,
                    body = body,
                    receivedAt = receivedAt,
                    rawId = rawId,
                )
                when (val result = parser.parse(event)) {
                    is ParseResult.Success -> {
                        parseable += 1
                        if (rawId in existingRefs) duplicates += 1
                    }
                    ParseResult.Ignored, is ParseResult.Failed -> skipped += 1
                }
                dispatcher.enqueue(event)
                accepted += 1
            }
            _progress.value = BackfillProgress.Done(scanned = scanned, sentToPipeline = accepted)
            Timber.i("SMS import complete: scanned=%d accepted=%d", scanned, accepted)
            SmsImportResult(
                scanned = scanned,
                accepted = accepted,
                parseable = parseable,
                duplicates = duplicates,
                skipped = skipped,
            )
        }.getOrElse { e ->
            Timber.e(e, "SMS import failed")
            _progress.value = BackfillProgress.Failed(e.message ?: "import failed")
            SmsImportResult(failed = true, failureReason = e.message ?: "import failed")
        }
    }

    private inline fun forEachMatching(
        settings: com.athar.core.domain.smsimport.SmsImportFilterSettings,
        block: (sender: String, body: String, receivedAt: Instant, rawId: String) -> Unit,
    ) {
        val zone = TimeZone.currentSystemDefault()
        val startMs = SmsImportFilterGate.startEpochMillisInclusive(settings.startDate, zone)
        val endExclusiveMs = SmsImportFilterGate.endEpochMillisExclusive(settings, zone)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (startMs != null) {
            clauses += "${Telephony.Sms.DATE} >= ?"
            args += startMs.toString()
        }
        if (endExclusiveMs != null) {
            clauses += "${Telephony.Sms.DATE} < ?"
            args += endExclusiveMs.toString()
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection,
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val sender = c.getString(addrIdx).orEmpty()
                val body = c.getString(bodyIdx).orEmpty()
                val ms = c.getLong(dateIdx)
                val receivedAt = Instant.fromEpochMilliseconds(ms)
                if (sender.isBlank() || body.isBlank()) continue
                if (!SmsImportFilterGate.allows(sender, receivedAt, settings, zone)) continue
                block(sender, body, receivedAt, "inbox-$id")
            }
        }
    }

    private suspend fun existingSourceRefIds(): Set<String> =
        transactions.observeAll().first().mapNotNull { it.sourceRefId }.toHashSet()

    private fun hasReadSms(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
