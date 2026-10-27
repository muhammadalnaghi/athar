package com.athar.core.data.position

import com.athar.core.domain.repo.DeclaredPositionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Same-package Hilt binding for the WM033 declared-position read side. The
 * existing `SavedFinancialPlanRepository` binding in `core.data.di` is reused
 * as the storage source; nothing in the global DI graph is modified.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DeclaredPositionDataModule {

    @Binds
    @Singleton
    internal abstract fun bindDeclaredPositionRepository(
        impl: DeclaredPositionRepositoryImpl,
    ): DeclaredPositionRepository
}
