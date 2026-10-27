package com.athar.ingestion

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestDispatcher
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.repo.SmsImportPreferencesRepository
import com.athar.core.domain.smsimport.SmsImportFilterGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-blocking receiver entry point with a single ingestion worker.
 *
 * Live SMS (BroadcastReceiver) is gated by [SmsImportFilterSettings]: no selected
 * senders and/or live tracking disabled means the event is dropped before parse.
 * Historical import / explicit backfill enqueue the same events but those callers
 * already applied their own filters (or are legacy paths).
 */
@Singleton
class QueuedRawIngestDispatcher private constructor(
    private val pipeline: SmsIngestionPipeline,
    private val importPrefs: SmsImportPreferencesRepository?,
    private val scope: CoroutineScope,
    capacity: Int,
) : RawIngestDispatcher {

    @Inject
    constructor(
        pipeline: SmsIngestionPipeline,
        importPrefs: SmsImportPreferencesRepository,
    ) : this(
        pipeline = pipeline,
        importPrefs = importPrefs,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        capacity = Channel.UNLIMITED,
    )

    internal constructor(
        pipeline: SmsIngestionPipeline,
        scope: CoroutineScope,
        importPrefs: SmsImportPreferencesRepository? = null,
    ) : this(
        pipeline = pipeline,
        importPrefs = importPrefs,
        scope = scope,
        capacity = Channel.UNLIMITED,
    )

    private val events = Channel<RawIngestEvent>(capacity = capacity)

    init {
        scope.launch {
            for (event in events) {
                processSafely(event)
            }
        }
    }

    override fun enqueue(event: RawIngestEvent) {
        val result = events.trySend(event)
        if (result.isFailure) {
            Timber.e(
                result.exceptionOrNull(),
                "[ingest-queue] failed to enqueue rawId=%s sender=%s",
                event.rawId,
                event.sender,
            )
        }
    }

    private suspend fun processSafely(event: RawIngestEvent) {
        try {
            if (event.source == IngestSource.SMS && !allowsLiveSms(event)) {
                Timber.d(
                    "[ingest-queue] skipped live SMS sender=%s (import filters)",
                    event.sender,
                )
                return
            }
            pipeline.process(event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Timber.e(error, "[ingest-queue] failed rawId=%s sender=%s", event.rawId, event.sender)
        }
    }

    private suspend fun allowsLiveSms(event: RawIngestEvent): Boolean {
        val prefs = importPrefs ?: return true
        // Historical imports reuse rawId prefix inbox- / explicit import paths still
        // go through the dispatcher; only BroadcastReceiver live traffic must require
        // liveTrackingEnabled. Inbox imports set rawId = "inbox-$id".
        val isHistoricalImport = event.rawId?.startsWith("inbox-") == true
        val settings = prefs.settings().first()
        return SmsImportFilterGate.allows(
            sender = event.sender,
            receivedAt = event.receivedAt,
            settings = settings,
            zone = TimeZone.currentSystemDefault(),
            requireLiveEnabled = !isHistoricalImport,
        )
    }
}
