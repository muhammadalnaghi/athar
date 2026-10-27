package com.athar.feature.plan.portfolio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

internal const val MAX_STATEMENT_BYTES = 1_048_576

internal sealed interface StatementFileText {
    data class Text(val value: String) : StatementFileText
    data object TooLarge : StatementFileText
    data object Unreadable : StatementFileText
}

/** Caller opens/reads on IO. Own the stream, probe one byte beyond the bound, and never truncate. */
internal fun readPortfolioStatementText(openStream: () -> InputStream?): StatementFileText = try {
    val stream = openStream()
    if (stream == null) {
        StatementFileText.Unreadable
    } else {
        stream.use {
            val buffer = ByteArray(MAX_STATEMENT_BYTES + 1)
            var offset = 0
            while (offset < buffer.size) {
                val read = stream.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read == 0) {
                    val single = stream.read()
                    if (single < 0) break
                    buffer[offset++] = single.toByte()
                } else {
                    offset += read
                }
            }
            if (offset > MAX_STATEMENT_BYTES) {
                StatementFileText.TooLarge
            } else {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, 0, offset)).toString().removePrefix("\uFEFF")
                StatementFileText.Text(text)
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    StatementFileText.Unreadable
}
