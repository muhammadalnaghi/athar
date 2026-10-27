package com.athar.core.data.di

import android.content.Context
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.db.crypto.DbKeyManager
import com.athar.core.data.db.dao.AccountDao
import com.athar.core.data.db.dao.ActivityLogDao
import com.athar.core.data.db.dao.CategoryDao
import com.athar.core.data.db.dao.CategoryRuleDao
import com.athar.core.data.db.dao.InvestmentDao
import com.athar.core.data.db.dao.SmsMessageDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.dao.TransactionReceiptDao
import com.athar.core.data.db.dao.UserTemplateDao
import com.athar.core.data.db.dao.WishlistDao
import com.athar.core.data.backup.WidgetRefreshingBackupRepository
import com.athar.core.data.csv.BudgetTargetImporter
import com.athar.core.data.csv.CommunityRulesShareExporter
import com.athar.core.data.csv.CsvExporter
import com.athar.core.data.csv.CsvImporter
import com.athar.core.data.csv.InvestmentImporter
import com.athar.core.data.csv.MerchantBulkExporter
import com.athar.core.data.csv.MerchantBulkImporter
import com.athar.core.data.csv.WishlistImporter
import com.athar.core.data.report.TaxPdfExporter
import com.athar.core.data.repo.AccountRepositoryImpl
import com.athar.core.data.repo.ActivityLogRepositoryImpl
import com.athar.core.data.repo.CategoryRepositoryImpl
import com.athar.core.data.repo.CategoryRuleRepositoryImpl
import com.athar.core.data.repo.InvestmentRepositoryImpl
import com.athar.core.data.repo.SmsAuditRepositoryImpl
import com.athar.core.data.prefs.UserPreferencesRepositoryImpl
import com.athar.core.data.repo.TransactionRepositoryImpl
import com.athar.core.data.repo.ReceiptAttachmentRepositoryImpl
import com.athar.core.data.repo.UserTemplateRepositoryImpl
import com.athar.core.data.repo.WishlistRepositoryImpl
import com.athar.core.data.planning.JsonReaderSavedFinancialPlanPayloadGuard
import com.athar.core.data.planning.RoomSavedFinancialPlanTransactionRunner
import com.athar.core.data.planning.SavedFinancialPlanDao
import com.athar.core.data.planning.SavedFinancialPlanPayloadGuard
import com.athar.core.data.planning.WidgetRefreshingSavedPlanRepository
import com.athar.core.data.planning.SavedFinancialPlanTransactionRunner
import com.athar.core.data.support.SupportDiagnosticsExporter
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.ActivityLogRepository
import com.athar.core.domain.repo.BackupRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.BudgetTargetImportTrigger
import com.athar.core.domain.repo.CommunityRulesShareTrigger
import com.athar.core.domain.repo.CsvExportTrigger
import com.athar.core.domain.repo.CsvImportTrigger
import com.athar.core.domain.repo.InvestmentImportTrigger
import com.athar.core.domain.repo.MerchantBulkExportTrigger
import com.athar.core.domain.repo.MerchantBulkImportTrigger
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.SupportDiagnosticsExportTrigger
import com.athar.core.domain.repo.TaxExportTrigger
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.ReceiptAttachmentRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.core.domain.repo.UserTemplateRepository
import com.athar.core.domain.repo.WishlistImportTrigger
import com.athar.core.domain.repo.WishlistRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        dbKeyManager: DbKeyManager,
    ): AtharDatabase {
        // `net.zetetic:sqlcipher-android` 4.6.x ships `libsqlcipher.so` but NO class in the AAR
        // calls `System.loadLibrary`, so consumers must do it explicitly before the first
        // SQLiteConnection.nativeOpen — otherwise the JNI lookup fails with UnsatisfiedLinkError.
        // (Verified by `javap`-scanning the AAR; the 4.6 series dropped the auto-load that
        // the older `android-database-sqlcipher` artifact had.)
        return runCatching {
            System.loadLibrary("sqlcipher")
            // WM028-13B0: no startup deletion and no destructive migration fallback.
            // A retained database without key material fails closed inside
            // getOrCreateDbKey before anything is generated or written; an
            // unsupported open (e.g. a downgrade) fails in Room and leaves every
            // artifact in place for explicit recovery. Master Brief §5.3.
            val key = dbKeyManager.getOrCreateDbKey()
            val factory = SupportOpenHelperFactory(key)
            atharDatabaseBuilder(context, AtharDatabase.NAME)
                .openHelperFactory(factory)
                .build()
        }.onFailure { t ->
            Timber.e(t, "Failed to open Athar database")
            runCatching {
                File(context.filesDir, "crash.log").appendText(
                    "===== DB INIT FAILURE =====\n" +
                        "${t.javaClass.name}: ${t.message}\n" +
                        t.stackTraceToString() +
                        "\n",
                )
            }
        }.getOrThrow()
    }

    @Provides fun provideUserTemplateDao(db: AtharDatabase): UserTemplateDao = db.userTemplateDao()
    @Provides fun provideAccountDao(db: AtharDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: AtharDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: AtharDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideTransactionReceiptDao(db: AtharDatabase): TransactionReceiptDao = db.transactionReceiptDao()
    @Provides fun provideCategoryRuleDao(db: AtharDatabase): CategoryRuleDao = db.categoryRuleDao()
    @Provides fun provideWishlistDao(db: AtharDatabase): WishlistDao = db.wishlistDao()
    @Provides fun provideInvestmentDao(db: AtharDatabase): InvestmentDao = db.investmentDao()
    @Provides fun provideSmsMessageDao(db: AtharDatabase): SmsMessageDao = db.smsMessageDao()
    @Provides fun provideActivityLogDao(db: AtharDatabase): ActivityLogDao = db.activityLogDao()
    @Provides fun provideRecurringRuleDao(db: AtharDatabase): com.athar.core.data.db.dao.RecurringRuleDao = db.recurringRuleDao()
    @Provides fun provideSavedFinancialPlanDao(db: AtharDatabase): SavedFinancialPlanDao = db.savedFinancialPlanDao()

    /** Saved-plan writes are atomic via Room's real suspend transaction. */
    @Provides
    @Singleton
    fun provideSavedFinancialPlanTransactionRunner(
        db: AtharDatabase,
    ): SavedFinancialPlanTransactionRunner = RoomSavedFinancialPlanTransactionRunner(db)

    /** Saved-plan payloads are guarded by the real platform JsonReader walk. */
    @Provides
    @Singleton
    fun provideSavedFinancialPlanPayloadGuard(): SavedFinancialPlanPayloadGuard =
        JsonReaderSavedFinancialPlanPayloadGuard

    @Provides @Singleton fun provideClock(): Clock = Clock.System
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds @Singleton
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds @Singleton
    abstract fun bindReceiptAttachmentRepository(impl: ReceiptAttachmentRepositoryImpl): ReceiptAttachmentRepository

    @Binds @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds @Singleton
    abstract fun bindCategoryRuleRepository(impl: CategoryRuleRepositoryImpl): CategoryRuleRepository

    @Binds @Singleton
    abstract fun bindBackupRepository(impl: WidgetRefreshingBackupRepository): BackupRepository

    @Binds @Singleton
    abstract fun bindWishlistRepository(impl: WishlistRepositoryImpl): WishlistRepository

    @Binds @Singleton
    abstract fun bindInvestmentRepository(impl: InvestmentRepositoryImpl): InvestmentRepository

    @Binds @Singleton
    abstract fun bindRecurringRuleRepository(
        impl: com.athar.core.data.repo.RecurringRuleRepositoryImpl,
    ): com.athar.core.domain.repo.RecurringRuleRepository

    @Binds @Singleton
    abstract fun bindRecurringSuggestionRepository(
        impl: com.athar.core.data.repo.RecurringSuggestionRepositoryImpl,
    ): com.athar.core.domain.repo.RecurringSuggestionRepository

    @Binds @Singleton
    abstract fun bindUserPreferencesRepository(impl: UserPreferencesRepositoryImpl): UserPreferencesRepository

    @Binds @Singleton
    abstract fun bindSmsImportPreferencesRepository(
        impl: com.athar.core.data.prefs.SmsImportPreferencesRepositoryImpl,
    ): com.athar.core.domain.repo.SmsImportPreferencesRepository

    @Binds @Singleton
    abstract fun bindIncomeSourcePreferencesRepository(
        impl: com.athar.core.data.prefs.IncomeSourcePreferencesRepositoryImpl,
    ): com.athar.core.domain.repo.IncomeSourcePreferencesRepository

    @Binds @Singleton
    abstract fun bindCsvImportTrigger(impl: CsvImporter): CsvImportTrigger

    @Binds @Singleton
    abstract fun bindBudgetTargetImportTrigger(impl: BudgetTargetImporter): BudgetTargetImportTrigger

    @Binds @Singleton
    abstract fun bindWishlistImportTrigger(impl: WishlistImporter): WishlistImportTrigger

    @Binds @Singleton
    abstract fun bindInvestmentImportTrigger(impl: InvestmentImporter): InvestmentImportTrigger

    @Binds @Singleton
    abstract fun bindSmsAuditRepository(impl: SmsAuditRepositoryImpl): SmsAuditRepository

    @Binds @Singleton
    abstract fun bindCsvExportTrigger(impl: CsvExporter): CsvExportTrigger

    @Binds @Singleton
    abstract fun bindMerchantBulkExportTrigger(impl: MerchantBulkExporter): MerchantBulkExportTrigger

    @Binds @Singleton
    abstract fun bindMerchantBulkImportTrigger(impl: MerchantBulkImporter): MerchantBulkImportTrigger

    @Binds @Singleton
    abstract fun bindCommunityRulesShareTrigger(impl: CommunityRulesShareExporter): CommunityRulesShareTrigger

    @Binds @Singleton
    abstract fun bindTaxExportTrigger(impl: TaxPdfExporter): TaxExportTrigger

    @Binds @Singleton
    abstract fun bindSupportDiagnosticsExportTrigger(impl: SupportDiagnosticsExporter): SupportDiagnosticsExportTrigger

    @Binds @Singleton
    abstract fun bindActivityLogRepository(impl: ActivityLogRepositoryImpl): ActivityLogRepository

    @Binds @Singleton
    abstract fun bindUserTemplateRepository(impl: UserTemplateRepositoryImpl): UserTemplateRepository

    @Binds @Singleton
    abstract fun bindSavedFinancialPlanRepository(
        impl: WidgetRefreshingSavedPlanRepository,
    ): SavedFinancialPlanRepository
}
