package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Parent-only opt-in diagnostic: no raw sender/body/amount enters a report or assertion. */
class PrivatePreparedArchiveFailureAuditTest {
    @Test
    fun auditsPreparedArchiveFailuresOnlyWhenExplicitlyEnabled() {
        val token = System.getenv("ATHAR_PRIVATE_FAILURE_OPT_IN")
        assumeTrue(token == "wealth-029-local-failures", "Private audit not engaged; skipped is not data coverage")
        val input = Path.of(checkNotNull(System.getenv("ATHAR_PRIVATE_FAILURE_INPUT")))
        val output = Path.of(checkNotNull(System.getenv("ATHAR_PRIVATE_FAILURE_OUTPUT")))
        check(Files.isRegularFile(input))
        val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())
        val decoder = Base64.getDecoder()
        fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val oracleLines = System.getenv("ATHAR_PRIVATE_ORACLE_INPUT")?.let { Files.readAllLines(Path.of(it)) }.orEmpty()
        val oracle = oracleLines.map { it.split('\t') }.also { rows ->
            check(rows.all { it.size == 4 && it[1] in setOf("IGNORED", "MONEY_SHA256") })
            check(rows.map { it[0] }.distinct().size == rows.size)
            check(rows.filter { it[1] == "MONEY_SHA256" }.all { it[3].matches(Regex("[0-9a-f]{64}")) })
        }.associateBy { it[0] }
        val expectedNewNotices = oracle.values.count { it[1] == "IGNORED" }
        val seenOracle = mutableSetOf<String>()
        val oracleFailures = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var records = 0
        var successes = 0
        var ignored = 0
        var explicitPosted = 0
        var datedDirectional = 0
        val evidenceCoverage = sortedMapOf<String, Int>()
        val successIndex = mutableListOf<String>()
        val declinedDispositions = mutableListOf<String>()
        val shapePatterns = listOf(
            "purchase" to Regex("شراء|مشتريات|purchas", RegexOption.IGNORE_CASE),
            "income" to Regex("وارد|إيداع|ايداع|راتب|salary|deposit|credited", RegexOption.IGNORE_CASE),
            "refund" to Regex("استرداد|استرجاع|إرجاع|ارجاع|refund|revers", RegexOption.IGNORE_CASE),
            "debit" to Regex("خصم|حسم|debit|deduct", RegexOption.IGNORE_CASE),
            "repayment" to Regex("سداد|قسط|installment|repayment", RegexOption.IGNORE_CASE),
            "code" to Regex("رمز|otp|verification|password", RegexOption.IGNORE_CASE),
        )
        Files.lines(input).use { lines ->
            lines.forEach { line ->
                val fields = line.split('\t', limit = 4)
                check(fields.size == 4)
                val event = RawIngestEvent(
                    id = fields[0], rawId = fields[0], source = IngestSource.SMS,
                    sender = String(decoder.decode(fields[2]), Charsets.UTF_8),
                    body = String(decoder.decode(fields[3]), Charsets.UTF_8),
                    receivedAt = Instant.parse(fields[1]),
                )
                records++
                val result = parser.parse(event)
                val expected = oracle[fields[0]]
                if (expected != null) {
                    seenOracle += fields[0]
                    val matches = if (expected[1] == "IGNORED") result == ParseResult.Ignored else
                        result is ParseResult.Success && digest(result.amount.amount.stripTrailingZeros().toPlainString() + "|" + result.amount.currency) == expected[3]
                    if (!matches) oracleFailures += fields[0]
                }
                when (result) {
                    is ParseResult.Success -> {
                        successes++
                        if (result.posting.state == com.athar.core.domain.model.financial.PostingState.DECLINED &&
                            DeclinedAttemptNotice055.matches(event.body)
                        ) declinedDispositions += fields[0]
                        val explicit = result.posting.state == com.athar.core.domain.model.financial.PostingState.POSTED &&
                            result.posting.support == com.athar.core.domain.model.financial.PostingSupport.EXPLICIT_POSTED
                        if (explicit) explicitPosted++
                        if (explicit && result.bankEventDate != null && result.type != com.athar.core.domain.model.TxType.TRANSFER) datedDirectional++
                        check(result.templateId.matches(Regex("[A-Za-z0-9_.-]+")))
                        val key = "${result.templateId}|${result.type}|${result.posting.state}|${result.posting.support}|dated=${result.bankEventDate != null}"
                        evidenceCoverage[key] = (evidenceCoverage[key] ?: 0) + 1
                        // Parent-local metadata only. No sender/body/amount or literal header is emitted.
                        val firstLine = event.body.lineSequence().firstOrNull().orEmpty()
                        val headerHash = digest(firstLine)
                        val moneyHash = digest(result.amount.amount.stripTrailingZeros().toPlainString() + "|" + result.amount.currency)
                        val flags = shapePatterns.filter { it.second.containsMatchIn(event.body) }
                            .joinToString(",") { it.first }
                        successIndex += listOf(fields[0], result.templateId, result.type.name,
                            result.posting.state.name, result.posting.support.name,
                            (result.bankEventDate != null).toString(), result.amount.currency,
                            flags, headerHash, moneyHash).joinToString("\t")
                    }
                    ParseResult.Ignored -> ignored++
                    is ParseResult.Failed -> failed += fields[0] + "\t" + result.templateAttempts.joinToString(",")
                }
            }
        }
        Files.createDirectories(output.parent)
        Files.write(output, failed)
        Files.write(output.resolveSibling("oracle-failures.tsv"), oracleFailures)
        Files.write(output.resolveSibling("success-index.tsv"), successIndex)
        Files.write(output.resolveSibling("declined-disposition-055.tsv"), declinedDispositions)
        Files.writeString(
            output.resolveSibling("aggregate.json"),
            """{"received":$records,"parsed":$successes,"ignored":$ignored,"failed":${failed.size},"rawBodiesWritten":0,"explicitPosted":$explicitPosted,"datedDirectional":$datedDirectional,"evidenceCoverage":{${evidenceCoverage.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}""",
        )
        assertThat(records).isEqualTo(5458)
        assertThat(successes).isEqualTo(1523 - expectedNewNotices)
        assertThat(ignored).isEqualTo(3935 + expectedNewNotices)
        assertThat(seenOracle).containsExactlyElementsIn(oracle.keys)
        assertThat(oracleFailures).isEmpty()
        assertThat(failed).isEmpty()
    }
}
