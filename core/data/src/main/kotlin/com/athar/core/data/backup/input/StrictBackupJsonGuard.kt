package com.athar.core.data.backup.input

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.StringReader

/**
 * Strict pre-parse guard over raw backup envelope JSON: bounds nesting depth,
 * total token count and individual string/name lengths, rejects duplicate keys
 * at every depth, requires exactly one complete top-level object followed by
 * [android.util.JsonToken.END_DOCUMENT], and pins the root `version` to an
 * explicit numeric 1..4 before any envelope parsing runs. Built on the
 * platform's standard [JsonReader] token stream — this is not a replacement
 * JSON parser.
 *
 * This is a syntax/resource/version guard only. It deliberately does not
 * perform referential validation (field presence beyond `version`,
 * canonicalLedger completeness, value ranges) — that belongs to the
 * backup-service lane. Failure messages name only budgets and structure; they
 * never echo payload fragments.
 *
 * Platform note: [JsonReader] is only exercisable on-device. The JVM suite may
 * only prove the budget preconditions (checked before the reader is built);
 * every structural behavior here is proven by the native instrumented suite.
 */
internal object StrictBackupJsonGuard {

    const val MIN_VERSION = 1
    const val MAX_VERSION = 4

    private const val VERSION_FIELD = "version"

    /**
     * Validates [json] against the given budgets and returns the envelope's
     * root version (1..4). [maxDepth] bounds the depth of any value — the root
     * object sits at depth 0, its members at depth 1, and so on; nothing may
     * sit deeper than [maxDepth] (the catalog guard engine's exact semantics).
     * [maxTokens] bounds the total number of consumed JSON
     * tokens (objects, arrays, names and values; [android.util.JsonToken.END_DOCUMENT]
     * is a terminator check, not a counted token); [maxStringChars] bounds each
     * individual string value, number literal and name.
     */
    fun validate(json: String, maxDepth: Int, maxTokens: Long, maxStringChars: Int): Int {
        require(maxDepth > 0) { "depth budget must be positive" }
        require(maxTokens > 0) { "token budget must be positive" }
        require(maxStringChars > 0) { "string char budget must be positive" }
        JsonReader(StringReader(json)).use { reader ->
            try {
                if (peekRoot(reader) != JsonToken.BEGIN_OBJECT) {
                    throw StrictBackupGuardException("backup envelope root must be a JSON object")
                }
                val state = GuardState(maxTokens = maxTokens, maxStringChars = maxStringChars)
                val version = walkObject(reader, depth = 0, maxDepth = maxDepth, state = state, isRoot = true)
                    ?: throw StrictBackupGuardException("backup envelope is missing a numeric root version")
                if (peekTrailing(reader) != JsonToken.END_DOCUMENT) {
                    throw StrictBackupGuardException("trailing content after the backup envelope root object")
                }
                return version
            } catch (e: StrictBackupGuardException) {
                throw e
            } catch (_: IOException) {
                // The reader's own malformed/EOF messages can embed document
                // paths (payload fragments); replace them with a fixed message.
                // The original exception is deliberately not chained: its own
                // message would leak the same document paths via getCause().
                throw StrictBackupGuardException("malformed or truncated backup JSON")
            }
        }
    }

    /**
     * The two structural peeks can throw before any token comparison happens
     * on a real strict [JsonReader]: an empty or whitespace-only document
     * hits EOF inside the root peek, and any non-whitespace bytes after the
     * root object make the END_DOCUMENT peek throw instead of returning a
     * token. At these two checkpoints such a failure IS the structural
     * rejection, so it is classified with the same fixed message a token
     * mismatch would produce — not the generic malformed-nested-document
     * diagnostic — with no chained cause and no lenient mode. Peeks inside
     * the walked document keep the generic classification.
     */
    private fun peekRoot(reader: JsonReader): JsonToken =
        try {
            reader.peek()
        } catch (_: IOException) {
            throw StrictBackupGuardException("backup envelope root must be a JSON object")
        }

    private fun peekTrailing(reader: JsonReader): JsonToken =
        try {
            reader.peek()
        } catch (_: IOException) {
            throw StrictBackupGuardException("trailing content after the backup envelope root object")
        }

