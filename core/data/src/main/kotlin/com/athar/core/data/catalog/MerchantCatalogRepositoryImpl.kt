package com.athar.core.data.catalog

import com.athar.core.domain.model.CatalogRefreshPhase
import com.athar.core.domain.model.MerchantCatalogSnapshot
import com.athar.core.domain.model.MerchantCatalogState
import com.athar.core.domain.repo.MerchantCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single state machine for the opt-in signed shared merchant catalog.
 *
 * Safety properties (each covered by MerchantCatalogRepositoryImplTest):
 * - off by default; every refresh requires actual opt-in + network capability;
 * - automatic (non-force) refresh respects the 7-day staleness window and a
 *   >= 1-day backoff after any failure; manual force bypasses both gates but
 *   still requires opt-in;
 * - simultaneous refreshes coalesce into at most one transport call;
 * - opting out bumps a generation counter, cancels pending work by discarding
 *   any response whose generation has gone stale, and keeps the already
 *   verified library usable offline;
 * - version monotonicity: new > highest; identical version accepted only with
 *   the identical authenticated payload digest (re-cache + check-time update);
 *   lower or digest-divergent versions are rejected and the last-good state
 *   (cache, entries, personal rules) stays untouched;
 * - reset clears caches/entries and disables updates while preserving the
 *   durable highest floor + digest, so the identical verified catalog can be
 *   reacquired later; personal rules/history are never touched.
 */
