package com.athar.core.data.backup.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * JVM coverage for [StrictBackupJsonGuard] is deliberately limited to the
 * budget preconditions: they are checked before `android.util.JsonReader` is
 * constructed, so they run honestly on the JVM. This suite is NOT proof of the
 * guard's structural behavior — the real JsonReader token walk (duplicates,
 * depth, tokens, strings, versions, END_DOCUMENT) is proven only by the
 * native instrumented suite in `src/androidTest`, as the lane contract
 * requires.
 */
class StrictBackupJsonGuardBudgetTest {

    @Test
    fun `rejects a nonpositive depth budget before any parsing`() {
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = 0, maxTokens = 8, maxStringChars = 8)
        }).hasMessageThat().contains("depth budget must be positive")
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = -2, maxTokens = 8, maxStringChars = 8)
        }).hasMessageThat().contains("depth budget must be positive")
    }

    @Test
    fun `rejects a nonpositive token budget before any parsing`() {
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = 2, maxTokens = 0, maxStringChars = 8)
        }).hasMessageThat().contains("token budget must be positive")
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = 2, maxTokens = -1, maxStringChars = 8)
        }).hasMessageThat().contains("token budget must be positive")
    }

    @Test
    fun `rejects a nonpositive string char budget before any parsing`() {
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = 2, maxTokens = 8, maxStringChars = 0)
        }).hasMessageThat().contains("string char budget must be positive")
        assertThat(assertThrows<IllegalArgumentException> {
            StrictBackupJsonGuard.validate(json = "{}", maxDepth = 2, maxTokens = 8, maxStringChars = -5)
        }).hasMessageThat().contains("string char budget must be positive")
    }
}
