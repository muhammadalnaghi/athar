package com.athar.core.data.seed

import android.content.Context
import com.athar.core.data.db.dao.CategoryRuleDao
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.domain.model.PatternType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Seeds the merchant categorization rules from `assets/seed_rules.json` on first run.
 * Master Brief §4.7 / Backlog S-09.
 *
 * Idempotent: only seeds when bundled system rules are missing or stale.
 * User-learned rules and local auto-learned rules are never overwritten.
 */
internal class RuleSeed @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CategoryRuleDao,
    private val clock: Clock,
) {

    suspend fun seedIfEmpty() {
        val raw = context.assets.open("seed_rules.json").bufferedReader().use { it.readText() }
        // Cheap steady-state path: when the bundled payload is byte-identical to the
        // last completed seed AND the system-rule count is unchanged, skip decoding
        // the payload and loading all rows. Any real change (new bundle, deleted or
        // tampered seed rows) breaks the marker and takes the full verification path.
        val payloadMarker = sha256(raw)
        val prefs = context.getSharedPreferences("athar_seed_markers", Context.MODE_PRIVATE)
        val systemCount = dao.countSystemRules()
        if (prefs.getString(RULE_SEED_MARKER_KEY, null) == payloadMarker &&
            prefs.getInt(RULE_SEED_COUNT_KEY, -1) == systemCount && systemCount > 0
        ) {
            Timber.d("System rules up-to-date ($systemCount) — marker skip.")
            return
        }
        val payload = SeedJson.decodeFromString<SeedPayload>(raw)
        val expectedRules = payload.rules.map { it.toSeedRuleSignature() }
        val currentRules = dao.all()
        val currentSeedRules = currentRules.filter { it.isBundledSeedRule() }
        if (!seedRulesNeedRefresh(currentRules, expectedRules)) {
            Timber.d("System rules up-to-date (${currentSeedRules.size}) — skipping seed.")
            prefs.edit().putString(RULE_SEED_MARKER_KEY, payloadMarker)
                .putInt(RULE_SEED_COUNT_KEY, currentSeedRules.size).apply()
            return
        }
        if (currentSeedRules.isNotEmpty()) {
            Timber.i("Refreshing system rules: ${currentSeedRules.size} → ${expectedRules.size}")
            dao.clearSeedRules()
        }
        val now = clock.now()
        val entities = payload.rules.map { dto ->
            CategoryRuleEntity(
                id = UUID.randomUUID().toString(),
                pattern = dto.pattern,
                patternType = (dto.patternType ?: PatternType.SUBSTRING).name,
                categoryId = dto.categoryId,
                priority = dto.priority,
                learnedFromUser = false,
                createdAt = now,
            )
        }
        entities.forEach { dao.upsert(it) }
        prefs.edit().putString(RULE_SEED_MARKER_KEY, payloadMarker)
            .putInt(RULE_SEED_COUNT_KEY, entities.size).apply()
        Timber.i("Seeded ${entities.size} categorization rules.")
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Serializable private data class SeedPayload(val rules: List<SeedRule>)
    @Serializable private data class SeedRule(
        val pattern: String,
        val categoryId: String,
        val priority: Int,
        val patternType: PatternType? = null,
    )

    private fun SeedRule.toSeedRuleSignature(): SeedRuleSignature = SeedRuleSignature(
        pattern = pattern,
        patternType = (patternType ?: PatternType.SUBSTRING).name,
        categoryId = categoryId,
        priority = priority,
    )

    private companion object {
        val SeedJson = Json { ignoreUnknownKeys = true }
        const val RULE_SEED_MARKER_KEY = "rule_seed_payload_sha256"
        const val RULE_SEED_COUNT_KEY = "rule_seed_system_count"
    }
}

internal data class SeedRuleSignature(
    val pattern: String,
    val patternType: String,
    val categoryId: String,
    val priority: Int,
)

internal fun seedRulesNeedRefresh(
    currentRules: List<CategoryRuleEntity>,
    expectedRules: List<SeedRuleSignature>,
): Boolean {
    val currentSeedRules = currentRules.filter { it.isBundledSeedRule() }
    if (currentSeedRules.size != expectedRules.size) return true

    val currentSignatures = currentSeedRules.map { it.toSeedRuleSignature() }.toSet()
    val expectedSignatures = expectedRules.toSet()
    return currentSignatures != expectedSignatures
}

internal fun CategoryRuleEntity.isBundledSeedRule(): Boolean =
    !learnedFromUser && !id.startsWith(AutoLocalRuleIdPrefix)

internal fun CategoryRuleEntity.toSeedRuleSignature(): SeedRuleSignature = SeedRuleSignature(
    pattern = pattern,
    patternType = patternType,
    categoryId = categoryId,
    priority = priority,
)

private const val AutoLocalRuleIdPrefix = "auto-local-"
