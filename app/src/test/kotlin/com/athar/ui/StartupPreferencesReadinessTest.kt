package com.athar.ui

import androidx.lifecycle.ViewModelStore
import com.athar.core.domain.repo.CsvImportTrigger
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.ui.onboarding.OnboardingViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupPreferencesReadinessTest {
    @Test
    fun `onboarding is unresolved until an actual preference is read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val completion = MutableSharedFlow<Boolean>(replay = 1)
            val prefs = mockk<UserPreferencesRepository>()
            every { prefs.onboardingComplete() } returns completion
            val viewModel = OnboardingViewModel(prefs, mockk<CsvImportTrigger>())
            store.put("onboarding", viewModel)

            assertThat(viewModel.onboardingComplete.value).isNull()
            runCurrent()
            assertThat(viewModel.onboardingComplete.value).isNull()
            completion.emit(false)
            runCurrent()
            assertThat(viewModel.onboardingComplete.value).isFalse()
            completion.emit(true)
            runCurrent()
            assertThat(viewModel.onboardingComplete.value).isTrue()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `display preferences never expose guessed defaults while loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val hijri = MutableSharedFlow<Boolean>(replay = 1)
            val currency = MutableSharedFlow<String>(replay = 1)
            val prefs = mockk<UserPreferencesRepository>()
            every { prefs.hijriEnabled() } returns hijri
            every { prefs.displayCurrency() } returns currency
            val viewModel = AppPrefsViewModel(prefs)
            store.put("display", viewModel)

            runCurrent()
            assertThat(viewModel.hijriEnabled.value).isNull()
            assertThat(viewModel.displayCurrency.value).isNull()
            hijri.emit(true)
            runCurrent()
            assertThat(viewModel.hijriEnabled.value).isTrue()
            assertThat(viewModel.displayCurrency.value).isNull()
            currency.emit("KWD")
            runCurrent()
            assertThat(viewModel.displayCurrency.value).isEqualTo("KWD")
            currency.emit("JPY")
            runCurrent()
            assertThat(viewModel.displayCurrency.value).isEqualTo("JPY")
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
