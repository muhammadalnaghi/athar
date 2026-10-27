package com.athar.ui.onboarding

import com.athar.core.domain.repo.CsvImportResult
import com.athar.core.domain.repo.CsvImportTrigger
import dagger.Lazy
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only an explicit import opens a document or requests the importer/database graph. */
internal suspend fun runDeferredOnboardingImport(
    importer: Lazy<CsvImportTrigger>,
    openInput: () -> InputStream?,
): CsvImportResult = withContext(Dispatchers.IO) {
    val input = try {
        openInput()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return@withContext CsvImportResult.Failed("Couldn't open CSV file.")
    } ?: return@withContext CsvImportResult.Failed("Couldn't open CSV file.")

    // The real importer also closes its input. Closeable.close is idempotent; this
    // outer owner additionally covers lazy construction failures and cancellation.
    try {
        input.use {
            importer.get().import(it)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CsvImportResult.Failed("Couldn't read CSV file.")
    }
}
