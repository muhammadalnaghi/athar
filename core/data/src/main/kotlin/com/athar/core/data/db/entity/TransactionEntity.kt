package com.athar.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["date"]),
        Index(value = ["status"]),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
        Index(value = ["sourceRefId"], unique = true),
    ],
)
internal data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: String,                 // TxType.name
    val amountMinor: Long,            // halalas
    val currency: String,
    val date: LocalDate,
    val occurredAt: Instant?,
    val merchant: String,
    val merchantNormalized: String,
    val categoryId: String?,
    val notes: String?,
    val source: String,               // IngestSource.name
    val sourceRefId: String?,
    val status: String,               // TxStatus.name
    val confidence: Float?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val categorySource: String? = null, // CategorySource.name; null = legacy/unknown
)
