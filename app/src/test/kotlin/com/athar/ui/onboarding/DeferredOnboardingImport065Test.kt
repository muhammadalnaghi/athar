package com.athar.ui.onboarding

import androidx.lifecycle.ViewModelStore
import com.athar.core.domain.repo.CsvImportResult
import com.athar.core.domain.repo.CsvImportTrigger
import com.athar.core.domain.repo.UserPreferencesRepository
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredOnboardingImport065Test {
    @Test
    fun importerDependencyRemainsLazy() {
        assertThat(OnboardingViewModel::class.java.getDeclaredField("csvImporter").type)
            .isEqualTo(dagger.Lazy::class.java)
    }

    @Test
    fun openingConstructionImportAndClosureRunOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        val phases = mutableListOf<String>()
        fun record(phase: String) { phases += phase; threads += Thread.currentThread() }
        val input = ClosingInput { record("close") }
        val expected = CsvImportResult.Done(3, 1)
        val trigger = mockk<CsvImportTrigger>()
        coEvery { trigger.import(input) } answers { record("import"); expected }
        val result = runDeferredOnboardingImport(Lazy { record("construct"); trigger }) {
            record("open")
            input
        }
        assertThat(result).isSameInstanceAs(expected)
        assertThat(phases).containsExactly("open", "construct", "import", "close").inOrder()
        assertThat(threads.all { it !== caller }).isTrue()
        assertThat(input.closes).isEqualTo(1)
    }

    @Test
    fun missingOrUnopenableDocumentsNeverConstructImporter() = runBlocking {
        var constructions = 0
        val lazy = Lazy<CsvImportTrigger> { constructions++; error("must not construct") }
        val missing = runDeferredOnboardingImport(lazy) { null }
        val inaccessible = runDeferredOnboardingImport(lazy) { throw IOException("private-provider-path") }
        assertThat(missing).isEqualTo(CsvImportResult.Failed("Couldn't open CSV file."))
        assertThat(inaccessible).isEqualTo(missing)
        assertThat(constructions).isEqualTo(0)
    }

    @Test
    fun declaredFailureIsPreservedAndInputIsClosed() = runBlocking {
        val input = ClosingInput()
        val expected = CsvImportResult.Failed("Synthetic declared failure")
        val trigger = mockk<CsvImportTrigger>()
        coEvery { trigger.import(input) } returns expected
        assertThat(runDeferredOnboardingImport(Lazy { trigger }) { input }).isSameInstanceAs(expected)
        assertThat(input.closes).isEqualTo(1)
    }

    @Test
    fun constructionImportAndCloseExceptionsAreSafeAndCloseInput() = runBlocking {
        for (phase in listOf("construct", "import", "close")) {
            val input = ClosingInput { if (phase == "close") throw IOException("private-close-path") }
            val trigger = mockk<CsvImportTrigger>()
            coEvery { trigger.import(input) } answers {
                if (phase == "import") throw IOException("private-import-path")
                CsvImportResult.Done(0, 0)
            }
            val lazy = Lazy {
                if (phase == "construct") throw IllegalStateException("private-construction-path")
                trigger
            }
            val result = runDeferredOnboardingImport(lazy) { input }
            assertThat(result).isEqualTo(CsvImportResult.Failed("Couldn't read CSV file."))
            assertThat(input.closes).isEqualTo(1)
        }
    }

    @Test
    fun cancellationIsRethrownAndOpenedInputIsClosed() {
        for (phase in listOf("open", "construct", "import")) {
            val input = ClosingInput()
            val cancellation = CancellationException("synthetic cancellation")
            val trigger = mockk<CsvImportTrigger>()
            coEvery { trigger.import(input) } throws cancellation
            val failure = assertThrows<CancellationException> {
                runBlocking {
                    runDeferredOnboardingImport(Lazy {
                        if (phase == "construct") throw cancellation
                        trigger
                    }) {
                        if (phase == "open") throw cancellation
                        input
                    }
                }
            }
            // Coroutine stack-trace recovery may copy CancellationException while
            // preserving the original as its cause; it must never become a result.
            assertThat(failure.message).isEqualTo(cancellation.message)
            assertThat(generateSequence<Throwable>(failure) { it.cause }.any { it === cancellation }).isTrue()
            assertThat(input.closes).isEqualTo(if (phase == "open") 0 else 1)
        }
    }

    @Test
    fun preferenceReadinessNeverRequestsUnusedImporter() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var constructions = 0
            val complete = MutableSharedFlow<Boolean>(replay = 1)
            val prefs = mockk<UserPreferencesRepository>()
            every { prefs.onboardingComplete() } returns complete
            val vm = OnboardingViewModel(prefs, Lazy<CsvImportTrigger> {
                constructions++
                error("startup must not request CSV importer")
            })
            store.put("onboarding", vm)
            assertThat(vm.onboardingComplete.value).isNull()
            runCurrent()
            assertThat(vm.onboardingComplete.value).isNull()
            complete.emit(true)
            runCurrent()
            assertThat(vm.onboardingComplete.value).isTrue()
            assertThat(constructions).isEqualTo(0)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class ClosingInput(private val onClose: () -> Unit = {}) : ByteArrayInputStream(byteArrayOf()) {
        var closes = 0
        override fun close() {
            closes++
            super.close()
            onClose()
        }
    }
}
