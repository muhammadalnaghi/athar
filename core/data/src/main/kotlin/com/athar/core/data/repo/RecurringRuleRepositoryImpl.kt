package com.athar.core.data.repo

import com.athar.core.data.db.dao.RecurringRuleDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.mapper.toDomain
import com.athar.core.data.mapper.toEntity
import com.athar.core.domain.calc.RecurringSchedule
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.repo.RecurringRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RecurringRuleRepositoryImpl @Inject constructor(
    private val dao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
    private val clock: Clock,
) : RecurringRuleRepository {

    override fun observeAll(includeInactive: Boolean): Flow<List<RecurringRule>> =
        (if (includeInactive) dao.observeAll() else dao.observeActive())
            .map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): RecurringRule? = dao.get(id)?.toDomain()

    override suspend fun upsert(rule: RecurringRule) {
        dao.upsert(rule.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun setActive(id: String, active: Boolean) {
        dao.setActive(id, active, clock.now().toEpochMilliseconds())
    }

    override suspend fun materializeDue(today: LocalDate): Int {
        val due = dao.dueOn(today.toString())
        var created = 0
        val now = clock.now()
        due.forEach { ruleEntity ->
            val rule = ruleEntity.toDomain()
            if (rule.lastRunDate == rule.nextRunDate) return@forEach
            val nextRun = RecurringSchedule.nextRunAfter(rule.nextRunDate, rule)
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                accountId = rule.accountId,
                type = rule.type.name,
                amountMinor = rule.amount.toMinor(),
                currency = rule.amount.currency,
                date = rule.nextRunDate,
                occurredAt = now,
                merchant = rule.merchant,
                merchantNormalized = rule.merchant.lowercase().trim(),
                categoryId = rule.categoryId,
                notes = rule.notes,
                source = IngestSource.RECURRING.name,
                sourceRefId = "recurring:${rule.id}:${rule.nextRunDate}",
                status = TxStatus.PENDING.name,
                confidence = 1.0f,
                createdAt = now,
                updatedAt = now,
                categorySource = null,
            )
            transactionDao.upsert(tx)
            dao.upsert(
                ruleEntity.copy(
                    lastRunDate = rule.nextRunDate.toString(),
                    nextRunDate = nextRun.toString(),
                    updatedAt = now.toEpochMilliseconds(),
                ),
            )
            created++
        }
        return created
    }
}

private fun com.athar.core.common.money.Money.toMinor(): Long =
    amount.movePointRight(2).toLong()
