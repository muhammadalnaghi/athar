package com.athar.core.data.backup.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL platform proof for [StrictBackupJsonGuard]: these tests execute the
 * actual `android.util.JsonReader` token walk on-device. The JVM suite only
 * covers budget preconditions and is explicitly not guard evidence — this
 * suite is.
 *
 * Fixture note: payloads below are field-name-agnostic guard fixtures. The
 * guard is a syntax/resource/version gate; referential validation (including
 * v4 canonicalLedger completeness) belongs to the backup-service lane, so the
 * v4 fixture's canonicalLedger is illustrative, not a schema claim.
 */
@RunWith(AndroidJUnit4::class)
class StrictBackupJsonGuardNativeTest {

    // -- realistic valid envelopes ---------------------------------------------

    @Test
    fun acceptsARealisticV1EnvelopeAndReturns1() {
        val json = """
            {"version":1,"exportedAt":"2026-10-21T09:00:00Z",
             "accounts":[{"id":"acc-1","name":"الحساب الجاري","maskedIban":"SA0380000000608010167519"}],
             "transactions":[{"id":"txn-1","accountId":"acc-1","amount":"-42.50","currency":"SAR",
                              "date":"2026-10-20","description":"شراء","categoryId":"cat-food"}]}
        """.trimIndent()
        assertThat(guard(json)).isEqualTo(1)
    }

    @Test
    fun acceptsRealisticV2AndV3EnvelopesAndReturnsTheirVersions() {
        val v2 = """
            {"version":2,"exportedAt":"2026-10-21T09:00:00Z","accounts":[],"transactions":[],
             "budgets":[{"id":"b-1","month":"2026-10","limit":"1500.00"}]}
        """.trimIndent()
        val v3 = """
            {"version":3,"exportedAt":"2026-10-21T09:00:00Z","accounts":[],"transactions":[],
             "investments":[{"id":"inv-1","symbol":"2222","quantity":"10","costBasis":"312.00"}]}
        """.trimIndent()
        assertThat(guard(v2)).isEqualTo(2)
        assertThat(guard(v3)).isEqualTo(3)
    }

    @Test
    fun acceptsARealisticV4EnvelopeWithACanonicalLedgerAndReturns4() {
        val v4 = """
            {"version":4,"exportedAt":"2026-10-21T09:00:00Z","accounts":[],"transactions":[],
             "canonicalLedger":{"sections":[{"kind":"accounts","rows":[]},{"kind":"transactions","rows":[]}]}}
        """.trimIndent()
        assertThat(guard(v4)).isEqualTo(4)
    }

    @Test
    fun acceptsARootVersionKeyInAnyPosition() {
        assertThat(guard("""{"note":"x","version":2}""")).isEqualTo(2)
    }

    // -- root shape and END_DOCUMENT ---------------------------------------------

    @Test
    fun rejectsRootsThatAreNotASingleObject() {
        assertThat(guardFailure("[]")).contains("root must be a JSON object")
        assertThat(guardFailure("\"envelope\"")).contains("root must be a JSON object")
        assertThat(guardFailure("")).contains("root must be a JSON object")
        assertThat(guardFailure("null")).contains("root must be a JSON object")
    }

    @Test
    fun rejectsTrailingContentAfterTheRootObject() {
        assertThat(guardFailure("""{"version":1}{"version":2}""")).contains("trailing content")
        assertThat(guardFailure("""{"version":1}[]""")).contains("trailing content")
        assertThat(guardFailure("""{"version":1} 42""")).contains("trailing content")
        assertThat(guardFailure("""{"version":1} null""")).contains("trailing content")
    }

    @Test
    fun rejectsTruncatedDocumentsWithoutEchoingPayload() {
        val failure = guardFailure("""{"version":1,"note":"unterminated""")
        assertThat(failure).contains("malformed or truncated")
        assertThat(failure).doesNotContain("unterminated")
    }

    // -- version semantics -----------------------------------------------------

    @Test
    fun rejectsMissingRootVersion() {
        assertThat(guardFailure("""{"accounts":[]}""")).contains("missing a numeric root version")
    }

    @Test
    fun rejectsNonnumericRootVersions() {
        assertThat(guardFailure("""{"version":"3"}""")).contains("must be a JSON number")
        assertThat(guardFailure("""{"version":true}""")).contains("must be a JSON number")
        assertThat(guardFailure("""{"version":null}""")).contains("must be a JSON number")
        assertThat(guardFailure("""{"version":3.5}""")).contains("plain integer")
    }

    @Test
    fun rejectsUnsupportedRootVersions() {
        assertThat(guardFailure("""{"version":0}""")).contains("between 1 and 4")
        assertThat(guardFailure("""{"version":5}""")).contains("between 1 and 4")
        assertThat(guardFailure("""{"version":-1}""")).contains("between 1 and 4")
        assertThat(guardFailure("""{"version":99999999999999999999}""")).contains("plain integer")
    }

    @Test
    fun rejectsVersionsBeyondIntRangeWithoutWrappingIntoRange() {
        // 4294967297 would wrap to 1 under silent Int truncation; it must reject.
        assertThat(guardFailure("""{"version":4294967297}""")).contains("plain integer")
        assertThat(guardFailure("""{"version":2147483648}""")).contains("plain integer")
    }

    @Test
    fun rejectsFractionalAndExponentVersionLiterals() {
        assertThat(guardFailure("""{"version":4.0}""")).contains("plain integer")
        assertThat(guardFailure("""{"version":2e0}""")).contains("plain integer")
    }

    @Test
    fun aNestedVersionKeyNeverSatisfiesTheRootRequirement() {
        assertThat(guardFailure("""{"meta":{"version":3}}""")).contains("missing a numeric root version")
    }

    // -- duplicate keys at every depth --------------------------------------------

