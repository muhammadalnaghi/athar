package com.athar.core.data.backup

import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.athar.core.data.db.entity.WishlistEntity
import com.athar.core.data.ledger.backup.CanonicalBackupSection
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * Wire format for an Athar backup. Mirrors entities but is decoupled from Room
 * annotations so we can evolve the DB schema without breaking restores.
 *
 * Versioned: bump [version] when adding/removing fields. Older readers should
 * tolerate unknown fields; newer readers handle older payloads via migrations
 * in [BackupMigration].
 */
@Serializable
internal data class BackupSnapshot(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Instant,
    val accounts: List<BackupAccount>,
    val categories: List<BackupCategory>,
    val transactions: List<BackupTransaction>,
    val categoryRules: List<BackupCategoryRule>,
    val wishlist: List<BackupWishlistItem>,
    val investmentPools: List<BackupInvestmentPool>,
    val investmentContributions: List<BackupInvestmentContribution>,
    val smsAudit: List<BackupSmsMessage>,
    val receiptAttachments: List<BackupReceiptAttachment> = emptyList(),
    // v4 (WM028-13C3): the canonical ledger travels with the legacy tables. Null
    // is only legal for v1–v3 payloads; v4 requires it present (possibly
    // all-empty) so canonical data is never silently omitted or silently mixed.
    val canonicalLedger: CanonicalBackupSection? = null,
    // Required for the unpublished complete v4 envelope; legacy v1–v3 decode as null.
    val auxiliaryTables: BackupAuxiliarySection? = null,
) {
    companion object {
        // v2 (G-4): BackupAccount gained openingBalanceMinor/Currency, notes, sortOrder,
        // archivedAt, updatedAt. All optional with safe defaults so v1 payloads still restore.
        // v3 (G-8): encrypted receipt attachments are included in backups.
        // v4 (WM028-13C3): canonicalLedger section. Version handling is explicit and
        // fail-closed — see BackupRestoreValidator (missing/unsupported versions fail).
        const val CURRENT_VERSION = 4
    }
}

@Serializable
internal data class BackupAccount(
    val id: String, val name: String, val type: String, val currency: String,
    val smsSenders: String, val active: Boolean, val createdAt: Instant,
    val openingBalanceMinor: Long = 0L,
    val openingBalanceCurrency: String = "SAR",
    val notes: String? = null,
    val sortOrder: Int = 0,
    val archivedAt: Instant? = null,
    val updatedAt: Instant? = null,
)

@Serializable
internal data class BackupCategory(
    val id: String, val name: String, val nameAr: String, val kind: String,
    val icon: String?, val monthlyTargetMinor: Long?, val currency: String,
    val archived: Boolean, val sortOrder: Int,
)

@Serializable
internal data class BackupTransaction(
    val id: String, val accountId: String, val type: String, val amountMinor: Long,
    val currency: String, val date: LocalDate, val occurredAt: Instant?,
    val merchant: String, val merchantNormalized: String, val categoryId: String?,
    val notes: String?, val source: String, val sourceRefId: String?,
    val status: String, val confidence: Float?,
    val createdAt: Instant, val updatedAt: Instant,
    // Optional + backwards compatible: older payloads restore as null (unknown).
    val categorySource: String? = null,
)

@Serializable
internal data class BackupCategoryRule(
    val id: String, val pattern: String, val patternType: String, val categoryId: String,
    val priority: Int, val learnedFromUser: Boolean, val createdAt: Instant,
)

@Serializable
internal data class BackupWishlistItem(
    val id: String, val name: String, val costMinor: Long, val currentSavedMinor: Long,
    val currency: String, val desiredMonths: Int?, val startYear: Int, val startMonth: Int,
    val notes: String?,
)

@Serializable
internal data class BackupInvestmentPool(
    val id: String, val name: String, val period: String,
    val totalReturnMinor: Long, val currency: String,
)

@Serializable
internal data class BackupInvestmentContribution(
    val id: String, val poolId: String, val ownerName: String,
    val amountMinor: Long, val currency: String,
)

@Serializable
internal data class BackupSmsMessage(
    val id: String, val sender: String, val body: String, val receivedAt: Instant,
    val parsedTransactionId: String?, val parseStatus: String, val parseError: String?,
)

@Serializable
internal data class BackupReceiptAttachment(
    val id: String,
    val transactionId: String,
    val mimeType: String,
    val originalName: String?,
    val sizeBytes: Long,
    val payloadBase64: String,
    val createdAt: Instant,
)

