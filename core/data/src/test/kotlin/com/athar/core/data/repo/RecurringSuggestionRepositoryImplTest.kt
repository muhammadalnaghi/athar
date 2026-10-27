package com.athar.core.data.repo

import com.athar.core.common.money.Money
import com.athar.core.data.db.dao.CategoryUsageCount
import com.athar.core.data.db.dao.RecurringRuleDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.AccountBalanceRow
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class RecurringSuggestionRepositoryImplTest {

    @Test
    fun `suggestions include repeated specific merchant history`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gym-1", merchant = "Gym Club", amountMinor = 9900, month = 1, day = 5),
                    tx(id = "gym-2", merchant = "Gym Club", amountMinor = 9900, month = 2, day = 6),
                    tx(id = "gym-3", merchant = "Gym Club", amountMinor = 9900, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().merchantNormalized).isEqualTo("gym club")
        assertThat(suggestions.single().occurrenceCount).isEqualTo(3)
        assertThat(suggestions.single().suggestedAccountId).isEqualTo("manual")
        assertThat(suggestions.single().suggestedCategoryId).isNull()
    }

    @Test
    fun `suggestions carry category when every occurrence has the same category`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedCategoryId).isEqualTo("cat-gym")
    }

    @Test
    fun `suggestions carry account when every occurrence has the same account`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedAccountId).isEqualTo("acc-checking")
    }

    @Test
    fun `suggestions omit account when occurrence accounts conflict`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        accountId = "acc-credit",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedAccountId).isNull()
    }

    @Test
    fun `suggestions omit category when occurrence categories conflict or are incomplete`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        categoryId = "cat-health",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "cloud-1",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 1,
                        day = 11,
                        categoryId = "cat-software",
                    ),
                    tx(id = "cloud-2", merchant = "Cloud App", amountMinor = 3900, month = 2, day = 12),
                    tx(
                        id = "cloud-3",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 3,
                        day = 11,
                        categoryId = "cat-software",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()
        val byMerchant = suggestions.associateBy { it.merchantNormalized }

        assertThat(suggestions.map { it.merchantNormalized }).containsAtLeast("gym club", "cloud app")
        assertThat(byMerchant.getValue("gym club").suggestedCategoryId).isNull()
        assertThat(byMerchant.getValue("cloud app").suggestedCategoryId).isNull()
    }

    @Test
    fun `suggestions ignore generic merchant labels`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "payment-1", merchant = "Payment", amountMinor = 4200, month = 1, day = 10),
                    tx(id = "payment-2", merchant = "Payment", amountMinor = 4200, month = 2, day = 10),
                    tx(id = "payment-3", merchant = "Payment", amountMinor = 4200, month = 3, day = 10),
                    tx(id = "cash-1", merchant = "كاش", amountMinor = 1200, month = 1, day = 12),
                    tx(id = "cash-2", merchant = "كاش", amountMinor = 1200, month = 2, day = 12),
                    tx(id = "cash-3", merchant = "كاش", amountMinor = 1200, month = 3, day = 12),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `quarterly spacing detected as quarterly not monthly`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "isp-1", merchant = "Isp Bundles", amountMinor = 29900, year = 2025, month = 10, day = 5),
                    tx(id = "isp-2", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 1, day = 5),
                    tx(id = "isp-3", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 4, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        val suggestion = suggestions.single()
        assertThat(suggestion.cadence).isEqualTo(Cadence.QUARTERLY)
        assertThat(suggestion.typicalDayOfMonth).isEqualTo(5)
        assertThat(suggestion.suggestedNextRun).isEqualTo(LocalDate(2026, 7, 5))
    }

    @Test
    fun `weekly pattern detected with weekday anchor`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "wash-1", merchant = "Car Wash", amountMinor = 2500, month = 5, day = 19),
                    tx(id = "wash-2", merchant = "Car Wash", amountMinor = 2500, month = 5, day = 26),
                    tx(id = "wash-3", merchant = "Car Wash", amountMinor = 2500, month = 6, day = 2),
                    tx(id = "wash-4", merchant = "Car Wash", amountMinor = 2500, month = 6, day = 9),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        val suggestion = suggestions.single()
        assertThat(suggestion.cadence).isEqualTo(Cadence.WEEKLY)
        assertThat(suggestion.dayOfWeek).isEqualTo(2)
        assertThat(suggestion.suggestedNextRun).isEqualTo(LocalDate(2026, 6, 16))
    }

    @Test
    fun `three weekly observations are not enough`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "wash-1", merchant = "Car Wash", amountMinor = 2500, month = 5, day = 26),
                    tx(id = "wash-2", merchant = "Car Wash", amountMinor = 2500, month = 6, day = 2),
                    tx(id = "wash-3", merchant = "Car Wash", amountMinor = 2500, month = 6, day = 9),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `yearly pattern detected with month anchor across two-year lookback`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "ins-1", merchant = "Insurance Renewal", amountMinor = 120000, year = 2024, month = 12, day = 20),
                    tx(id = "ins-2", merchant = "Insurance Renewal", amountMinor = 120000, year = 2025, month = 12, day = 20),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        val suggestion = suggestions.single()
        assertThat(suggestion.cadence).isEqualTo(Cadence.YEARLY)
        assertThat(suggestion.monthOfYear).isEqualTo(12)
        assertThat(suggestion.typicalDayOfMonth).isEqualTo(20)
        assertThat(suggestion.occurrenceCount).isEqualTo(2)
        assertThat(suggestion.suggestedNextRun).isEqualTo(LocalDate(2026, 12, 20))
    }

    @Test
    fun `small amount variation within currency stays detected`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "str-1", merchant = "Streaming Plus", amountMinor = 10000, month = 1, day = 5),
                    tx(id = "str-2", merchant = "Streaming Plus", amountMinor = 10050, month = 2, day = 5),
                    tx(id = "str-3", merchant = "Streaming Plus", amountMinor = 9950, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(10000, "SAR"))
    }

    @Test
    fun `large amount variation rejected`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "big-1", merchant = "Store Trips", amountMinor = 10000, month = 1, day = 5),
                    tx(id = "big-2", merchant = "Store Trips", amountMinor = 10050, month = 2, day = 5),
                    tx(id = "big-3", merchant = "Store Trips", amountMinor = 15000, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `borderline drift around the median stays one monthly cluster`() = runTest {
        // R9B F1: {9000, 9100, 10000} split under a first-amount anchor (11.1% from the
        // minimum) and lost the monthly rhythm; the documented median baseline keeps it.
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gym-1", merchant = "Gym Club", amountMinor = 9000, month = 1, day = 5),
                    tx(id = "gym-2", merchant = "Gym Club", amountMinor = 9100, month = 2, day = 5),
                    tx(id = "gym-3", merchant = "Gym Club", amountMinor = 10000, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(suggestions.single().occurrenceCount).isEqualTo(3)
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(9100, "SAR"))
    }

    @Test
    fun `exactly ten percent below the median stays one monthly cluster`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gym-1", merchant = "Gym Club", amountMinor = 9000, month = 1, day = 5),
                    tx(id = "gym-2", merchant = "Gym Club", amountMinor = 10000, month = 2, day = 5),
                    tx(id = "gym-3", merchant = "Gym Club", amountMinor = 10100, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(10000, "SAR"))
    }

    @Test
    fun `drift beyond ten percent of the median splits the cluster`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gym-1", merchant = "Gym Club", amountMinor = 10000, month = 1, day = 5),
                    tx(id = "gym-2", merchant = "Gym Club", amountMinor = 10100, month = 2, day = 5),
                    tx(id = "gym-3", merchant = "Gym Club", amountMinor = 15000, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `even median exact ten percent boundary is inclusive`() = runTest {
        val suggestions = medianSuggestions(listOf(9000L, 11000L), yearly = true)

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.YEARLY)
        // The clustering mean is 10000, but nominal suggestions retain the observed upper middle.
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(11000, "SAR"))
    }

    @Test
    fun `one minor unit beyond even median boundary is rejected without rounding`() = runTest {
        assertThat(medianSuggestions(listOf(9000L, 11001L), yearly = true)).isEmpty()
    }

    @Test
    fun `equal maximum amounts do not overflow median or tolerance`() = runTest {
        val suggestions = medianSuggestions(listOf(Long.MAX_VALUE, Long.MAX_VALUE), yearly = true)

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.YEARLY)
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(Long.MAX_VALUE, "SAR"))
    }

    @Test
    fun `adjacent maximum amounts retain exact odd median`() = runTest {
        val suggestions = medianSuggestions(listOf(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE))

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(suggestions.single().amount).isEqualTo(Money.ofMinor(Long.MAX_VALUE - 1, "SAR"))
    }

    @Test
    fun `extreme spread cannot overflow into a false yearly recurrence`() = runTest {
        assertThat(medianSuggestions(listOf(1L, Long.MAX_VALUE), yearly = true)).isEmpty()
    }

    private suspend fun medianSuggestions(amounts: List<Long>, yearly: Boolean = false) =
        RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                amounts.mapIndexed { index, amount ->
                    tx(
                        id = "median-$index",
                        merchant = "Median Service",
                        amountMinor = amount,
                        year = if (yearly) 2024 + index else 2026,
                        month = if (yearly) 12 else index + 1,
                        day = 5,
                    )
                },
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        ).observeSuggestions().first()

    @Test
    fun `same-day purchase batch rejected even with monthly-looking history`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gift-1", merchant = "Gift Cards", amountMinor = 5000, month = 1, day = 5),
                    tx(id = "gift-2", merchant = "Gift Cards", amountMinor = 5000, month = 2, day = 5),
                    tx(id = "gift-3", merchant = "Gift Cards", amountMinor = 5000, month = 3, day = 5),
                    tx(id = "gift-4", merchant = "Gift Cards", amountMinor = 5000, month = 3, day = 5),
                    tx(id = "gift-5", merchant = "Gift Cards", amountMinor = 5000, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `month-end monthly sequence projects clamped next run`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "rent-1", merchant = "Rent Meter", amountMinor = 250000, month = 1, day = 31),
                    tx(id = "rent-2", merchant = "Rent Meter", amountMinor = 250000, month = 2, day = 28),
                    tx(id = "rent-3", merchant = "Rent Meter", amountMinor = 250000, month = 3, day = 31),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(suggestions.single().typicalDayOfMonth).isEqualTo(31)
        // Apr 30 and May 31 are already behind today (2026-06-15); the next anchor-31
        // occurrence strictly after today is June, clamped to the 30th.
        assertThat(suggestions.single().suggestedNextRun).isEqualTo(LocalDate(2026, 6, 30))
    }

    @Test
    fun `existing monthly rule does not suppress quarterly pattern for same merchant`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "isp-1", merchant = "Isp Bundles", amountMinor = 29900, year = 2025, month = 10, day = 5),
                    tx(id = "isp-2", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 1, day = 5),
                    tx(id = "isp-3", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 4, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(rows = listOf(ruleEntity(cadence = "MONTHLY"))),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.QUARTERLY)
    }

    @Test
    fun `rule with same merchant amount and cadence still suppresses`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "isp-1", merchant = "Isp Bundles", amountMinor = 29900, year = 2025, month = 10, day = 5),
                    tx(id = "isp-2", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 1, day = 5),
                    tx(id = "isp-3", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 4, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(rows = listOf(ruleEntity(cadence = "QUARTERLY"))),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `same merchant different currency does not collapse into one suggestion`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "cld-1", merchant = "Cloud App", amountMinor = 3900, month = 1, day = 11),
                    tx(id = "cld-2", merchant = "Cloud App", amountMinor = 3900, month = 2, day = 11),
                    tx(id = "cld-3", merchant = "Cloud App", amountMinor = 3900, month = 3, day = 11),
                    tx(
                        id = "cld-us-1",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 1,
                        day = 11,
                        currency = "USD",
                    ),
                    tx(
                        id = "cld-us-2",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 2,
                        day = 11,
                        currency = "USD",
                    ),
                    tx(
                        id = "cld-us-3",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 3,
                        day = 11,
                        currency = "USD",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(2)
        assertThat(suggestions.map { it.amount.currency }).containsExactly("SAR", "USD")
    }

    @Test
    fun `foreign-currency rule does not suppress same-merchant suggestion`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "isp-1", merchant = "Isp Bundles", amountMinor = 29900, year = 2025, month = 10, day = 5),
                    tx(id = "isp-2", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 1, day = 5),
                    tx(id = "isp-3", merchant = "Isp Bundles", amountMinor = 29900, year = 2026, month = 4, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(rows = listOf(ruleEntity(cadence = "QUARTERLY", currency = "USD"))),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().cadence).isEqualTo(Cadence.QUARTERLY)
        assertThat(suggestions.single().amount.currency).isEqualTo("SAR")
    }

    @Test
    fun `different-type rule does not suppress suggestion for the other type`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "sal-1", merchant = "Salary Deposit", amountMinor = 500000, month = 3, day = 27, type = TxType.INCOME),
                    tx(id = "sal-2", merchant = "Salary Deposit", amountMinor = 500000, month = 4, day = 27, type = TxType.INCOME),
                    tx(id = "sal-3", merchant = "Salary Deposit", amountMinor = 500000, month = 5, day = 27, type = TxType.INCOME),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(
                rows = listOf(
                    ruleEntity(
                        cadence = "MONTHLY",
                        merchant = "Salary Deposit",
                        amountMinor = 500000,
                        type = TxType.EXPENSE.name,
                    ),
                ),
            ),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().type).isEqualTo(TxType.INCOME)
    }

    @Test
    fun `rule matching currency type merchant amount and cadence still suppresses`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "cld-1", merchant = "Cloud App", amountMinor = 3900, month = 3, day = 11, currency = "USD", type = TxType.INCOME),
                    tx(id = "cld-2", merchant = "Cloud App", amountMinor = 3900, month = 4, day = 11, currency = "USD", type = TxType.INCOME),
                    tx(id = "cld-3", merchant = "Cloud App", amountMinor = 3900, month = 5, day = 11, currency = "USD", type = TxType.INCOME),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(
                rows = listOf(
                    ruleEntity(
                        cadence = "MONTHLY",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        currency = "USD",
                        type = TxType.INCOME.name,
                    ),
                ),
            ),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }

    @Test
    fun `separator-bearing merchant identity does not collide with another rule`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "at-1", merchant = "Alpha@29900@QUARTERLY", amountMinor = 4900, month = 3, day = 7),
                    tx(id = "at-2", merchant = "Alpha@29900@QUARTERLY", amountMinor = 4900, month = 4, day = 7),
                    tx(id = "at-3", merchant = "Alpha@29900@QUARTERLY", amountMinor = 4900, month = 5, day = 7),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(
                rows = listOf(ruleEntity(cadence = "MONTHLY", merchant = "Alpha", amountMinor = 4900)),
            ),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().merchantNormalized).isEqualTo("alpha@29900@quarterly")
    }
}