@Singleton
internal class MerchantCatalogRepositoryImpl @Inject constructor(
    private val transport: CatalogTransport,
    private val cache: CatalogCacheStore,
    private val preferences: CatalogPreferencesStore,
    private val consentFence: CatalogConsentFence,
    private val envelopeParser: CatalogEnvelopeParser,
    private val networkSupported: Boolean,
    private val clock: Clock,
) : MerchantCatalogRepository {

    private val _state = MutableStateFlow(MerchantCatalogState(networkSupported = networkSupported))
    override val state: StateFlow<MerchantCatalogState> = _state.asStateFlow()

    private val initMutex = Mutex()
    private var initialized = false

    @Volatile
    private var activeCatalog: VerifiedCatalog? = null

    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    /** Shared consent/control lock for request registration, cancellation and fencing. */
    private val controlMutex = Mutex()

    /** Serializes bounded local cache/preferences/state commits; never spans network I/O. */
    private val commitMutex = Mutex()

    /** Prepared request currently owned by the repository, if any. */
    private var activeRequest: CatalogPreparedRequest? = null

    @Volatile
    private var activeEnvelope: ByteArray? = null

    @Volatile
    private var durableConsentVeto = false

    @Volatile
    private var durableConsentUncertain = false

    @Volatile
    private var sessionLastFailureEpochSecond: Long? = null

    private var catalogDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.Default

    /** Test seam; production's injected constructor keeps Dispatchers.Default off Main. */
    internal constructor(
        transport: CatalogTransport,
        cache: CatalogCacheStore,
        preferences: CatalogPreferencesStore,
        consentFence: CatalogConsentFence,
        envelopeParser: CatalogEnvelopeParser,
        networkSupported: Boolean,
        clock: Clock,
        catalogDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : this(transport, cache, preferences, consentFence, envelopeParser, networkSupported, clock) {
        this.catalogDispatcher = catalogDispatcher
    }

    /** Session-local fail-closed veto when explicit opt-out/reset durability fails. */
    private var sessionConsentVeto = false

    override suspend fun initialize() {
        initMutex.withLock {
            if (initialized) return

            val fence = try {
                consentFence.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                CatalogConsentFenceSnapshot(disabled = true, certain = false)
            }
            durableConsentVeto = fence.disabled || !fence.certain
            durableConsentUncertain = !fence.certain

            val snapshot = try {
                preferences.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _state.value = MerchantCatalogState(
                    networkSupported = networkSupported,
                    phase = CatalogRefreshPhase.FAILED,
                )
                // Keep initialization retryable after a transient persistent-store failure.
                return
            }

            var capabilityPreferenceFailure = false
            if (!networkSupported && snapshot.updatesEnabled) {
                try {
                    preferences.setOptIn(false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    capabilityPreferenceFailure = true
                }
            }

            var loadFailed = false
            activeCatalog = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    loadActiveCatalog(snapshot)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                loadFailed = true
                null
            }

            controlMutex.withLock {
                val vetoed = sessionConsentVeto || durableConsentVeto
                _state.value = MerchantCatalogState(
                    updatesEnabled = snapshot.updatesEnabled && networkSupported && !vetoed,
                    disablePersistencePending = !fence.certain && snapshot.updatesEnabled,
                    networkSupported = networkSupported,
                    version = activeCatalog?.version,
                    entryCount = activeCatalog?.entries?.size,
                    checkedAt = snapshot.lastCheckEpochSecond?.let {
                        runCatching { Instant.fromEpochSeconds(it) }.getOrNull()
                    },
                    phase = when {
                        loadFailed || capabilityPreferenceFailure || durableConsentUncertain -> CatalogRefreshPhase.FAILED

                        !networkSupported -> CatalogRefreshPhase.UNAVAILABLE
                        activeCatalog != null -> CatalogRefreshPhase.CURRENT
                        else -> CatalogRefreshPhase.IDLE
                    },
                )
            }
            initialized = !loadFailed
        }
    }

    override suspend fun setUpdatesEnabled(enabled: Boolean) {
        initialize()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            commitMutex.withLock {
                controlMutex.withLock {
                    when {
                        enabled && !networkSupported -> _state.update {
                            it.copy(updatesEnabled = false, phase = CatalogRefreshPhase.UNAVAILABLE)
                        }
                        !enabled -> revokeConsent()?.let { throw it }
                        else -> {
                            try {
                                withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { preferences.setOptIn(true) }
                                withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { consentFence.clear() }
                                durableConsentVeto = false
                                durableConsentUncertain = false
                                sessionConsentVeto = false
                                _state.update {
                                    it.copy(
                                        updatesEnabled = true,
                                        disablePersistencePending = false,
                                        phase = truthfulPhase(),
                                    )
                                }
                            } catch (failure: Throwable) {
                                generation.incrementAndGet()
                                sessionConsentVeto = true
                                durableConsentVeto = true
                                durableConsentUncertain = true
                                runCatching { withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { activeRequest?.cancel() } }
                                activeRequest = null
                                _state.update {
                                    it.copy(updatesEnabled = false, phase = CatalogRefreshPhase.FAILED)
                                }
                                throw failure
                            }
                        }
                    }
                }
            }
        }
    }

    /** Called only under commit/control locks; either durable veto is sufficient. */
    private suspend fun revokeConsent(): Throwable? {
        generation.incrementAndGet()
        sessionConsentVeto = true
        // Transport cancellation is best effort. It must not
        // prevent the independent durable revocation attempt.
        runCatching { withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { activeRequest?.cancel() } }
        activeRequest = null
        var fenceFailure: Throwable? = null
        var preferenceFailure: Throwable? = null
        try {
            withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { consentFence.disable() }
            durableConsentVeto = true
            durableConsentUncertain = false
        } catch (failure: Throwable) {
            durableConsentVeto = true
            durableConsentUncertain = true
            fenceFailure = failure
        }
        try {
            withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { preferences.setOptIn(false) }
        } catch (failure: Throwable) {
            preferenceFailure = failure
        }
        val failure = fenceFailure ?: preferenceFailure
        _state.update {
            it.copy(
                updatesEnabled = false,
                disablePersistencePending = fenceFailure != null && preferenceFailure != null,
                phase = if (failure == null) truthfulPhase() else CatalogRefreshPhase.FAILED,
            )
        }
        return failure
    }

    override suspend fun refresh(force: Boolean) {
        initialize()
        // Never replace a signed cached candidate while its startup floor could
        // not be reconciled. A subsequent call retries initialization first.
        if (!initialized) return
        if (!inFlight.compareAndSet(false, true)) return

        val generationAtStart = generation.get()
        var preparedRequest: CatalogPreparedRequest? = null
        try {
            val snapshot = try {
                preferences.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                failAttempt(generationAtStart)
                return
            }

            if (!snapshot.updatesEnabled || !networkSupported) {
                publishUnavailableIfCurrent(generationAtStart)
                return
            }

            // Keep an unsaved-revocation failure visible; never turn it into a
            // misleading connectivity message or start a new request.
            if (durableConsentVeto || durableConsentUncertain || sessionConsentVeto) return

            val now = clock.now()
            if (!force) {
                val lastFailure = listOfNotNull(
                    snapshot.lastFailureEpochSecond,
                    sessionLastFailureEpochSecond,
                ).maxOrNull()
                if (lastFailure != null && now.epochSeconds - lastFailure < BACKOFF_SECONDS) {
                    restoreIdleOrCurrentPhase(generationAtStart)
                    return
                }
                val lastCheck = snapshot.lastCheckEpochSecond
                val stale = lastCheck == null || now.epochSeconds - lastCheck >= STALENESS_SECONDS
                if (!stale) {
                    restoreIdleOrCurrentPhase(generationAtStart)
                    return
                }
            }

            // Consent validation, CHECKING publication, and prepared-request
            // registration are one control-mutex transaction. No stale attempt can
            // publish a phase after disable/reset advances generation.
            val request = prepareAttempt(generationAtStart)
            if (request == null) {
                restoreIdleOrCurrentPhase(generationAtStart)
                return
            }
            preparedRequest = request

            val raw = try {
                withTimeout(REQUEST_TIMEOUT_MILLIS) { request.execute() }
            } catch (timeout: TimeoutCancellationException) {
                if (generation.get() == generationAtStart) failAttempt(generationAtStart)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (generation.get() != generationAtStart) return
                failAttempt(
                    generationAtStart = generationAtStart,
                    unavailable = failure is CatalogTransportUnavailableException,
                )
                return
            }

            if (generation.get() != generationAtStart) return

            val verification = kotlinx.coroutines.withContext(catalogDispatcher) {
                envelopeParser.parse(raw)
            }
            when (verification) {
                is CatalogVerification.Invalid -> {
                    failAttempt(generationAtStart, rejection = verification.reason)
                }
                is CatalogVerification.Valid -> {
                    when (applyVerified(verification.catalog, raw, generationAtStart)) {
                        CommitResult.REJECTED,
                        CommitResult.FAILED -> failAttempt(generationAtStart)
                        CommitResult.STALE,
                        CommitResult.APPLIED -> Unit
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            if (generation.get() == generationAtStart) {
                cancelPrepared(preparedRequest)
                // A shielded commit may have completed before prompt caller cancellation.
                // Do not overwrite its terminal UPDATED/CURRENT/FAILED state.
                if (_state.value.phase == CatalogRefreshPhase.CHECKING) {
                    restoreIdleOrCurrentPhase(generationAtStart)
                }
            }
            throw cancellation
        } catch (failure: Throwable) {
            if (generation.get() == generationAtStart) {
                failAttempt(
                    generationAtStart = generationAtStart,
                    unavailable = failure is CatalogTransportUnavailableException,
                )
            }
        } finally {
            try {
                cleanupAttempt(preparedRequest, generationAtStart)
            } finally {
                inFlight.set(false)
            }
        }
    }

    override suspend fun resetToBundled() {
        initialize()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            commitMutex.withLock {
                controlMutex.withLock {
                    var resetFailure = revokeConsent()
                    try {
                        withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { cache.clear() }
                    } catch (failure: Throwable) {
                        if (resetFailure == null) resetFailure = failure
                    }

                    if (resetFailure == null) {
                        activeCatalog = null
                        activeEnvelope = null
                        _state.update {
                            it.copy(
                                updatesEnabled = false,
                                disablePersistencePending = false,
                                version = null,
                                entryCount = null,
                                phase = CatalogRefreshPhase.IDLE,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(updatesEnabled = false, phase = CatalogRefreshPhase.FAILED)
                        }
                        Timber.w("Catalog reset failed")
                        if (resetFailure is CancellationException) throw resetFailure
                    }
                }
            }
        }
    }

    override suspend fun snapshot(): MerchantCatalogSnapshot? {
        initialize()
        val catalog = activeCatalog ?: return null
        return MerchantCatalogSnapshot(catalog.entries.toList(), catalog.version)
    }

    /**
     * Re-verifies both cache files and applies the durable authenticated floor.
     * A cache-first newer file may advance the floor only after metadata
     * reconciliation succeeds. Invalid current bytes never overwrite previous.
     */
    private suspend fun loadActiveCatalog(snapshot: CatalogPrefsSnapshot): VerifiedCatalog? {
        activeEnvelope = null
        val candidates = mutableListOf<CachedCatalog>()
        var readFailure: IOException? = null
        var reconciliationFailed = false
        suspend fun readCandidate(read: suspend () -> ByteArray?, sourceOrder: Int) {
            val raw = try {
                read()
            } catch (failure: IOException) {
                readFailure = failure
                return
            } ?: return
            val parsed = envelopeParser.parse(raw)
            if (parsed is CatalogVerification.Valid) {
                candidates += CachedCatalog(parsed.catalog, raw, sourceOrder)
            }
        }
        readCandidate(cache::readCurrent, sourceOrder = 0)
        readCandidate(cache::readPrevious, sourceOrder = 1)

        val floorVersion = snapshot.highestVersion
        val floorDigest = snapshot.highestDigest
        val ordered = candidates.sortedWith(
            compareByDescending<CachedCatalog> { it.catalog.version }
                .thenBy { it.sourceOrder },
        )

        for (candidate in ordered) {
            val version = candidate.catalog.version
            if (floorVersion == null) {
                if (reconcileStartupFloor(candidate.catalog)) {
                    activeEnvelope = candidate.rawEnvelope
                    return candidate.catalog
                }
                reconciliationFailed = true
                continue
            }
            if (version < floorVersion) continue
            if (version == floorVersion) {
                if (candidate.catalog.payloadDigest == floorDigest) {
                    activeEnvelope = candidate.rawEnvelope
                    return candidate.catalog
                }
                continue
            }
            if (reconcileStartupFloor(candidate.catalog)) {
                activeEnvelope = candidate.rawEnvelope
                return candidate.catalog
            }
            reconciliationFailed = true
        }
        readFailure?.let { throw it }
        if (reconciliationFailed) throw IOException("catalog startup floor reconciliation failed")
        return null
    }

    private suspend fun reconcileStartupFloor(catalog: VerifiedCatalog): Boolean = try {
        preferences.recordAcceptance(catalog.version, catalog.payloadDigest)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        // Reject only this newer candidate; the caller may still recover a
        // verified previous candidate matching the persisted floor and digest.
        false
    }

    private suspend fun applyVerified(
        catalog: VerifiedCatalog,
        rawEnvelope: ByteArray,
        generationAtStart: Int,
    ): CommitResult {
        return try {
            kotlinx.coroutines.withContext(catalogDispatcher + kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) {
                    commitMutex.withLock {
                        val floor = preferences.load()
                        if (!floor.updatesEnabled || generation.get() != generationAtStart) {
                            return@withLock CommitResult.STALE
                        }

                        val highest = floor.highestVersion
                        if (highest != null && catalog.version < highest) {
                            Timber.w("Catalog refresh rejected: ${CatalogRejection.UNACCEPTABLE_VERSION.name}")
                            return@withLock CommitResult.REJECTED
                        }
                        if (
                            highest != null &&
                            catalog.version == highest &&
                            floor.highestDigest != catalog.payloadDigest
                        ) {
                            Timber.w("Catalog refresh rejected: ${CatalogRejection.UNACCEPTABLE_VERSION.name}")
                            return@withLock CommitResult.REJECTED
                        }

                        // Cache first is intentional. Preserve only the envelope
                        // authenticated as active; raw current may be corrupt.
                        // Cache files and DataStore are not one transaction.
                        cache.promoteAuthenticated(rawEnvelope, activeEnvelope)
                        if (highest == null || catalog.version > highest) {
                            preferences.recordAcceptance(catalog.version, catalog.payloadDigest)
                        }
                        val checkedAt = clock.now()
                        preferences.recordCheckSuccess(checkedAt.epochSeconds)
                        sessionLastFailureEpochSecond = null

                        // Disable/reset acquire commitMutex before fencing, so an
                        // older commit cannot publish state after either action wins.
                        activeCatalog = catalog
                        activeEnvelope = rawEnvelope
                        _state.update {
                            it.copy(
                                version = catalog.version,
                                entryCount = catalog.entries.size,
                                checkedAt = checkedAt,
                                phase = if (highest == null || catalog.version > highest) {
                                    CatalogRefreshPhase.UPDATED
                                } else {
                                    CatalogRefreshPhase.CURRENT
                                },
                            )
                        }
                        CommitResult.APPLIED
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            CommitResult.FAILED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            CommitResult.FAILED
        }
    }

    private suspend fun failAttempt(
        generationAtStart: Int,
        unavailable: Boolean = false,
        rejection: CatalogRejection? = null,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            controlMutex.withLock {
                if (generation.get() == generationAtStart) {
                    val failureEpochSecond = clock.now().epochSeconds
                    sessionLastFailureEpochSecond = failureEpochSecond
                    try {
                        withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { preferences.recordFailure(failureEpochSecond) }
                    } catch (_: Throwable) {
                        // Persistence is best effort; the session backoff is not.
                    }
                    _state.update {
                        it.copy(
                            phase = if (unavailable) {
                                CatalogRefreshPhase.UNAVAILABLE
                            } else {
                                CatalogRefreshPhase.FAILED
                            },
                        )
                    }
                    if (rejection != null) {
                        Timber.w("Catalog refresh rejected: %s", rejection.name)
                    }
                }
            }
        }
    }

    private suspend fun prepareAttempt(generationAtStart: Int): CatalogPreparedRequest? =
        controlMutex.withLock {
            val consent = preferences.load()
            if (
                !networkSupported ||
                durableConsentVeto ||
                durableConsentUncertain ||
                sessionConsentVeto ||
                !consent.updatesEnabled ||
                generation.get() != generationAtStart
            ) {
                null
            } else {
                _state.update { it.copy(updatesEnabled = true, phase = CatalogRefreshPhase.CHECKING) }
                transport.prepare().also { activeRequest = it }
            }
        }

    private suspend fun publishUnavailableIfCurrent(generationAtStart: Int) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            controlMutex.withLock {
                if (generation.get() == generationAtStart) {
                    _state.update { it.copy(phase = CatalogRefreshPhase.UNAVAILABLE) }
                }
            }
        }
    }

    private suspend fun cancelPrepared(request: CatalogPreparedRequest?) {
        if (request == null) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { withTimeout(LOCAL_COMMIT_TIMEOUT_MILLIS) { request.cancel() } }
        }
    }

    private suspend fun cleanupAttempt(
        request: CatalogPreparedRequest?,
        generationAtStart: Int,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            controlMutex.withLock {
                if (activeRequest === request) activeRequest = null
                if (
                    generation.get() == generationAtStart &&
                    _state.value.phase == CatalogRefreshPhase.CHECKING
                ) {
                    _state.update { it.copy(phase = truthfulPhase()) }
                }
            }
        }
    }

    private suspend fun restoreIdleOrCurrentPhase(generationAtStart: Int) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            controlMutex.withLock {
                if (generation.get() == generationAtStart) {
                    _state.update { it.copy(phase = truthfulPhase()) }
                }
            }
        }
    }

    private fun truthfulPhase(): CatalogRefreshPhase =
        if (activeCatalog != null) CatalogRefreshPhase.CURRENT else CatalogRefreshPhase.IDLE

    private data class CachedCatalog(
        val catalog: VerifiedCatalog,
        val rawEnvelope: ByteArray,
        val sourceOrder: Int,
    )

    private enum class CommitResult { STALE, REJECTED, FAILED, APPLIED }

    companion object {
        const val STALENESS_SECONDS: Long = 7L * 24 * 60 * 60
        const val BACKOFF_SECONDS: Long = 24L * 60 * 60
        const val REQUEST_TIMEOUT_MILLIS: Long = 25_000
        const val LOCAL_COMMIT_TIMEOUT_MILLIS: Long = 5_000
    }
}