    /** Walks an object; returns the root version only when [isRoot] is set. */
    private fun walkObject(
        reader: JsonReader,
        depth: Int,
        maxDepth: Int,
        state: GuardState,
        isRoot: Boolean,
    ): Int? {
        if (depth > maxDepth) {
            throw StrictBackupGuardException("JSON nesting deeper than $maxDepth")
        }
        state.consumeToken()
        reader.beginObject()
        val seenKeys = HashSet<String>()
        var version: Int? = null
        while (reader.hasNext()) {
            state.consumeToken()
            val name = reader.nextName()
            state.checkText(name, "name")
            if (!seenKeys.add(name)) {
                throw StrictBackupGuardException("duplicate JSON key")
            }
            if (isRoot && name == VERSION_FIELD) {
                version = readVersion(reader, state)
            } else {
                walkValue(reader, depth + 1, maxDepth, state)
            }
        }
        reader.endObject()
        state.consumeToken()
        return version
    }

    private fun walkArray(reader: JsonReader, depth: Int, maxDepth: Int, state: GuardState) {
        if (depth > maxDepth) {
            throw StrictBackupGuardException("JSON nesting deeper than $maxDepth")
        }
        state.consumeToken()
        reader.beginArray()
        while (reader.hasNext()) {
            walkValue(reader, depth + 1, maxDepth, state)
        }
        reader.endArray()
        state.consumeToken()
    }

    private fun walkValue(reader: JsonReader, depth: Int, maxDepth: Int, state: GuardState) {
        if (depth > maxDepth) {
            throw StrictBackupGuardException("JSON nesting deeper than $maxDepth")
        }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> walkObject(reader, depth, maxDepth, state, isRoot = false)
            JsonToken.BEGIN_ARRAY -> walkArray(reader, depth, maxDepth, state)
            JsonToken.STRING -> {
                state.consumeToken()
                val value = reader.nextString()
                state.checkText(value, "string")
            }
            JsonToken.NUMBER -> {
                state.consumeToken()
                // Number literals are materialized as text by the reader, so
                // they are bounded like strings to stop digit-flood payloads.
                val literal = reader.nextString()
                state.checkText(literal, "number")
            }
            JsonToken.BOOLEAN -> {
                state.consumeToken()
                reader.nextBoolean()
            }
            JsonToken.NULL -> {
                state.consumeToken()
                reader.nextNull()
            }
            else -> throw StrictBackupGuardException("unexpected JSON structure")
        }
    }

    private fun readVersion(reader: JsonReader, state: GuardState): Int {
        if (reader.peek() != JsonToken.NUMBER) {
            throw StrictBackupGuardException("backup version must be a JSON number")
        }
        state.consumeToken()
        val literal = reader.nextString()
        state.checkText(literal, "string")
        // Parse as Int directly: Long has no toIntOrNull, and toLong().toInt()
        // would silently wrap out-of-range literals (4294967297 -> 1). The
        // range-exact String.toIntOrNull rejects huge, fractional and exponent
        // literals as non-integers instead of wrapping into 1..4.
        val parsed = literal.toIntOrNull()
            ?: throw StrictBackupGuardException("backup version must be a plain integer")
        if (parsed < MIN_VERSION || parsed > MAX_VERSION) {
            throw StrictBackupGuardException(
                "backup version must be between $MIN_VERSION and $MAX_VERSION",
            )
        }
        return parsed
    }

    private class GuardState(private val maxTokens: Long, val maxStringChars: Int) {
        private var tokens: Long = 0

        fun consumeToken() {
            tokens += 1
            if (tokens > maxTokens) {
                throw StrictBackupGuardException("JSON token budget of $maxTokens exceeded")
            }
        }

        fun checkText(text: String, kind: String) {
            if (text.length > maxStringChars) {
                throw StrictBackupGuardException(
                    "JSON $kind exceeds the budget of $maxStringChars chars",
                )
            }
        }
    }
}

/** Raised only for guard rejections; messages never contain payload fragments. */
internal class StrictBackupGuardException(message: String) : RuntimeException(message)
