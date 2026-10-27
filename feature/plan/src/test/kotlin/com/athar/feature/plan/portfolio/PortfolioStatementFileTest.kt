package com.athar.feature.plan.portfolio

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortfolioStatementFileTest {
    @Test fun utf8StatementPreservesArabicAndClosesOwnedStream() {
        val text = "type,eventId,note\nBUY,synthetic,محفظة تجريبية"
        val stream = TrackedStream(text.toByteArray(Charsets.UTF_8))
        assertThat(readPortfolioStatementText { stream }).isEqualTo(StatementFileText.Text(text))
        assertThat(stream.closed).isTrue()
    }

    @Test fun utf8BomIsAcceptedWithoutChangingPayloadText() {
        assertThat(readPortfolioStatementText { ByteArrayInputStream("\uFEFFtype,eventId".toByteArray()) })
            .isEqualTo(StatementFileText.Text("type,eventId"))
    }

    @Test fun exactByteLimitIsAccepted() {
        val stream = TrackedStream(ByteArray(MAX_STATEMENT_BYTES) { 'a'.code.toByte() })
        val result = readPortfolioStatementText { stream } as StatementFileText.Text
        assertThat(result.value.length).isEqualTo(MAX_STATEMENT_BYTES)
        assertThat(stream.closed).isTrue()
    }

    @Test fun oversizedInputIsRefusedRatherThanTruncated() {
        val stream = TrackedStream(ByteArray(MAX_STATEMENT_BYTES + 10))
        assertThat(readPortfolioStatementText { stream }).isEqualTo(StatementFileText.TooLarge)
        assertThat(stream.available()).isEqualTo(9)
        assertThat(stream.closed).isTrue()
    }

    @Test fun absentProviderStreamIsUnreadableNotOversized() {
        assertThat(readPortfolioStatementText { null }).isEqualTo(StatementFileText.Unreadable)
    }

    @Test fun malformedUtf8IsNotSilentlyReplaced() {
        val stream = TrackedStream(byteArrayOf(0xC3.toByte(), 0x28))
        assertThat(readPortfolioStatementText { stream }).isEqualTo(StatementFileText.Unreadable)
        assertThat(stream.closed).isTrue()
    }

    @Test fun providerFailureIsRecoverableAndClosesStream() {
        val stream = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic provider failure")
            override fun close() { closed = true }
        }
        assertThat(readPortfolioStatementText { stream }).isEqualTo(StatementFileText.Unreadable)
        assertThat(stream.closed).isTrue()
        assertThat(readPortfolioStatementText { throw SecurityException() }).isEqualTo(StatementFileText.Unreadable)
    }

    @Test fun cancellationPropagatesAndClosesStream() {
        val stream = object : InputStream() {
            var closed = false
            override fun read(): Int = throw CancellationException()
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) { readPortfolioStatementText { stream } }
        assertThat(stream.closed).isTrue()
    }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
