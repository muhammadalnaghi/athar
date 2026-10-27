package com.athar.startup

import com.athar.AtharApplication
import com.athar.MainActivity
import com.athar.core.data.AppDataInitializer
import com.athar.core.domain.repo.MerchantCatalogRepository
import com.athar.runDeferredStartupJobs
import com.athar.runDeferredCatalogJobs
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType

/**
 * WM056 regression guards for deferred startup wiring.
 *
 * Pre-WM056, `AtharApplication` field-injected `AppDataInitializer` directly, so
 * Hilt's inject() — which runs on the main thread before the app's own
 * onCreate body — synchronously constructed the seed → DAO → AtharDatabase
 * chain: System.loadLibrary("sqlcipher"), DbKeyManager.getOrCreateDbKey
 * (Android Keystore) and SupportOpenHelperFactory, all ahead of the first
 * frame. The `scope.launch` around initialize() was too late: construction had
 * already happened at injection time.
 *
 * These tests pin the fix at two levels:
 * 1. structural — the injected fields must stay dagger.Lazy, so re-introducing
 *    eager injection fails the build's test run;
 * 2. behavioral — the extracted background sequence constructs the initializer
 *    exactly once, seeds, then schedules recurring work, and propagates
 *    failures/cancellation to the scope's CoroutineExceptionHandler without
 *    ever scheduling after a failure (semantics identical to the pre-fix
 *    inline block).
 *
 * What these JVM tests cannot prove: actual device latency. Cold-start
 * timings are verified by the parent on the emulator.
 */
class DeferredStartup056Test {

    @Test
    fun `application injects AppDataInitializer as dagger Lazy so injection cannot build the seed DAO SQLCipher Keystore chain on the main thread`() {
        val field = AtharApplication::class.java.getDeclaredField("dataInitializer")
        assertThat(field.type).isEqualTo(dagger.Lazy::class.java)
        val generic = field.genericType as ParameterizedType
        assertThat(generic.actualTypeArguments.single()).isEqualTo(AppDataInitializer::class.java)
        assertThat(AtharApplication::class.java.declaredFields.map { it.type })
            .doesNotContain(AppDataInitializer::class.java)
    }

    @Test
    fun `main activity defers catalog construction during field injection`() {
        val field = MainActivity::class.java.getDeclaredField("merchantCatalog")
        assertThat(field.type).isEqualTo(dagger.Lazy::class.java)
        val generic = field.genericType as ParameterizedType
        assertThat(generic.actualTypeArguments.single()).isEqualTo(MerchantCatalogRepository::class.java)
        assertThat(MainActivity::class.java.declaredFields.map { it.type })
            .doesNotContain(MerchantCatalogRepository::class.java)
    }

    @Test
    fun `deferred startup constructs the initializer once, seeds, then schedules recurring work`() =
        runBlocking<Unit> {
            val events = mutableListOf<String>()
            val initializer = mockk<AppDataInitializer> {
                coEvery { initialize(any()) } answers { events += "initialize:${firstArg<Boolean>()}" }
            }
            val lazy = dagger.Lazy<AppDataInitializer> { events += "construct"; initializer }

            runDeferredStartupJobs(lazy, seedTmoap = true) { events += "schedule" }

            assertThat(events).containsExactly("construct", "initialize:true", "schedule").inOrder()
        }

    @Test
    fun `initializer construction and seeding run off the calling thread`() = runBlocking<Unit> {
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        val initializer = mockk<AppDataInitializer> {
            coEvery { initialize(any()) } answers { threads += Thread.currentThread() }
        }
        runDeferredStartupJobs(dagger.Lazy { threads += Thread.currentThread(); initializer }, false) {}
        assertThat(threads).hasSize(2)
        assertThat(threads.all { it !== caller }).isTrue()
    }

    @Test
    fun `catalog construction initialization and refresh run on IO in order`() = runBlocking<Unit> {
        val caller = Thread.currentThread()
        val events = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        fun record(phase: String) { events += phase; threads += Thread.currentThread() }
        val catalog = mockk<MerchantCatalogRepository> {
            coEvery { initialize() } answers { record("initialize") }
            coEvery { refresh(any()) } answers { record("refresh") }
        }
        runDeferredCatalogJobs(dagger.Lazy { record("construct"); catalog })
        assertThat(events).containsExactly("construct", "initialize", "refresh").inOrder()
        assertThat(threads.all { it !== caller }).isTrue()
    }

    @Test
    fun `catalog initialization failure propagates without refresh`() = runBlocking<Unit> {
        val catalog = mockk<MerchantCatalogRepository>(relaxed = true) {
            coEvery { initialize() } throws IllegalStateException("unavailable")
        }
        var failure: Throwable? = null
        try { runDeferredCatalogJobs(dagger.Lazy { catalog }) } catch (t: Throwable) { failure = t }
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { catalog.refresh(any()) }
    }

    @Test
    fun `catalog cancellation propagates without refresh`() = runBlocking<Unit> {
        val catalog = mockk<MerchantCatalogRepository>(relaxed = true) {
            coEvery { initialize() } throws CancellationException("cancelled")
        }
        var failure: Throwable? = null
        try { runDeferredCatalogJobs(dagger.Lazy { catalog }) } catch (t: Throwable) { failure = t }
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { catalog.refresh(any()) }
    }

    @Test
    fun `deferred startup propagates seeding failure to the startup error handler and never schedules recurring work`() =
        runBlocking<Unit> {
            val initializer = mockk<AppDataInitializer> {
                coEvery { initialize(any()) } throws IllegalStateException("db init failed")
            }
            var scheduled = false
            var thrown: Throwable? = null
            try {
                runDeferredStartupJobs(dagger.Lazy { initializer }, seedTmoap = false) { scheduled = true }
            } catch (t: Throwable) {
                thrown = t
            }
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(scheduled).isFalse()
        }

    @Test
    fun `deferred startup propagates cancellation and never schedules recurring work`() =
        runBlocking<Unit> {
            val initializer = mockk<AppDataInitializer> {
                coEvery { initialize(any()) } throws CancellationException("scope cancelled")
            }
            var scheduled = false
            var thrown: Throwable? = null
            try {
                runDeferredStartupJobs(dagger.Lazy { initializer }, seedTmoap = false) { scheduled = true }
            } catch (t: Throwable) {
                thrown = t
            }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            assertThat(scheduled).isFalse()
        }
}
