package com.athar.core.data.backup

import com.athar.core.domain.repo.BackupRepository
import com.athar.core.domain.repo.WidgetRefresher
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Adds projection invalidation only; authenticated replacement remains owned by BackupService. */
@Singleton
internal class WidgetRefreshingBackupRepository internal constructor(
    private val delegate: BackupRepository,
    private val widgets: WidgetRefresher,
) : BackupRepository by delegate {
    @Inject constructor(service: BackupService, widgets: WidgetRefresher) : this(service as BackupRepository, widgets)

    override suspend fun import(bytes: ByteArray, passphrase: CharArray) {
        delegate.import(bytes, passphrase)
        refreshCommittedRestore()
    }

    override suspend fun import(input: InputStream, passphrase: CharArray) {
        delegate.import(input, passphrase)
        refreshCommittedRestore()
    }
    private suspend fun refreshCommittedRestore() {
        // Unlike regular edits, whole replacement must not return with only a
        // process-local delayed request: the caller can finish immediately.
        try {
            widgets.refreshNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The financial transaction already committed. A projection failure
            // must not falsely report an authentication/storage restore failure.
            Timber.w("Widget refresh deferred after committed backup restore")
            widgets.requestRefresh()
        }
    }
}
