package com.athar.feature.trends

/**
 * WM072 — the exact bucket a Trends evidence sheet is scoped to, taken from the
 * immutable [TrendsEvidenceRequest] (never the mutable query filters). Null means
 * the request is not bucket-scoped (headline, headline comparison, month bar) and
 * the evidence sheet must not render a category scope line for it.
 */
fun evidenceScopeKey(request: TrendsEvidenceRequest): String? = when (request) {
    is TrendsEvidenceRequest.Bucket -> request.key
    is TrendsEvidenceRequest.MonthInBucket -> request.key
    is TrendsEvidenceRequest.CategoryComparison -> request.key
    is TrendsEvidenceRequest.Headline,
    is TrendsEvidenceRequest.HeadlineComparison,
    is TrendsEvidenceRequest.MonthBar,
    -> null
}
