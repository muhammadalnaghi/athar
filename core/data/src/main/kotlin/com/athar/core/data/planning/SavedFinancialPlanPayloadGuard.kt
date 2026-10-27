package com.athar.core.data.planning

import android.util.JsonReader
import android.util.JsonToken
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import java.io.IOException
import java.io.StringReader

/**
 * WM028 fast-store: strict pre-parse guard over the raw saved-plan payload.
 *
 * This is the production binding behind the [SavedFinancialPlanPayloadGuard]
 * seam, built on the platform's standard [android.util.JsonReader] token
 * stream (strict mode) — the same proven mechanism as the catalog and backup
 * guards. It is NOT a replacement JSON parser and performs NO semantic
 * inspection: feature lanes own payload-version codecs.
 *
 * Enforced structurally, all BEFORE the document is parsed by anything else:
 *  - the root must be a JSON object (arrays/primitives are refused);
 *  - duplicate object keys are refused (a tree parser silently keeps the last
 *    one, so this is only visible on the raw token stream);
 *  - nesting deeper than [com.athar.core.data.planning.SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_DEPTH]
 *    is refused DURING the walk — the reader is an iterative pushdown lexer,
 *    so a hostile deep document can never reach a recursive parser;
 *  - each object/array is bounded by MAX_PAYLOAD_MEMBERS entries and every
 *    string, number literal and name by MAX_PAYLOAD_STRING_CHARS;
 *  - exactly one complete root object followed by end-of-document.
 *
 * Failure messages are fixed constants — payload fragments are never echoed.
 *
 * Platform note: android.util.JsonReader only executes on-device (and in the
 * native instrumented suite). The JVM suite exercises the identical refusal
 * contract through the seam, mirroring the established catalog-guard split.
 */
internal object JsonReaderSavedFinancialPlanPayloadGuard : SavedFinancialPlanPayloadGuard {

    override fun validate(payloadJson: String) {
        val budgets = "depth ${SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_DEPTH}, " +
            "members ${SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS}, " +
            "text ${SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_STRING_CHARS}"
        JsonReader(StringReader(payloadJson)).use { reader ->
            try {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    throw SavedFinancialPlanValidationException()
                }
                walk(reader, depth = 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw SavedFinancialPlanValidationException()
                }
            } catch (e: SavedFinancialPlanValidationException) {
                throw e
            } catch (_: IOException) {
                // Reader EOF/malformed diagnostics can embed document paths;
                // replace them with the fixed typed validation failure.
                throw SavedFinancialPlanValidationException()
            }
        }
    }

    /** Depth is checked on every container entry, before descending. */
    private fun walk(reader: JsonReader, depth: Int) {
        if (depth > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_DEPTH) {
            throw SavedFinancialPlanValidationException()
        }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val seenKeys = HashSet<String>()
                var members = 0
                while (reader.hasNext()) {
                    if (++members > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS) {
                        throw SavedFinancialPlanValidationException()
                    }
                    val name = reader.nextName()
                    checkText(name)
                    if (!seenKeys.add(name)) {
                        // Duplicate keys only exist on the raw stream: a parsed
                        // tree silently keeps the last value.
                        throw SavedFinancialPlanValidationException()
                    }
                    walk(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var members = 0
                while (reader.hasNext()) {
                    if (++members > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS) {
                        throw SavedFinancialPlanValidationException()
                    }
                    walk(reader, depth + 1)
                }
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> {
                // Number literals materialize as text, so digit floods are
                // bounded exactly like strings.
                checkText(reader.nextString())
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            JsonToken.END_DOCUMENT -> throw SavedFinancialPlanValidationException()
            else -> throw SavedFinancialPlanValidationException()
        }
    }

    private fun checkText(text: String) {
        if (text.length > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_STRING_CHARS) {
            throw SavedFinancialPlanValidationException()
        }
    }
}

/**
 * The raw-payload guard seam of the saved-plan write boundary. Production
 * binds [JsonReaderSavedFinancialPlanPayloadGuard]; the JVM suite substitutes
 * an equivalent kotlinx-based guard so the shared refusal contract is proven
 * without a device, exactly like the catalog guard seam.
 */
internal fun interface SavedFinancialPlanPayloadGuard {
    fun validate(payloadJson: String)
}
