package com.athar.feature.plan.portfolio

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local explicit CSV/JSON statement import — pure parsing + preview + merge.
 *
 * Exact supported columns (CSV header row REQUIRED, exact order; JSON rows use the
 * same keys; every value must be a plain string, decimals WITHOUT grouping):
 *   type,eventId,date,securityId,securityName,assetClass,quantity,unitPrice,fee,amount,destination,note
 * `type` (exact, uppercase):
 *   BUY | SELL            → eventId,date,securityId,quantity,unitPrice,fee(optional, default 0)
 *   CONTRIBUTION|WITHDRAWAL|FEE → eventId,date,amount
 *   CASH_DIVIDEND         → eventId,date,securityId,amount,destination(PORTFOLIO_CASH|EXTERNAL_ACCOUNT)
 *   REINVESTMENT          → eventId,date,securityId,amount,unitPrice,quantity
 *   QUOTE                 → date,securityId,unitPrice (zero allowed: known-worthless; no eventId)
 * `securityName`/`assetClass` register an unknown security inline (assetClass:
 * EQUITY|SUKUK|CASH|OTHER or blank = unclassified). Everything else is previewed and
 * applied only as a full-database-grade merged document; duplicates are skipped and
 * listed, hard errors disable Apply. No network, no quotes fetched, no date repair.
 */
data class ImportRow(val lineNumber: Int, val fields: Map<String, String>)

enum class StatementProblem { EMPTY_INPUT, BAD_HEADER, BAD_FIELD_TYPE }

enum class RowProblem {
    BAD_COLUMN_COUNT,
    BAD_QUOTING,
    UNKNOWN_TYPE,
    MISSING_EVENT_ID,
    MISSING_DATE,
    BAD_DATE,
    MISSING_SECURITY,
    MISSING_POSITIVE_NUMBER,
    BAD_DESTINATION,
    UNKNOWN_SECURITY,
    BAD_ASSET_CLASS,
    DUPLICATE_EVENT_ID,
    DUPLICATE_QUOTE,
}

data class RowError(val lineNumber: Int, val problem: RowProblem)

sealed interface StatementParseResult {
    data class Success(val rows: List<ImportRow>, val parseErrors: List<RowError>) : StatementParseResult
    data class Failed(val problem: StatementProblem) : StatementParseResult
}

data class ImportPreview(
    val validEventCount: Int,
    val validQuoteCount: Int,
    val newSecurities: List<SecurityInput>,
    val duplicateEventIds: List<String>,
    val duplicateQuoteKeys: List<String>,
    val errors: List<RowError>,
    val merged: PortfolioPlanDocument?,
    /** Safe rejection token when the merged document cannot build/replay; Apply stays disabled. */
    val rejection: String?,
    val canApply: Boolean,
)

object PortfolioStatementImport {

    val COLUMNS = listOf(
        "type", "eventId", "date", "securityId", "securityName", "assetClass",
        "quantity", "unitPrice", "fee", "amount", "destination", "note",
    )
    private val COLUMN_COUNT = COLUMNS.size
    private const val COL_TYPE = "type"
    private val eventTypes = setOf("BUY", "SELL", "CONTRIBUTION", "WITHDRAWAL", "FEE", "CASH_DIVIDEND", "REINVESTMENT")
    private val quoteType = "QUOTE"
    private val destinations = setOf("PORTFOLIO_CASH", "EXTERNAL_ACCOUNT")
    private val assetClasses = setOf("EQUITY", "SUKUK", "CASH", "OTHER")

    fun parseCsv(text: String): StatementParseResult {
        val trimmed = text.trim('\uFEFF')
        if (trimmed.isBlank()) return StatementParseResult.Failed(StatementProblem.EMPTY_INPUT)
        val lines = trimmed.split("\r\n", "\n").toMutableList()
        if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return StatementParseResult.Failed(StatementProblem.EMPTY_INPUT)
        if (lines.first() != COLUMNS.joinToString(",")) return StatementParseResult.Failed(StatementProblem.BAD_HEADER)

        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<RowError>()
        lines.drop(1).forEachIndexed { index, line ->
            val lineNumber = index + 2
            if (line.isBlank()) return@forEachIndexed
            val fields = splitCsvLine(line)
            when {
                fields == null -> errors += RowError(lineNumber, RowProblem.BAD_QUOTING)
                fields.size > COLUMN_COUNT -> errors += RowError(lineNumber, RowProblem.BAD_COLUMN_COUNT)
                // Common export shape: trailing empty columns may be omitted. Pad,
                // never truncate; nothing past the header's last column is invented.
                fields.size < COLUMN_COUNT ->
                    rows += ImportRow(
                        lineNumber,
                        COLUMNS.zip(fields + List(COLUMN_COUNT - fields.size) { "" }).toMap(),
                    )
                else -> rows += ImportRow(lineNumber, COLUMNS.zip(fields).toMap())
            }
        }
        return StatementParseResult.Success(rows, errors)
    }

