package com.athar.core.data.db.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * WM028-13B0: the key-creation preflight must refuse to run over retained
 * database artifacts and must leave every artifact byte-identical with no new
 * file written (no replacement key). Uses real synthetic temporary files only.
 */
class RetainedDataPreflightTest {

    @TempDir
    lateinit var tempDir: File

    private fun writeArtifact(name: String, bytes: ByteArray): File =
        File(tempDir, name).apply { writeBytes(bytes) }

    private fun snapshotDirectory(): Map<String, ByteArray> =
        tempDir.listFiles().orEmpty().associate { it.name to it.readBytes() }

    private fun assertDirectoryUnchangedSince(before: Map<String, ByteArray>) {
        val after = snapshotDirectory()
        assertThat(after.keys).isEqualTo(before.keys)
        before.forEach { (name, bytes) -> assertThat(after.getValue(name)).isEqualTo(bytes) }
    }

    @Test
    fun `retained database with sidecars blocks key creation and stays byte-identical`() {
        val database = writeArtifact("sentinel.db", "sentinel-db-page-image".toByteArray())
        writeArtifact("sentinel.db-wal", "wal-frames".toByteArray())
        writeArtifact("sentinel.db-shm", "shm-index".toByteArray())
        writeArtifact("sentinel.db-journal", "hot-journal".toByteArray())
        val before = snapshotDirectory()

        val error = assertThrows<RetainedDataPreflight.RetainedDatabaseWithoutKeyException> {
            RetainedDataPreflight.requireNoRetainedDatabaseArtifacts(database)
        }

        assertThat(error.artifacts.map { it.name }).containsExactly(
            "sentinel.db",
            "sentinel.db-wal",
            "sentinel.db-shm",
            "sentinel.db-journal",
        ).inOrder()
        // Nothing deleted, nothing rewritten, no key file or temp file created.
        assertDirectoryUnchangedSince(before)
    }

    @Test
    fun `orphan sidecar without a main database file still blocks key creation`() {
        val journal = writeArtifact("sentinel.db-journal", "leftover-journal".toByteArray())
        val missingMainFile = File(tempDir, "sentinel.db")
        val before = snapshotDirectory()

        val error = assertThrows<RetainedDataPreflight.RetainedDatabaseWithoutKeyException> {
            RetainedDataPreflight.requireNoRetainedDatabaseArtifacts(missingMainFile)
        }

        assertThat(error.artifacts.map { it.name }).containsExactly("sentinel.db-journal")
        assertThat(error).hasMessageThat().contains(journal.path)
        assertDirectoryUnchangedSince(before)
    }

    @Test
    fun `zero-length database file still blocks - existence is the trigger not size`() {
        val database = writeArtifact("sentinel.db", ByteArray(0))
        val before = snapshotDirectory()

        assertThrows<RetainedDataPreflight.RetainedDatabaseWithoutKeyException> {
            RetainedDataPreflight.requireNoRetainedDatabaseArtifacts(database)
        }

        assertDirectoryUnchangedSince(before)
    }

    @Test
    fun `genuinely empty installation allows first key creation`() {
        val database = File(tempDir, "sentinel.db")

        RetainedDataPreflight.requireNoRetainedDatabaseArtifacts(database)

        assertThat(snapshotDirectory()).isEmpty()
    }

    @Test
    fun `sidecar naming matches SQLite journal and WAL conventions`() {
        val sidecars = RetainedDataPreflight.sidecarFiles(File(tempDir, "names-only.db"))

        assertThat(sidecars.map { it.path }).containsExactly(
            File(tempDir, "names-only.db-wal").path,
            File(tempDir, "names-only.db-shm").path,
            File(tempDir, "names-only.db-journal").path,
        ).inOrder()
    }
}
