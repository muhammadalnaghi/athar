package com.athar.di

import com.athar.core.domain.repo.FinancialRecordingPreferences
import com.athar.ingestion.FinancialRecordingRollout
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WM033 recording-only binding: the Settings control and the canonical
 * purchase path share the rollout singleton as one source of truth.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class FinancialRecordingPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindFinancialRecordingPreferences(
        impl: FinancialRecordingRollout,
    ): FinancialRecordingPreferences
}
