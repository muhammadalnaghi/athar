package com.athar.core.data.catalog

import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.PatternType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Repository behavior tests. Network/cache/preferences/verifier/clock are injected
 * fakes — no real network, no real files, ephemeral signing key only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MerchantCatalogRepositoryImplTest {

    private val base: Instant = Instant.parse("2026-10-16T12:00:00Z")

    @Test
    fun `default state is off and refresh without opt-in never touches the network`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repository(transport = transport, clock = clockAt(base))

        repo.refresh()

        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UNAVAILABLE)
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `regression - capability off never persists enabled updates`() = runTest {
        val prefs = FakePreferences()
        val repo = repository(
            transport = FakeTransport(response = signedCatalogV1()),
            prefs = prefs,
            optIn = false,
            networkSupported = false,
            clock = clockAt(base),
        )
        repo.setUpdatesEnabled(true)
        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(prefs.snapshot().updatesEnabled).isFalse()
    }

    @Test
    fun `refresh without network permission is unavailable and never calls transport`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repository(transport = transport, networkSupported = false, optIn = true, clock = clockAt(base))

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UNAVAILABLE)
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `first successful refresh applies the updated catalog and records acceptance`() = runTest {
        val raw = signedCatalogV1()
        val transport = FakeTransport(response = raw)
        val prefs = FakePreferences()
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
        assertThat(repo.state.value.version).isEqualTo(1)
        assertThat(repo.state.value.entryCount).isEqualTo(1)
        assertThat(repo.state.value.checkedAt).isEqualTo(base)
        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(prefs.snapshot().highestVersion).isEqualTo(1)
        assertThat(prefs.snapshot().lastCheckEpochSecond).isEqualTo(base.epochSeconds)
    }

    @Test
    fun `identical version with same authenticated digest is an unchanged success`() = runTest {
        val raw = signedCatalogV1()
        val prefs = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(raw)),
            lastCheckEpochSecond = Instant.parse("2026-10-15T12:00:00Z").epochSeconds,
        )
        val transport = FakeTransport(response = raw)
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.CURRENT)
        assertThat(repo.state.value.version).isEqualTo(1)
        assertThat(repo.state.value.checkedAt).isEqualTo(base)
        assertThat(repo.entries()).isNotEmpty()
    }

    @Test
    fun `identical version with a different payload digest is rejected`() = runTest {
        val original = signedCatalogV1()
        val other = signedCatalog(version = 1, pattern = "starbuckz")
        val prefs = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(original)),
        )
        val transport = FakeTransport(response = other)
        val cache = FakeCache(current = original)
        val repo = repository(transport = transport, cache = cache, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(repo.state.value.checkedAt).isNull()
        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(prefs.snapshot().lastCheckEpochSecond).isNull()
    }

    @Test
    fun `a version below the highest accepted floor is rejected`() = runTest {
        val high = signedCatalog(version = 5)
        val low = signedCatalog(version = 4)
        val prefs = FakePreferences(
            highestVersion = 5L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(high)),
        )
        val transport = FakeTransport(response = low)
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(repo.state.value.version).isNull()
    }

    @Test
    fun `a higher version is accepted and the previous envelope is retained`() = runTest {
        val old = signedCatalog(version = 1)
        val new = signedCatalog(version = 2, pattern = "starbucks2")
        val cache = FakeCache(current = old)
        val prefs = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(old)),
        )
        val transport = FakeTransport(response = new)
        val repo = repository(transport = transport, cache = cache, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
        assertThat(repo.state.value.version).isEqualTo(2)
        assertThat(cache.previous).isEqualTo(old)
        assertThat(repo.entries().single().pattern).isEqualTo("starbucks2")
    }

    @Test
    fun `a failed attempt does not record a check time and backs off automatic refresh`() = runTest {
        val transport = FakeTransport(error = java.io.IOException("offline"))
        val prefs = FakePreferences()
        val clock = MutableClock(base)
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clock)

        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(prefs.snapshot().lastCheckEpochSecond).isNull()
        assertThat(prefs.snapshot().lastFailureEpochSecond).isEqualTo(base.epochSeconds)
        assertThat(transport.calls.get()).isEqualTo(1)

        // Within the one-day backoff an automatic check must not re-attempt.
        clock.now_ = base.plus(2, kotlinx.datetime.DateTimeUnit.HOUR)
        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(1)

        // After the backoff window the automatic check may retry (48h > 24h).
        clock.now_ = base.plus(48, kotlinx.datetime.DateTimeUnit.HOUR)
        transport.error = null
        transport.response = signedCatalogV1()
        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(2)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
    }

    @Test
    fun `regression - session failure backoff survives failed failure metadata write`() = runTest {
        val backing = FakePreferences()
        backing.setOptInBlocking(true)
        var failFirstRecord = true
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordFailure(epochSecond: Long) {
                if (failFirstRecord) {
                    failFirstRecord = false
                    throw java.io.IOException("failure metadata")
                }
                backing.recordFailure(epochSecond)
            }
        }
        val transport = FakeTransport(error = java.io.IOException("offline"))
        val clock = MutableClock(base)
        val repo = repositoryWith(
            transport = transport,
            cache = FakeCache(),
            prefs = prefs,
            optIn = true,
            clock = clock,
        )
        repo.refresh(true)
        clock.now_ = base.plus(2, kotlinx.datetime.DateTimeUnit.HOUR)
        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(1)

        clock.now_ = base.plus(48, kotlinx.datetime.DateTimeUnit.HOUR)
        transport.error = null
        transport.response = signedCatalogV1()
        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(2)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
    }

    @Test
    fun `automatic refresh skips a fresh cache and runs after seven days`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val clock = MutableClock(base)
        val prefs = FakePreferences(lastCheckEpochSecond = base.minus(24, kotlinx.datetime.DateTimeUnit.HOUR).epochSeconds)
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clock)
        repo.initialize()

        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(0)

        clock.now_ = base.plus(192, kotlinx.datetime.DateTimeUnit.HOUR)
        repo.refresh()
        assertThat(transport.calls.get()).isEqualTo(1)
    }

    @Test
    fun `manual refresh bypasses staleness and backoff`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val prefs = FakePreferences(
            lastCheckEpochSecond = base.epochSeconds,
            lastFailureEpochSecond = base.epochSeconds,
        )
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))

        repo.refresh(true)

        assertThat(transport.calls.get()).isEqualTo(1)
    }

    @Test
    fun `offline keeps the last verified entries usable`() = runTest {
        val raw = signedCatalogV1()
        val cache = FakeCache(current = raw)
        val transport = FakeTransport(error = java.io.IOException("offline"))
        val repo = repository(transport = transport, cache = cache, prefs = FakePreferences(), optIn = true, clock = clockAt(base))
        repo.initialize()

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
    }

    @Test
    fun `reset disables clears entries and cache but preserves the highest floor and digest`() = runTest {
        val raw = signedCatalogV1()
        val transport = FakeTransport(response = raw)
        val prefs = FakePreferences()
        val cache = FakeCache(current = raw)
        val repo = repository(transport = transport, cache = cache, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()
        repo.refresh(true)
        assertThat(repo.entries()).isNotEmpty()

        repo.resetToBundled()

        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(repo.state.value.version).isNull()
        assertThat(repo.state.value.entryCount).isNull()
        assertThat(repo.entries()).isEmpty()
        assertThat(cache.current).isNull()
        assertThat(prefs.snapshot().highestVersion).isEqualTo(1L)
        assertThat(prefs.snapshot().highestDigest).isNotEmpty()
        assertThat(prefs.snapshot().updatesEnabled).isFalse()

        // Re-enabling can reacquire the identical verified catalog at the floor.
        prefs.setOptIn(true)
        repo.initialize() // no-op, already initialized
        repo.setUpdatesEnabled(true)
        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.CURRENT)
        assertThat(repo.entries()).isNotEmpty()
    }

    @Test
    fun `simultaneous automatic refreshes coalesce into one transport call`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repository(transport = transport, prefs = FakePreferences(), optIn = true, clock = clockAt(base))

        val a = async(UnconfinedTestDispatcher(testScheduler)) { repo.refresh() }
        val b = async(UnconfinedTestDispatcher(testScheduler)) { repo.refresh() }
        a.await()
        b.await()

        assertThat(transport.calls.get()).isEqualTo(1)
        assertThat(repo.state.value.version).isEqualTo(1)
    }

    @Test
    fun `opt-out during an in-flight download discards the stale result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport(response = signedCatalogV1(), gate = gate)
        val prefs = FakePreferences()
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        // The request is now suspended on the transport gate while opted in.
        repo.setUpdatesEnabled(false)
        gate.complete(Unit)
        job.join()

        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(prefs.snapshot().highestVersion).isNull()
        assertThat(repo.entries()).isEmpty()
        // F03: the registered in-flight call must actually be cancelled.
        assertThat(transport.cancelRequests.get()).isEqualTo(1)
    }

    @Test
    fun `opt-out between the initial snapshot and the wire prevents any request`() = runTest {
        val prefs = FakePreferences()
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()

        // Hold the NEXT load (the refresh's initial snapshot read) and opt out
        // while refresh is suspended before it can reach the wire.
        val loadGate = CompletableDeferred<Unit>()
        prefs.loadGate = loadGate
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        repo.setUpdatesEnabled(false)
        loadGate.complete(Unit)
        job.join()

        assertThat(transport.calls.get()).isEqualTo(0)
        assertThat(prefs.snapshot().highestVersion).isNull()
        assertThat(repo.entries()).isEmpty()
    }

    @Test
    fun `reset wins deterministically over an in-flight commit`() = runTest {
        // Barrier inside the cache write: the refresh is suspended inside its
        // commit while reset is requested.
        val writeGate = CompletableDeferred<Unit>()
        val raw = signedCatalogV1()
        val cache = FakeCache(current = raw)
        val gatedCache = object : CatalogCacheStore by cache {
            override suspend fun promoteAuthenticated(next: ByteArray, trustedPrevious: ByteArray?) {
                writeGate.await()
                cache.promoteAuthenticated(next, trustedPrevious)
            }
        }
        val transport = FakeTransport(response = raw)
        val prefs = FakePreferences()
        val repo = repository(transport = transport, cache = gatedCache, prefs = prefs, optIn = true, clock = clockAt(base))
        repo.initialize()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        // Reset must not run inline: it would block the single test thread on the
        // commit mutex the gated refresh still holds.
        val resetJob = launch { repo.resetToBundled() }
        writeGate.complete(Unit)
        job.join()
        resetJob.join()

        // No new cache, entries, or active version survives a completed reset;
        // the durable highest floor + digest are intentionally preserved (re-enable
        // can reacquire the identical verified catalog).
        assertThat(cache.current).isNull()
        assertThat(repo.entries()).isEmpty()
        assertThat(repo.state.value.version).isNull()
        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(prefs.snapshot().highestVersion).isEqualTo(1L)
        assertThat(prefs.snapshot().highestDigest).isNotEmpty()
    }

    @Test
    fun `cache load re-verifies the signature and falls back to the previous envelope`() = runTest {
        val good = signedCatalogV1()
        val tamperedCurrent = good.toString(Charsets.UTF_8)
            .replace("starbucks", "starbuckz")
            .toByteArray(Charsets.UTF_8)
        val cache = FakeCache(current = tamperedCurrent, previous = good)
        val prefs = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(good)),
        )
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = cache,
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()

        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(repo.state.value.version).isEqualTo(1)
    }

    @Test
    fun `entries awaits initialization so cold-start never misses cached knowledge`() = runTest {
        val cache = FakeCache(current = signedCatalogV1())
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = cache,
            prefs = FakePreferences(),
            optIn = true,
            clock = clockAt(base),
        )

        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
    }

    @Test
    fun `disable during initial snapshot cannot publish checking or start wire`() = runTest {
        val backing = FakePreferences()
        backing.setOptInBlocking(true)
        val prefs = SnapshotBarrierPreferences(backing)
        val transport = PreparedBarrierTransport(signedCatalogV1())
        val repo = repositoryWith(
            transport = transport,
            cache = FakeCache(),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )
        repo.initialize()
        prefs.holdAfterFirstLoad = true

        val refresh = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        prefs.snapshotCaptured.await()
        repo.setUpdatesEnabled(false)
        prefs.releaseSnapshot.complete(Unit)
        refresh.join()

        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
        assertThat(transport.prepareCalls.get()).isEqualTo(0)
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `initialization retries after transient preference failure`() = runTest {
        val backing = FakePreferences()
        backing.setOptInBlocking(true)
        var failFirstLoad = true
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun load(): CatalogPrefsSnapshot {
                if (failFirstLoad) {
                    failFirstLoad = false
                    throw java.io.IOException("transient preference read")
                }
                return backing.load()
            }
        }
        val repo = repositoryWith(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = FakeCache(),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)

        repo.initialize()

        assertThat(repo.state.value.updatesEnabled).isTrue()
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
    }

    @Test
    fun `cancellation between durable floor and active state completes coherent commit`() = runTest {
        val old = signedCatalog(version = 1)
        val newer = signedCatalog(version = 2, pattern = "new merchant")
        val backing = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(old)),
            updatesEnabled = true,
        )
        val prefs = CheckSuccessBarrierPreferences(backing)
        val repo = repositoryWith(
            transport = FakeTransport(response = newer),
            cache = FakeCache(current = old),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
            catalogDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        repo.initialize()

        val refresh = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        testScheduler.runCurrent()
        prefs.commitStarted.await()
        refresh.cancel()
        prefs.releaseCommit.complete(Unit)
        testScheduler.runCurrent()
        refresh.join()

        assertThat(repo.state.value.version).isEqualTo(2L)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
        assertThat(backing.snapshot().highestVersion).isEqualTo(2L)
    }

    @Test
    fun `opt out cancels a prepared request before it reaches the wire`() = runTest {
        val transport = PreparedBarrierTransport(signedCatalogV1())
        val repo = repository(transport = transport, optIn = true, clock = clockAt(base))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }

        transport.prepared.await()
        repo.setUpdatesEnabled(false)
        job.join()

        assertThat(transport.calls.get()).isEqualTo(0)
        assertThat(transport.cancellations.get()).isEqualTo(1)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
    }

    @Test
    fun `opt out cancels the prepared request while response bytes are in flight`() = runTest {
        val transport = PreparedBarrierTransport(signedCatalogV1())
        val repo = repository(transport = transport, optIn = true, clock = clockAt(base))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }

        transport.prepared.await()
        transport.executionGate.complete(Unit)
        transport.wireStarted.await()
        repo.setUpdatesEnabled(false)
        job.join()

        assertThat(transport.calls.get()).isEqualTo(1)
        assertThat(transport.cancellations.get()).isEqualTo(1)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
    }

    @Test
    fun `external coroutine cancellation restores truthful idle state`() = runTest {
        val transport = PreparedBarrierTransport(signedCatalogV1())
        val repo = repository(transport = transport, optIn = true, clock = clockAt(base))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }

        transport.prepared.await()
        job.cancel()
        job.join()

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
    }

    @Test
    fun `force refreshes coalesce while one prepared request is gated`() = runTest {
        val transport = PreparedBarrierTransport(signedCatalogV1())
        val repo = repository(transport = transport, optIn = true, clock = clockAt(base))
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        transport.prepared.await()
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh(true) }
        second.join()

        assertThat(transport.prepareCalls.get()).isEqualTo(1)
        transport.executionGate.complete(Unit)
        transport.wireStarted.await()
        transport.responseGate.complete(signedCatalogV1())
        first.join()
        assertThat(transport.calls.get()).isEqualTo(1)
    }

    @Test
    fun `request timeout leaves failed state and permits a successful retry`() = runTest {
        val transport = TimeoutTransport(signedCatalogV1())
        val prefs = FakePreferences()
        val repo = repository(transport = transport, prefs = prefs, optIn = true, clock = clockAt(base))

        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(prefs.snapshot().lastCheckEpochSecond).isNull()

        transport.delayResponse = false
        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
    }

    @Test
    fun `cache write failure never leaves checking and a later retry succeeds`() = runTest {
        val raw = signedCatalogV1()
        val backing = FakeCache()
        var fail = true
        val cache = object : CatalogCacheStore by backing {
            override suspend fun promoteAuthenticated(next: ByteArray, trustedPrevious: ByteArray?) {
                if (fail) throw java.io.IOException("cache write")
                backing.promoteAuthenticated(next, trustedPrevious)
            }
        }
        val repo = repository(
            transport = FakeTransport(response = raw),
            cache = cache,
            optIn = true,
            clock = clockAt(base),
        )

        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        fail = false
        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.UPDATED)
    }

    @Test
    fun `preferences commit failure is truthful and a later retry succeeds`() = runTest {
        val raw = signedCatalogV1()
        val backing = FakePreferences()
        backing.setOptInBlocking(true)
        var fail = true
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordCheckSuccess(epochSecond: Long) {
                if (fail) {
                    fail = false
                    throw java.io.IOException("preferences commit")
                }
                backing.recordCheckSuccess(epochSecond)
            }
        }
        val repo = repositoryWith(
            transport = FakeTransport(response = raw),
            cache = FakeCache(),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        repo.refresh(true)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.CURRENT)
    }

    @Test
    fun `failed reset reports failure and does not pretend cache was cleared`() = runTest {
        val raw = signedCatalogV1()
        val backing = FakeCache(current = raw)
        val cache = object : CatalogCacheStore by backing {
            override suspend fun clear() {
                throw java.io.IOException("delete failure")
            }
        }
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = cache,
            optIn = true,
            clock = clockAt(base),
        )
        repo.initialize()
        repo.resetToBundled()

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(repo.entries()).isNotEmpty()
    }

    @Test
    fun `older valid cache cannot become current below the durable floor`() = runTest {
        val older = signedCatalog(version = 1)
        val floor = signedCatalog(version = 2, pattern = "new merchant")
        val prefs = FakePreferences(
            highestVersion = 2L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(floor)),
            updatesEnabled = true,
        )
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = FakeCache(current = older),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()

        assertThat(repo.entries()).isEmpty()
        assertThat(repo.state.value.version).isNull()
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.IDLE)
    }

    @Test
    fun `startup reconciles a verified cache newer than the durable floor`() = runTest {
        val old = signedCatalog(version = 1)
        val newer = signedCatalog(version = 2, pattern = "new merchant")
        val prefs = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(old)),
            updatesEnabled = true,
        )
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = FakeCache(current = newer, previous = old),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()

        assertThat(repo.state.value.version).isEqualTo(2)
        assertThat(prefs.snapshot().highestVersion).isEqualTo(2L)
        assertThat(prefs.snapshot().highestDigest).isEqualTo(CatalogDigest.sha256Hex(payloadOf(newer)))
    }

    @Test
    fun `startup recovers matching previous same-version bytes without overwriting them`() = runTest {
        val good = signedCatalog(version = 3)
        val divergent = signedCatalog(version = 3, pattern = "different merchant")
        val cache = FakeCache(current = divergent, previous = good)
        val prefs = FakePreferences(
            highestVersion = 3L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(good)),
            updatesEnabled = true,
        )
        val repo = repository(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = cache,
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()

        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(cache.previous).isEqualTo(good)
        assertThat(cache.current).isEqualTo(divergent)
    }

    @Test
    fun `failed opt-out persistence still prevents later requests in the same session`() = runTest {
        val backing = FakePreferences(updatesEnabled = true)
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun setOptIn(enabled: Boolean) {
                if (!enabled) throw java.io.IOException("opt-out persistence failed")
                backing.setOptIn(enabled)
            }
        }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(
            transport = transport,
            cache = FakeCache(),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )
        repo.initialize()

        try {
            repo.setUpdatesEnabled(false)
            error("Expected persistence failure")
        } catch (_: java.io.IOException) {
            // Durable consent is still true, but the user's current-session veto wins.
        }
        assertThat(backing.snapshot().updatesEnabled).isTrue()
        assertThat(repo.state.value.updatesEnabled).isFalse()

        repo.refresh(true)

        assertThat(transport.calls.get()).isEqualTo(0)
        assertThat(repo.state.value.updatesEnabled).isFalse()
    }

    @Test
    fun `startup retains floor-matching previous cache when newer floor persistence fails`() = runTest {
        val old = signedCatalog(version = 1)
        val newer = signedCatalog(version = 2, pattern = "new merchant")
        val backing = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(old)),
            updatesEnabled = true,
        )
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordAcceptance(version: Long, digest: String) {
                throw java.io.IOException("newer floor persistence failed")
            }
        }
        val repo = repositoryWith(
            transport = FakeTransport(error = java.io.IOException("offline")),
            cache = FakeCache(current = newer, previous = old),
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()

        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(repo.state.value.version).isEqualTo(1L)
        assertThat(backing.snapshot().highestVersion).isEqualTo(1L)
    }

    @Test
    fun `failed update preserves authenticated previous when current cache is corrupt`() = runTest {
        val old = signedCatalog(version = 1)
        val newer = signedCatalog(version = 2, pattern = "new merchant")
        val backing = FakePreferences(
            highestVersion = 1L,
            highestDigest = CatalogDigest.sha256Hex(payloadOf(old)),
            updatesEnabled = true,
        )
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordAcceptance(version: Long, digest: String) {
                throw java.io.IOException("newer floor persistence failed")
            }
        }
        val corruptCurrent = "{\"corrupt\":true}".toByteArray(Charsets.UTF_8)
        val cache = FakeCache(current = corruptCurrent, previous = old)
        val repo = repositoryWith(
            transport = FakeTransport(response = newer),
            cache = cache,
            prefs = prefs,
            optIn = true,
            clock = clockAt(base),
        )

        repo.initialize()
        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )

        repo.refresh(true)

        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
        assertThat(cache.previous).isEqualTo(old)
        assertThat(repo.entries()).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
    }

    // ---- fakes & helpers ----

    @Test
    fun `startup floor persistence retry prevents lower downloaded catalog replacing higher cache`() = runTest {
        val higher = signedCatalog(version = 2L)
        val cached = FakeCache().apply { current = higher }
        val backing = FakePreferences(updatesEnabled = true)
        var failAcceptance = true
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordAcceptance(version: Long, digest: String) {
                if (failAcceptance) throw IOException("synthetic startup floor write failure")
                backing.recordAcceptance(version, digest)
            }
        }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(transport, cached, prefs, optIn = true, clock = clockAt(base))
        assertThat(repo.snapshot()).isNull()
        failAcceptance = false
        repo.refresh(force = true)
        assertThat(repo.snapshot()?.version).isEqualTo(2L)
        assertThat(backing.snapshot().highestVersion).isEqualTo(2L)
        assertThat(cached.current).isEqualTo(higher)
    }

    @Test
    fun `unreconciled startup floor blocks HTTP and preserves higher cached bytes`() = runTest {
        val higher = signedCatalog(version = 2L)
        val cached = FakeCache().apply { current = higher }
        val backing = FakePreferences(updatesEnabled = true)
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun recordAcceptance(version: Long, digest: String) {
                throw IOException("synthetic startup floor write failure")
            }
        }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(transport, cached, prefs, optIn = true, clock = clockAt(base))
        repo.refresh(force = true)
        assertThat(transport.calls.get()).isEqualTo(0)
        assertThat(cached.current).isEqualTo(higher)
        assertThat(repo.state.value.phase).isEqualTo(com.athar.core.domain.model.CatalogRefreshPhase.FAILED)
    }

    @Test
    fun `unreadable current still restores independently authenticated previous catalog`() = runTest {
        val cached = FakeCache().apply { previous = signedCatalogV1() }
        val cache = object : CatalogCacheStore by cached {
            override suspend fun readCurrent(): ByteArray? = throw IOException("unreadable current")
        }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(
            transport, cache, FakePreferences(), optIn = false, clock = clockAt(base),
        )
        assertThat(repo.snapshot()?.version).isEqualTo(1L)
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `transient offline cache read failure retries initialization without network`() = runTest {
        val cached = FakeCache().apply { current = signedCatalogV1() }
        var failRead = true
        val cache = object : CatalogCacheStore by cached {
            override suspend fun readCurrent(): ByteArray? {
                if (failRead) {
                    failRead = false
                    throw IOException("synthetic transient read failure")
                }
                return cached.readCurrent()
            }
        }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(
            transport, cache, FakePreferences(), optIn = false, clock = clockAt(base),
        )
        assertThat(repo.snapshot()).isNull()
        val recovered = repo.snapshot()
        assertThat(recovered?.version).isEqualTo(1L)
        assertThat(recovered?.entries).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(transport.calls.get()).isEqualTo(0)
        assertThat(repo.state.value.updatesEnabled).isFalse()
    }

    @Test
    fun `durable disable fence vetoes recreated repository after preference write failure`() = runTest {
        val backing = FakePreferences(updatesEnabled = true)
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun setOptIn(enabled: Boolean) {
                if (!enabled) throw IOException("metadata unavailable")
                backing.setOptIn(enabled)
            }
        }
        val fence = FakeConsentFence()
        val transport = FakeTransport(response = signedCatalogV1())
        fun recreated() = repositoryWith(
            transport, FakeCache(), prefs, optIn = true, clock = clockAt(base), fence = fence,
        )
        val first = recreated()
        assertThat(runCatching { first.setUpdatesEnabled(false) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(backing.snapshot().updatesEnabled).isTrue()
        assertThat(first.state.value.disablePersistencePending).isFalse()

        val second = recreated()
        second.initialize()
        second.refresh(force = true)
        assertThat(second.state.value.updatesEnabled).isFalse()
        assertThat(second.state.value.disablePersistencePending).isFalse()
        assertThat(transport.calls.get()).isEqualTo(0)

        second.setUpdatesEnabled(true)
        second.refresh(force = true)
        assertThat(second.state.value.updatesEnabled).isTrue()
        assertThat(transport.calls.get()).isEqualTo(1)
    }

    @Test
    fun `both revocation stores failing is honestly retryable without reopening network`() = runTest {
        val backing = FakePreferences(updatesEnabled = true)
        var failMetadata = true
        val prefs = object : CatalogPreferencesStore by backing {
            override suspend fun setOptIn(enabled: Boolean) {
                if (!enabled && failMetadata) throw IOException("metadata unavailable")
                backing.setOptIn(enabled)
            }
        }
        val fence = FakeConsentFence().apply { failDisable = true }
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repositoryWith(
            transport, FakeCache(), prefs, optIn = true, clock = clockAt(base), fence = fence,
        )
        assertThat(runCatching { repo.setUpdatesEnabled(false) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        repo.refresh(force = true)
        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(repo.state.value.disablePersistencePending).isTrue()
        assertThat(transport.calls.get()).isEqualTo(0)

        failMetadata = false
        fence.failDisable = false
        repo.setUpdatesEnabled(false)
        assertThat(repo.state.value.disablePersistencePending).isFalse()
        assertThat(backing.snapshot().updatesEnabled).isFalse()
        assertThat(fence.stored.disabled).isTrue()
    }

    @Test
    fun `uncertain persisted consent fence fails closed even with saved opt-in`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val fence = FakeConsentFence().apply {
            stored = CatalogConsentFenceSnapshot(disabled = false, certain = false)
        }
        val repo = repositoryWith(
            transport, FakeCache(), FakePreferences(), optIn = true, clock = clockAt(base), fence = fence,
        )
        repo.refresh(force = true)
        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(repo.state.value.disablePersistencePending).isTrue()
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `failed explicit enable cannot clear the veto or start forced refresh`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val fence = FakeConsentFence().apply { failClear = true }
        val repo = repositoryWith(
            transport, FakeCache(), FakePreferences(), optIn = false, clock = clockAt(base), fence = fence,
        )
        assertThat(runCatching { repo.setUpdatesEnabled(true) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        repo.refresh(force = true)
        assertThat(repo.state.value.updatesEnabled).isFalse()
        assertThat(transport.calls.get()).isEqualTo(0)
    }

    @Test
    fun `snapshots keep entries and version coherent after reset`() = runTest {
        val transport = FakeTransport(response = signedCatalogV1())
        val repo = repository(transport, optIn = true, clock = clockAt(base))
        repo.refresh(force = true)
        val before = requireNotNull(repo.snapshot())
        repo.resetToBundled()
        assertThat(repo.snapshot()).isNull()
        assertThat(before.version).isEqualTo(1L)
        assertThat(before.entries).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
    }

    private fun repositoryWith(
        transport: CatalogTransport,
        cache: CatalogCacheStore,
        prefs: CatalogPreferencesStore,
        optIn: Boolean,
        networkSupported: Boolean = true,
        clock: Clock,
        catalogDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.Default,
        fence: CatalogConsentFence = FakeConsentFence(),
    ): MerchantCatalogRepositoryImpl {
        if (prefs is FakePreferences) prefs.setOptInBlocking(optIn)
        val parser = CatalogEnvelopeParser(
            verifier = JcaCatalogSignatureVerifier(spkiOf(keyPair)),
            clock = clock,
            guard = { _, _ -> },
        )
        return MerchantCatalogRepositoryImpl(
            transport = transport,
            cache = cache,
            preferences = prefs,
            envelopeParser = parser,
            networkSupported = networkSupported,
            clock = clock,
            catalogDispatcher = catalogDispatcher,
            consentFence = fence,
        )
    }

    private class SnapshotBarrierPreferences(
        private val backing: FakePreferences,
    ) : CatalogPreferencesStore by backing {
        var holdAfterFirstLoad = false
        val snapshotCaptured = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        private var held = false

        override suspend fun load(): CatalogPrefsSnapshot {
            val snapshot = backing.load()
            if (holdAfterFirstLoad && !held) {
                held = true
                snapshotCaptured.complete(Unit)
                releaseSnapshot.await()
            }
            return snapshot
        }
    }

    private class CheckSuccessBarrierPreferences(
        private val backing: FakePreferences,
    ) : CatalogPreferencesStore by backing {
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        private var held = false

        override suspend fun recordCheckSuccess(epochSecond: Long) {
            if (!held) {
                held = true
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
            backing.recordCheckSuccess(epochSecond)
        }
    }

    private class PreparedBarrierTransport(
        private val response: ByteArray,
    ) : CatalogTransport {
        val prepared = CompletableDeferred<Unit>()
        val executionGate = CompletableDeferred<Unit>()
        val wireStarted = CompletableDeferred<Unit>()
        val responseGate = CompletableDeferred<ByteArray>()
        val prepareCalls = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val cancellations = AtomicInteger(0)

        override fun prepare(): CatalogPreparedRequest {
            prepareCalls.incrementAndGet()
            prepared.complete(Unit)
            return object : CatalogPreparedRequest {
                override suspend fun execute(): ByteArray {
                    executionGate.await()
                    calls.incrementAndGet()
                    wireStarted.complete(Unit)
                    return responseGate.await()
                }

                override suspend fun cancel() {
                    cancellations.incrementAndGet()
                    executionGate.cancel(kotlinx.coroutines.CancellationException("cancelled before execution"))
                    responseGate.completeExceptionally(kotlinx.coroutines.CancellationException("cancelled during execution"))
                }
            }
        }

        override suspend fun fetch(): ByteArray = error("prepared request path expected")
        override suspend fun cancelActive() = Unit
    }

    private class TimeoutTransport(
        private val response: ByteArray,
    ) : CatalogTransport {
        var delayResponse = true

        override fun prepare(): CatalogPreparedRequest = object : CatalogPreparedRequest {
            override suspend fun execute(): ByteArray {
                if (delayResponse) kotlinx.coroutines.delay(30_000)
                return response
            }

            override suspend fun cancel() = Unit
        }

        override suspend fun fetch(): ByteArray = response
        override suspend fun cancelActive() = Unit
    }

    private fun repository(
        transport: CatalogTransport,
        cache: CatalogCacheStore = FakeCache(),
        prefs: FakePreferences = FakePreferences(),
        optIn: Boolean = false,
        networkSupported: Boolean = true,
        clock: Clock,
    ): MerchantCatalogRepositoryImpl {
        prefs.setOptInBlocking(optIn)
        val parser = CatalogEnvelopeParser(
            verifier = JcaCatalogSignatureVerifier(spkiOf(keyPair)),
            clock = clock,
            // JVM: skip only the platform token walk; strict kotlinx parsing stays on.
            guard = { _, _ -> },
        )
        return MerchantCatalogRepositoryImpl(
            transport = transport,
            cache = cache,
            preferences = prefs,
            envelopeParser = parser,
            networkSupported = networkSupported,
            clock = clock,
            consentFence = FakeConsentFence(),
        )
    }

    private class FakeConsentFence : CatalogConsentFence {
        var stored = CatalogConsentFenceSnapshot(disabled = false, certain = true)
        var failDisable = false
        var failClear = false
        override suspend fun read(): CatalogConsentFenceSnapshot = stored
        override suspend fun disable() {
            if (failDisable) throw IOException("fence unavailable")
            stored = CatalogConsentFenceSnapshot(disabled = true, certain = true)
        }
        override suspend fun clear() {
            if (failClear) throw IOException("fence unavailable")
            stored = CatalogConsentFenceSnapshot(disabled = false, certain = true)
        }
    }

    private fun clockAt(instant: Instant): Clock = object : Clock {
        override fun now(): Instant = instant
    }

    private class MutableClock(start: Instant) : Clock {
        var now_: Instant = start
        override fun now(): Instant = now_
    }

    private class FakeTransport(
        var response: ByteArray? = null,
        var error: Exception? = null,
        val gate: CompletableDeferred<Unit>? = null,
    ) : CatalogTransport {
        val calls = AtomicInteger(0)
        val cancelRequests = AtomicInteger(0)

        override suspend fun fetch(): ByteArray {
            calls.incrementAndGet()
            error?.let { throw it }
            gate?.await()
            return response ?: error("FakeTransport has no response")
        }

        override suspend fun cancelActive() {
            cancelRequests.incrementAndGet()
        }
    }

    private class FakeCache(
        current: ByteArray? = null,
        previous: ByteArray? = null,
    ) : CatalogCacheStore {
        var current: ByteArray? = current
        var previous: ByteArray? = previous

        override suspend fun readCurrent(): ByteArray? = current
        override suspend fun readPrevious(): ByteArray? = previous
        override suspend fun promoteAuthenticated(next: ByteArray, trustedPrevious: ByteArray?) {
            if (trustedPrevious != null) previous = trustedPrevious
            current = next
        }

        override suspend fun clear() {
            current = null
            previous = null
        }
    }

    private class FakePreferences(
        updatesEnabled: Boolean = false,
        highestVersion: Long? = null,
        highestDigest: String? = null,
        lastCheckEpochSecond: Long? = null,
        lastFailureEpochSecond: Long? = null,
    ) : CatalogPreferencesStore {
        private var snapshot = CatalogPrefsSnapshot(
            updatesEnabled = updatesEnabled,
            highestVersion = highestVersion,
            highestDigest = highestDigest,
            lastCheckEpochSecond = lastCheckEpochSecond,
            lastFailureEpochSecond = lastFailureEpochSecond,
        )

        /** Optional barrier that suspends every load() until completed (F03 race test). */
        var loadGate: CompletableDeferred<Unit>? = null

        fun snapshot(): CatalogPrefsSnapshot = snapshot
        fun setOptInBlocking(enabled: Boolean) {
            snapshot = snapshot.copy(updatesEnabled = enabled)
        }

        override suspend fun load(): CatalogPrefsSnapshot {
            loadGate?.await()
            return snapshot
        }

        override suspend fun setOptIn(enabled: Boolean) = setOptInBlocking(enabled)
        override suspend fun recordAcceptance(version: Long, digest: String) {
            snapshot = snapshot.copy(highestVersion = version, highestDigest = digest)
        }

        override suspend fun recordCheckSuccess(epochSecond: Long) {
            snapshot = snapshot.copy(lastCheckEpochSecond = epochSecond, lastFailureEpochSecond = null)
        }

        override suspend fun recordFailure(epochSecond: Long) {
            snapshot = snapshot.copy(lastFailureEpochSecond = epochSecond)
        }
    }

    // ---- signing helpers (ephemeral test key only) ----

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun spkiOf(keyPair: java.security.KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun signedCatalogV1(): ByteArray = signedCatalog(version = 1)

    private fun signedCatalog(version: Long, pattern: String = "starbucks"): ByteArray {
        val payload = """{"schemaVersion":1,"catalogVersion":$version,"issuedAt":"2026-10-16T12:00:00Z","entries":[""" +
            """{"pattern":"$pattern","patternType":"SUBSTRING","categoryId":"cat-coffee"}]}"""
        return signedEnvelope(payload)
    }

    private fun signedEnvelope(payloadJson: String): ByteArray {
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(CONTEXT_PREFIX)
            update(payloadBytes)
        }.sign()
        return """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"${Base64.getEncoder().encodeToString(payloadBytes)}","signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun payloadOf(envelope: ByteArray): ByteArray =
        Base64.getDecoder().decode(
            envelope.toString(Charsets.UTF_8)
                .removePrefix("""{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"""")
                .substringBefore("""","signature"""),
        )
}
