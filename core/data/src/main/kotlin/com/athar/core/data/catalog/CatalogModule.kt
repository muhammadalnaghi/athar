package com.athar.core.data.catalog

import android.content.Context
import android.content.pm.PackageManager
import com.athar.core.domain.repo.MerchantCatalogRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock
import javax.inject.Singleton

/**
 * Focused Hilt wiring for the signed shared merchant catalog (ENH-009).
 * Kept out of DataModule so the catalog's seams stay cohesive; the Room
 * migration registration remains in DatabaseModule.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object CatalogModule {

    @Provides
    @Singleton
    fun provideCatalogTransport(impl: HttpCatalogTransport): CatalogTransport = impl

    @Provides
    @Singleton
    fun provideCatalogCacheStore(impl: AtomicFileCatalogCacheStore): CatalogCacheStore = impl

    @Provides
    @Singleton
    fun provideCatalogConsentFence(impl: AtomicFileCatalogConsentFence): CatalogConsentFence = impl

    @Provides
    @Singleton
    fun provideCatalogPreferences(impl: DataStoreCatalogPreferences): CatalogPreferencesStore = impl

    @Provides
    @Singleton
    fun provideCatalogSignatureVerifier(): CatalogSignatureVerifier =
        JcaCatalogSignatureVerifier(ProductionCatalogTrust.PUBLIC_KEY_SPKI_BASE64)

    @Provides
    @Singleton
    fun provideCatalogEnvelopeParser(
        verifier: CatalogSignatureVerifier,
        clock: Clock,
    ): CatalogEnvelopeParser = CatalogEnvelopeParser(verifier, clock)

    @Provides
    @Singleton
    fun provideMerchantCatalogRepository(
        transport: CatalogTransport,
        cache: CatalogCacheStore,
        preferences: CatalogPreferencesStore,
        consentFence: CatalogConsentFence,
        envelopeParser: CatalogEnvelopeParser,
        clock: Clock,
        @ApplicationContext context: Context,
    ): MerchantCatalogRepository = MerchantCatalogRepositoryImpl(
        transport = transport,
        cache = cache,
        preferences = preferences,
        consentFence = consentFence,
        envelopeParser = envelopeParser,
        // Install-time permission: constant per install/flavor. StoreSafe is
        // offline from the first process start, not just from the UI.
        networkSupported = context.checkSelfPermission(android.Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED,
        clock = clock,
    )
}
