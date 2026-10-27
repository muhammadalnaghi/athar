package com.athar.core.domain.smsimport

/**
 * Exact-ID sender handling for SMS import filters.
 *
 * Comparison is trim + case-insensitive only — never substring, wildcard, or fuzzy.
 * Persistence keeps the first exact spelling the user selected / discovered.
 */
object SmsSenderIds {
    fun preserve(raw: String): String? {
        val trimmed = raw.trim()
        return trimmed.takeIf { it.isNotEmpty() }
    }

    fun normalize(raw: String): String = raw.trim().lowercase()

    fun matches(candidate: String, selectedExactIds: Set<String>): Boolean {
        if (selectedExactIds.isEmpty()) return false
        val needle = normalize(candidate)
        if (needle.isEmpty()) return false
        return selectedExactIds.any { normalize(it) == needle }
    }

    fun normalizedSet(selectedExactIds: Set<String>): Set<String> =
        selectedExactIds.mapNotNull { preserve(it)?.let(::normalize) }.toSet()
}