private class FakeTransactionDao(
    private val rows: List<TransactionEntity>,
) : TransactionDao {
    override fun observeByPeriod(start: LocalDate, endExclusive: LocalDate, status: String?): Flow<List<TransactionEntity>> =
        flowOf(emptyList())

    override fun observePending(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun get(id: String): TransactionEntity? = rows.firstOrNull { it.id == id }

    override suspend fun findBySourceRefId(sourceRefId: String): TransactionEntity? =
        rows.firstOrNull { it.sourceRefId == sourceRefId }

    override suspend fun all(): List<TransactionEntity> = rows

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(rows)

    override fun observeConfirmedSince(since: LocalDate): Flow<List<TransactionEntity>> =
        flowOf(rows.filter { it.status == TxStatus.CONFIRMED.name && it.date >= since })

    override suspend fun confirmedCategoryCountsForMerchant(merchantNormalized: String): List<CategoryUsageCount> =
        emptyList()

    override suspend fun firstId(): String? = rows.firstOrNull()?.id

    override suspend fun clear() = Unit

    override suspend fun clearPending(): Int = 0

    override suspend fun confirmAllConfident(minConfidence: Float, now: Instant): Int = 0

    override suspend fun dismissAllLowConfidence(maxConfidence: Float, now: Instant): Int = 0

    override suspend fun dismissAllPending(now: Instant): Int = 0

    override suspend fun recoverDismissedToPending(now: Instant): Int = 0

    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String, now: Instant): Int = 0

    override suspend fun upsert(entity: TransactionEntity) = Unit

    override suspend fun updateExistingForUpsert(entity: TransactionEntity): Int =
        error("Write operations are not supported by this read-only suggestion fake")

    override suspend fun insertNewForUpsert(entity: TransactionEntity) =
        error("Write operations are not supported by this read-only suggestion fake")

    override suspend fun delete(id: String) = Unit

    override suspend fun setStatus(id: String, status: String, now: Instant) = Unit

    override fun observeBalancesByAccount(): Flow<List<AccountBalanceRow>> = flowOf(emptyList())
}