    fun parseJsonRows(text: String): StatementParseResult {
        if (text.isBlank()) return StatementParseResult.Failed(StatementProblem.EMPTY_INPUT)
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return StatementParseResult.Failed(StatementProblem.BAD_FIELD_TYPE)
        }
        if (root !is JsonArray) return StatementParseResult.Failed(StatementProblem.BAD_FIELD_TYPE)
        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<RowError>()
        root.forEachIndexed { index, element ->
            val lineNumber = index + 1
            val obj = element as? JsonObject
            if (obj == null || obj.keys.any { it !in COLUMNS } || obj.keys.none { it == COL_TYPE }) {
                errors += RowError(lineNumber, RowProblem.BAD_COLUMN_COUNT)
                return@forEachIndexed
            }
            val values = mutableMapOf<String, String>()
            var badType = false
            for ((key, value) in obj) {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    badType = true
                    break
                }
                values[key] = primitive.content
            }
            if (badType) errors += RowError(lineNumber, RowProblem.BAD_QUOTING) else rows += ImportRow(lineNumber, values)
        }
        return StatementParseResult.Success(rows, errors)
    }

    /** Validates rows against [current] and returns the full preview, merged document included. */
    fun preview(current: PortfolioPlanDocument, parse: StatementParseResult): ImportPreview {
        val (rows, parseErrors) = when (parse) {
            is StatementParseResult.Success -> parse.rows to parse.parseErrors
            is StatementParseResult.Failed -> return ImportPreview(0, 0, emptyList(), emptyList(), emptyList(), emptyList(), null, parse.problem.name, false)
        }
        val errors = parseErrors.toMutableList()
        val existingEventIds = current.events.map { it.eventId }.toMutableSet()
        val existingQuotes = current.quotes.map { it.securityId + "@" + it.date }.toMutableSet()
        val knownSecurities = current.securities.associateBy { it.securityId }.toMutableMap()

        val newSecurities = mutableListOf<SecurityInput>()
        val validEvents = mutableListOf<EventInput>()
        val validQuotes = mutableListOf<QuoteInput>()
        val duplicateEventIds = mutableListOf<String>()
        val duplicateQuoteKeys = mutableListOf<String>()

        for (row in rows) {
            val f = row.fields
            val type = f[COL_TYPE]?.trim() ?: ""
            if (type !in eventTypes && type != quoteType) {
                errors += RowError(row.lineNumber, RowProblem.UNKNOWN_TYPE)
                continue
            }
            val date = f["date"]?.trim().orEmpty()
            val dateOk = date.isNotEmpty() && runCatching { kotlinx.datetime.LocalDate.parse(date) }.isSuccess
            if (!dateOk) {
                errors += RowError(row.lineNumber, if (date.isEmpty()) RowProblem.MISSING_DATE else RowProblem.BAD_DATE)
                continue
            }
            if (type == quoteType) {
                val securityId = f["securityId"]?.trim().orEmpty()
                if (securityId.isEmpty()) {
                    errors += RowError(row.lineNumber, RowProblem.MISSING_SECURITY)
                    continue
                }
                val unitPrice = f["unitPrice"]?.trim().orEmpty()
                val quotePrice = decimal(unitPrice)
                if (quotePrice == null || quotePrice.signum() < 0) {
                    errors += RowError(row.lineNumber, RowProblem.MISSING_POSITIVE_NUMBER)
                    continue
                }
                val key = securityId + "@" + date
                if (!registerSecurity(current, f, knownSecurities, newSecurities)) {
                    errors += RowError(row.lineNumber, securityError(f))
                    continue
                }
                if (!existingQuotes.add(key)) {
                    duplicateQuoteKeys += key
                    continue
                }
                validQuotes += QuoteInput(securityId, date, unitPrice)
                continue
            }

            val eventId = f["eventId"]?.trim().orEmpty()
            if (eventId.isEmpty()) {
                errors += RowError(row.lineNumber, RowProblem.MISSING_EVENT_ID)
                continue
            }
            if (!existingEventIds.add(eventId)) {
                duplicateEventIds += eventId
                continue
            }
            val securityId = f["securityId"]?.trim().orEmpty()
            val needsSecurity = type == "BUY" || type == "SELL" || type == "CASH_DIVIDEND" || type == "REINVESTMENT"
            if (needsSecurity && securityId.isEmpty()) {
                errors += RowError(row.lineNumber, RowProblem.MISSING_SECURITY)
                continue
            }
            if (needsSecurity && !registerSecurity(current, f, knownSecurities, newSecurities)) {
                errors += RowError(row.lineNumber, securityError(f))
                continue
            }
            val destination = f["destination"]?.trim().orEmpty()
            if (type == "CASH_DIVIDEND" && destination !in destinations) {
                errors += RowError(row.lineNumber, RowProblem.BAD_DESTINATION)
                continue
            }
            val quantity = positiveOrZero(f["quantity"])
            val unitPrice = f["unitPrice"]?.trim().orEmpty()
            val fee = f["fee"]?.trim().orEmpty()
            val amount = positiveOrZero(f["amount"])
            val priceValue = decimal(unitPrice)
            val feeValue = if (fee.isEmpty()) BigDecimal.ZERO else decimal(fee)
            val shapeOk = when (type) {
                "BUY", "SELL" -> quantity != null && priceValue != null && priceValue.signum() > 0 &&
                    feeValue != null && feeValue.signum() >= 0
                "CONTRIBUTION", "WITHDRAWAL", "FEE" -> amount != null
                "CASH_DIVIDEND" -> amount != null
                "REINVESTMENT" -> amount != null && priceValue != null && priceValue.signum() > 0 && quantity != null
                else -> false
            }
            if (!shapeOk) {
                errors += RowError(row.lineNumber, RowProblem.MISSING_POSITIVE_NUMBER)
                continue
            }
            validEvents += EventInput(
                kind = PortfolioEntryKind.valueOf(type),
                eventId = eventId,
                date = date,
                securityId = securityId.ifEmpty { null },
                quantity = quantity?.toPlainString(),
                unitPrice = unitPrice.ifEmpty { null },
                fee = fee.ifEmpty { null },
                amount = amount?.toPlainString(),
                destination = destination.ifEmpty { null },
                note = f["note"]?.trim()?.ifEmpty { null },
            )
        }

        val merged = if (errors.isEmpty()) {
            current.copy(
                securities = current.securities + newSecurities,
                events = (current.events + validEvents).sortedBy { it.date },
                quotes = current.quotes + validQuotes,
            )
        } else {
            null
        }
        val rejection = merged?.let {
            if (encodeLoadablePortfolio(it) == null) "UNSUPPORTED_EVENT_FIELDS"
            else PortfolioWorkbench.build(it).rejection
        }
        val validCount = validEvents.size + validQuotes.size
        return ImportPreview(
            validEventCount = validEvents.size,
            validQuoteCount = validQuotes.size,
            newSecurities = newSecurities,
            duplicateEventIds = duplicateEventIds,
            duplicateQuoteKeys = duplicateQuoteKeys,
            errors = errors,
            merged = merged,
            rejection = rejection,
            canApply = merged != null && rejection == null && validCount > 0,
        )
    }

    /** Registers an unknown security inline when the row carries an explicit name; returns false on failure. */
    private fun registerSecurity(
        current: PortfolioPlanDocument,
        fields: Map<String, String>,
        known: MutableMap<String, SecurityInput>,
        newSecurities: MutableList<SecurityInput>,
    ): Boolean {
        val id = fields["securityId"]?.trim().orEmpty()
        if (known.containsKey(id) || current.securities.any { it.securityId == id }) return true
        val name = fields["securityName"]?.trim().orEmpty()
        if (name.isEmpty()) return false
        val assetClassRaw = fields["assetClass"]?.trim().orEmpty()
        val assetClass = when {
            assetClassRaw.isEmpty() -> null
            assetClassRaw in assetClasses -> assetClassRaw
            else -> return false
        }
        val security = SecurityInput(id, name, assetClass)
        known[id] = security
        newSecurities += security
        return true
    }

    private fun securityError(fields: Map<String, String>): RowProblem {
        val assetClassRaw = fields["assetClass"]?.trim().orEmpty()
        val nameMissing = fields["securityName"]?.trim().isNullOrEmpty()
        return when {
            nameMissing -> RowProblem.UNKNOWN_SECURITY
            assetClassRaw.isNotEmpty() && assetClassRaw !in assetClasses -> RowProblem.BAD_ASSET_CLASS
            else -> RowProblem.UNKNOWN_SECURITY
        }
    }

    private fun positiveOrZero(raw: String?): BigDecimal? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return decimal(trimmed)?.takeIf { it.signum() >= 0 }
    }

    private fun decimal(raw: String?): BigDecimal? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        return trimmed.toBigDecimalOrNull()
    }

    private fun splitCsvLine(line: String): List<String>? {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        sb.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }
            } else {
                when {
                    c == '"' -> if (sb.isEmpty()) inQuotes = true else return null
                    c == ',' -> {
                        out += sb.toString()
                        sb.clear()
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (inQuotes) return null
        out += sb.toString()
        return out
    }
}
