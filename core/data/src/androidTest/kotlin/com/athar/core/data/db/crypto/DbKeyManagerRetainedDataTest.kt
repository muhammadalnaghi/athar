package com.athar.core.data.db.crypto

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real key-creation entry point; no real database or key is used. */
@RunWith(AndroidJUnit4::class)
class DbKeyManagerRetainedDataTest {
    @Test
    fun missingWrappedKeyRefusesBeforeCreatingAReplacementAndPreservesArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = Files.createTempDirectory(context.cacheDir.toPath(), "wm028-key-preflight-").toFile()
        try {
            val database = File(sandbox, "retained-sentinel.db")
            val wal = File(sandbox, "retained-sentinel.db-wal")
            val databaseBytes = "synthetic-retained-pages".toByteArray()
            val walBytes = "synthetic-retained-wal".toByteArray()
            database.writeBytes(databaseBytes)
            wal.writeBytes(walBytes)
            val scopedContext = object : ContextWrapper(context) {
                override fun getFilesDir(): File = sandbox
                override fun getDatabasePath(name: String): File = database
            }

            val refusal = runCatching { DbKeyManager(scopedContext).getOrCreateDbKey() }.exceptionOrNull()

            assertThat(refusal).isInstanceOf(
                RetainedDataPreflight.RetainedDatabaseWithoutKeyException::class.java,
            )
            assertThat(database.readBytes()).isEqualTo(databaseBytes)
            assertThat(wal.readBytes()).isEqualTo(walBytes)
            assertThat(File(sandbox, "db_key.bin").exists()).isFalse()
            assertThat(sandbox.listFiles().orEmpty().map { it.name }).containsExactly(
                database.name,
                wal.name,
            )
        } finally {
            // Only this freshly allocated test directory is owned by the fixture.
            sandbox.deleteRecursively()
        }
    }
}
