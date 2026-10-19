package com.athar.core.data.mapper

import com.athar.core.common.money.Money
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType

internal fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    accountId = accountId,
    type = TxType.valueOf(type),
    amount = Money.ofMinor(amountMinor, currency),
    date = date,
    occurredAt = occurredAt,
    merchant = merchant,
    merchantNormalized = merchantNormalized,
    categoryId = categoryId,
    notes = notes,
    source = IngestSource.valueOf(source),
    sourceRefId = sourceRefId,
    status = TxStatus.valueOf(status),
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
    // Unknown future provenance (e.g. a backup from a newer build) degrades to
    // null instead of failing the read/restore.
    categorySource = categorySource?.let { name ->
        runCatching { CategorySource.valueOf(name) }.getOrNull()
    },
)

internal fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    accountId = accountId,
    type = type.name,
    amountMinor = amount.toMinor(),
    currency = amount.currency,
    date = date,
    occurredAt = occurredAt,
    merchant = merchant,
    merchantNormalized = merchantNormalized,
    categoryId = categoryId,
    notes = notes,
    source = source.name,
    sourceRefId = sourceRefId,
    status = status.name,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
    categorySource = categorySource?.name,
)
