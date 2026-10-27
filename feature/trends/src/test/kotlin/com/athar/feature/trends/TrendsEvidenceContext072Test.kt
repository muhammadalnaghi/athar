package com.athar.feature.trends

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.YearMonth

/**
 * WM072 — evidence scope derivation is pure and exact: every bucket-shaped
 * request carries its immutable request key through untouched (including
 * synthetic keys), and every unbucketed request produces no scope line at all.
 */
class TrendsEvidenceContext072Test {

    @Test
    fun `bucket request carries its exact key`() {
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.Bucket("cat-1"))).isEqualTo("cat-1")
    }

    @Test
    fun `month in bucket request carries its exact key`() {
        assertThat(
            evidenceScopeKey(TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 1), "cat-7")),
        ).isEqualTo("cat-7")
    }

    @Test
    fun `category comparison request carries its exact key`() {
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.CategoryComparison("cat-3")))
            .isEqualTo("cat-3")
    }

    @Test
    fun `synthetic bucket keys are preserved exactly`() {
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.Bucket(OTHER_KEY))).isEqualTo(OTHER_KEY)
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.Bucket(UNCATEGORIZED_KEY)))
            .isEqualTo(UNCATEGORIZED_KEY)
        assertThat(
            evidenceScopeKey(TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2025, 12), "صنف-محذوف-42")),
        ).isEqualTo("صنف-محذوف-42")
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.CategoryComparison("orphan-gone")))
            .isEqualTo("orphan-gone")
    }

    @Test
    fun `distinct keys on the same request shape stay distinct`() {
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.Bucket("a")))
            .isNotEqualTo(evidenceScopeKey(TrendsEvidenceRequest.Bucket("b")))
    }

    @Test
    fun `headline requests are not bucket scoped`() {
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE))).isNull()
        assertThat(evidenceScopeKey(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.SAVINGS)))
            .isNull()
    }

    @Test
    fun `month bar requests are not bucket scoped`() {
        assertThat(
            evidenceScopeKey(TrendsEvidenceRequest.MonthBar(YearMonth.of(2026, 2), TrendsMetric.INCOME)),
        ).isNull()
    }
}