internal fun AccountEntity.toBackup(): BackupAccount = BackupAccount(
    id = id, name = name, type = type, currency = currency,
    smsSenders = smsSenders, active = active, createdAt = createdAt,
    openingBalanceMinor = openingBalanceMinor,
    openingBalanceCurrency = openingBalanceCurrency,
    notes = notes, sortOrder = sortOrder, archivedAt = archivedAt, updatedAt = updatedAt,
)
internal fun CategoryEntity.toBackup(): BackupCategory = BackupCategory(id, name, nameAr, kind, icon, monthlyTargetMinor, currency, archived, sortOrder)
internal fun TransactionEntity.toBackup(): BackupTransaction = BackupTransaction(id, accountId, type, amountMinor, currency, date, occurredAt, merchant, merchantNormalized, categoryId, notes, source, sourceRefId, status, confidence, createdAt, updatedAt, categorySource)
internal fun CategoryRuleEntity.toBackup(): BackupCategoryRule = BackupCategoryRule(id, pattern, patternType, categoryId, priority, learnedFromUser, createdAt)
internal fun WishlistEntity.toBackup(): BackupWishlistItem = BackupWishlistItem(id, name, costMinor, currentSavedMinor, currency, desiredMonths, startYear, startMonth, notes)
internal fun InvestmentPoolEntity.toBackup(): BackupInvestmentPool = BackupInvestmentPool(id, name, period, totalReturnMinor, currency)
internal fun InvestmentContributionEntity.toBackup(): BackupInvestmentContribution = BackupInvestmentContribution(id, poolId, ownerName, amountMinor, currency)
internal fun SmsMessageEntity.toBackup(): BackupSmsMessage = BackupSmsMessage(id, sender, body, receivedAt, parsedTransactionId, parseStatus, parseError)
internal fun TransactionReceiptEntity.toBackup(): BackupReceiptAttachment = BackupReceiptAttachment(
    id = id,
    transactionId = transactionId,
    mimeType = mimeType,
    originalName = originalName,
    sizeBytes = sizeBytes,
    payloadBase64 = Base64.getEncoder().encodeToString(payload),
    createdAt = createdAt,
)

internal fun BackupAccount.toEntity(): AccountEntity = AccountEntity(
    id = id, name = name, type = type, currency = currency,
    openingBalanceMinor = openingBalanceMinor,
    openingBalanceCurrency = openingBalanceCurrency,
    smsSenders = smsSenders, notes = notes, sortOrder = sortOrder,
    active = active && archivedAt == null,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt ?: createdAt,
)
internal fun BackupCategory.toEntity(): CategoryEntity = CategoryEntity(id, name, nameAr, kind, icon, monthlyTargetMinor, currency, archived, sortOrder)
internal fun BackupTransaction.toEntity(): TransactionEntity = TransactionEntity(id, accountId, type, amountMinor, currency, date, occurredAt, merchant, merchantNormalized, categoryId, notes, source, sourceRefId, status, confidence, createdAt, updatedAt, categorySource)
internal fun BackupCategoryRule.toEntity(): CategoryRuleEntity = CategoryRuleEntity(id, pattern, patternType, categoryId, priority, learnedFromUser, createdAt)
internal fun BackupWishlistItem.toEntity(): WishlistEntity = WishlistEntity(id, name, costMinor, currentSavedMinor, currency, desiredMonths, startYear, startMonth, notes)
internal fun BackupInvestmentPool.toEntity(): InvestmentPoolEntity = InvestmentPoolEntity(id, name, period, totalReturnMinor, currency)
internal fun BackupInvestmentContribution.toEntity(): InvestmentContributionEntity = InvestmentContributionEntity(id, poolId, ownerName, amountMinor, currency)
internal fun BackupSmsMessage.toEntity(): SmsMessageEntity = SmsMessageEntity(id, sender, body, receivedAt, parsedTransactionId, parseStatus, parseError)
internal fun BackupReceiptAttachment.toEntity(): TransactionReceiptEntity = TransactionReceiptEntity(
    id = id,
    transactionId = transactionId,
    mimeType = mimeType,
    originalName = originalName,
    sizeBytes = sizeBytes,
    payload = Base64.getDecoder().decode(payloadBase64),
    createdAt = createdAt,
)
