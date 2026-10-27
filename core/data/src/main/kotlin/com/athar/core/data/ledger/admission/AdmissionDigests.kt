package com.athar.core.data.ledger.admission

import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Standard JCA SHA-256 only: separate original-bytes and exact codec-JSON
 * digests, plus unambiguous canonical conflict-ID tuples encoded as standard
 * JSON lists (never delimiter concatenation, clocks or random identities).
 */
internal object AdmissionDigests {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val tupleSerializer = ListSerializer(String.serializer())
    private val detailSerializer = MapSerializer(String.serializer(), String.serializer())

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Deterministic conflict ID over a canonical JSON list of tuple parts. */
    fun canonicalTupleId(parts: List<String>): String =
        "adm-" + sha256Hex(json.encodeToString(tupleSerializer, parts))

    fun detailJson(detail: Map<String, String>): String =
        json.encodeToString(detailSerializer, detail)
}
