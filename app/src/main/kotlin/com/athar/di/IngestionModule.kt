package com.athar.di

import com.athar.core.domain.model.RawIngestDispatcher
import com.athar.core.domain.repo.SmsBackfillTrigger
import com.athar.core.domain.repo.SmsImportController
import com.athar.core.domain.repo.UserTemplateRepository
import com.athar.ingestion.QueuedRawIngestDispatcher
import com.athar.ingestion.SmsBackfillService
import com.athar.ingestion.SmsImportService
import com.athar.ingestion.smsparser.BuiltInSmsTemplateRegistry
import com.athar.ingestion.smsparser.SmsParser
import com.athar.ingestion.smsparser.user.HybridSmsParser
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object IngestionModule {

    @Provides
    @Singleton
    fun provideHybridSmsParser(
        userTemplateRepository: UserTemplateRepository,
    ): HybridSmsParser {
        val parser = HybridSmsParser(BuiltInSmsTemplateRegistry.templates())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        userTemplateRepository.observeAll()
            .onEach { templates -> parser.updateUserTemplates(templates) }
            .launchIn(scope)
        return parser
    }

    @Provides
    @Singleton
    fun provideSmsParser(hybrid: HybridSmsParser): SmsParser = hybrid

}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class IngestionBindingsModule {
    @Binds @Singleton
    abstract fun bindRawIngestDispatcher(impl: QueuedRawIngestDispatcher): RawIngestDispatcher

    @Binds @Singleton
    abstract fun bindSmsBackfillTrigger(impl: SmsBackfillService): SmsBackfillTrigger

    @Binds @Singleton
    abstract fun bindSmsImportController(impl: SmsImportService): SmsImportController
}