private class FakeRecurringRuleDao(
    private val rows: List<RecurringRuleEntity> = emptyList(),
) : RecurringRuleDao {
    override suspend fun all(): List<RecurringRuleEntity> = rows

    override suspend fun count(): Long = rows.size.toLong()
    override fun observeAll(): Flow<List<RecurringRuleEntity>> = flowOf(rows)

    override fun observeActive(): Flow<List<RecurringRuleEntity>> = flowOf(rows.filter { it.isActive })

    override suspend fun get(id: String): RecurringRuleEntity? = rows.firstOrNull { it.id == id }

    override suspend fun dueOn(today: String): List<RecurringRuleEntity> =
        rows.filter { it.isActive && it.nextRunDate <= today }

    override suspend fun upsert(entity: RecurringRuleEntity) = Unit

    override suspend fun setActive(id: String, active: Boolean, ts: Long) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun clear() = Unit
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-15T12:00:00Z")
}

private fun tx(
    id: String,
    merchant: String,
    amountMinor: Long,
    month: Int,
    day: Int,
    year: Int = 2026,
    currency: String = "SAR",
    type: TxType = TxType.EXPENSE,
    accountId: String = "manual",
    categoryId: String? = null,
): TransactionEntity =
    TransactionEntity(
        id = id,
        accountId = accountId,
        type = type.name,
        amountMinor = amountMinor,
        currency = currency,
        date = LocalDate(year, month, day),
        occurredAt = Instant.parse(
            "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T12:00:00Z",
        ),
        merchant = merchant,
        merchantNormalized = merchant.lowercase().trim(),
        categoryId = categoryId,
        notes = null,
        source = IngestSource.MANUAL.name,
        sourceRefId = null,
        status = TxStatus.CONFIRMED.name,
        confidence = 1f,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        categorySource = null,
    )

private fun ruleEntity(
    cadence: String,
    merchant: String = "Isp Bundles",
    amountMinor: Long = 29900,
    currency: String = "SAR",
    type: String = TxType.EXPENSE.name,
): RecurringRuleEntity =
    RecurringRuleEntity(
        id = "rule-$merchant-$cadence-$currency-$type",
        displayName = merchant,
        merchant = merchant,
        amountMinor = amountMinor,
        amountCurrency = currency,
        type = type,
        accountId = "manual",
        categoryId = null,
        cadence = cadence,
        dayOfMonth = 5,
        dayOfWeek = null,
        monthOfYear = null,
        nextRunDate = "2026-07-05",
        lastRunDate = null,
        isActive = true,
        notes = null,
        createdAt = 0L,
        updatedAt = 0L,
    )