    @Test
    fun rejectsDuplicateKeysAtTheRoot() {
        assertThat(guardFailure("""{"version":1,"version":1}""")).contains("duplicate JSON key")
    }

    @Test
    fun rejectsDuplicateKeysInNestedObjects() {
        val json = """{"version":1,"a":{"k":1,"k":2}}"""
        assertThat(guardFailure(json)).contains("duplicate JSON key")
    }

    @Test
    fun rejectsDuplicateKeysInsideArrayMembers() {
        val json = """{"version":1,"rows":[{"id":"a","id":"b"}]}"""
        assertThat(guardFailure(json)).contains("duplicate JSON key")
    }

    // -- exact and +1 bounds ------------------------------------------------------

    @Test
    fun acceptsValuesAtTheExactDepthBoundAndRejectsOneValueDeeper() {
        // Containers at depths 0,1,2 with no member sitting deeper than 2.
        val atBound = """{"version":1,"a":{"b":{}}}"""
        // The scalar "c" would sit at depth 3 — one value beyond the bound.
        val oneDeeper = """{"version":1,"a":{"b":{"c":1}}}"""
        assertThat(guard(atBound, maxDepth = 2)).isEqualTo(1)
        assertThat(guardFailure(oneDeeper, maxDepth = 2)).contains("nesting deeper than 2")
    }

    @Test
    fun countsEveryTokenAndEnforcesTheExactTokenBudget() {
        val json = """{"version":4,"m":{"k":"v"}}""" // BEGIN,NAME,NUMBER,NAME,BEGIN,NAME,STRING,END,END = 9
        assertThat(guard(json, maxTokens = 9)).isEqualTo(4)
        assertThat(guardFailure(json, maxTokens = 8)).contains("token budget of 8")
    }

    @Test
    fun boundsStringValuesAtExactlyMaxStringChars() {
        val exact = """{"version":1,"note":"abcdefghij"}"""
        val oneOver = """{"version":1,"note":"abcdefghijk"}"""
        assertThat(guard(exact, maxStringChars = 10)).isEqualTo(1)
        assertThat(guardFailure(oneOver, maxStringChars = 10)).contains("budget of 10 chars")
    }

    @Test
    fun boundsNamesAndNumberLiteralsWithTheSameBudget() {
        assertThat(guard("""{"version":1,"abcdefghij":1}""", maxStringChars = 10)).isEqualTo(1)
        assertThat(guardFailure("""{"version":1,"abcdefghijk":1}""", maxStringChars = 10))
            .contains("budget of 10 chars")
        assertThat(guard("""{"version":1,"n":1234567890}""", maxStringChars = 10)).isEqualTo(1)
        assertThat(guardFailure("""{"version":1,"n":12345678901}""", maxStringChars = 10))
            .contains("budget of 10 chars")
    }

    // -- error hygiene --------------------------------------------------------------

    @Test
    fun guardErrorsNeverEchoPayloadFragments() {
        val marker = "mark-سري-77"
        val duplicate = """{"version":1,"$marker":1,"$marker":2}"""
        assertThat(guardFailure(duplicate)).doesNotContain(marker)
        val oversized = """{"version":1,"note":"$marker-and-much-more-text"}"""
        assertThat(guardFailure(oversized, maxStringChars = 8)).doesNotContain(marker)
    }

    @Test
    fun enforcesPositiveBudgetsOnDeviceAsWell() {
        assertThat(
            runCatching { guard("{}", maxDepth = 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { guard("{}", maxTokens = 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { guard("{}", maxStringChars = 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun guardRejectionsCarryNoChainedCauseThatCouldLeakDocumentPaths() {
        val failure = runCatching {
            guard("""{"version":1,"note":"unterminated"""")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(StrictBackupGuardException::class.java)
        assertThat(failure).hasCauseThat().isNull()
    }

    @Test
    fun rootAndTrailingPeekFailuresRejectCauseFreeWithTheFixedStructuralMessages() {
        // An empty document throws inside the root peek itself on a strict
        // reader; it must still report the root-shape rejection, cause-free.
        val emptyFailure = runCatching { guard("") }.exceptionOrNull()
        assertThat(emptyFailure).isInstanceOf(StrictBackupGuardException::class.java)
        assertThat(emptyFailure).hasMessageThat().contains("root must be a JSON object")
        assertThat(emptyFailure).hasCauseThat().isNull()
        // A second root object throws inside the END_DOCUMENT peek; it must
        // report the trailing-content rejection, cause-free, without echoing
        // the trailing payload.
        val trailingFailure = runCatching {
            guard("""{"version":1}{"version":2}""")
        }.exceptionOrNull()
        assertThat(trailingFailure).isInstanceOf(StrictBackupGuardException::class.java)
        assertThat(trailingFailure).hasMessageThat().contains("trailing content")
        assertThat(trailingFailure).hasMessageThat().doesNotContain("\"version\":2")
        assertThat(trailingFailure).hasCauseThat().isNull()
    }

    // -- helpers -------------------------------------------------------------

    /** Real guard invocation with the production-typical budgets. */
    private fun guard(
        json: String,
        maxDepth: Int = 24,
        maxTokens: Long = 100_000,
        maxStringChars: Int = 1_000_000,
    ): Int = StrictBackupJsonGuard.validate(json, maxDepth, maxTokens, maxStringChars)

    private fun guardFailure(
        json: String,
        maxDepth: Int = 24,
        maxTokens: Long = 100_000,
        maxStringChars: Int = 1_000_000,
    ): String = requireNotNull(
        runCatching { guard(json, maxDepth, maxTokens, maxStringChars) }
            .exceptionOrNull()?.message,
    ) { "expected StrictBackupJsonGuard to reject: ${json.take(24)}…" }
}
