package com.athar.core.data.catalog

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/**
 * Strict pre-parse guard over the raw JSON text: rejects duplicate object keys,
 * nesting deeper than [validate]'s depth budget, before any untrusted structure
 * reaches kotlinx.serialization. Uses the platform's standard JsonReader token
 * stream — this is not a replacement JSON parser.
 *
 * Platform note: android.util.JsonReader is only exercisable on-device; the JVM
 * suite covers shape/strictness via kotlinx strict mode and injects a no-op
 * guard through the [CatalogJsonGuard] seam.
 */
internal fun interface CatalogJsonGuard {
    fun validate(json: String, maxDepth: Int)
}

internal object JsonReaderCatalogJsonGuard : CatalogJsonGuard {

    override fun validate(json: String, maxDepth: Int) {
        JsonReader(StringReader(json)).use { reader ->
            walk(reader, depth = 0, maxDepth = maxDepth)
        }
    }

    private fun walk(reader: JsonReader, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) throw CatalogGuardException("JSON nesting deeper than $maxDepth")
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val seenKeys = HashSet<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!seenKeys.add(name)) throw CatalogGuardException("duplicate JSON key '$name'")
                    walk(reader, depth + 1, maxDepth)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    walk(reader, depth + 1, maxDepth)
                }
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }
}

internal class CatalogGuardException(message: String) : RuntimeException(message)
