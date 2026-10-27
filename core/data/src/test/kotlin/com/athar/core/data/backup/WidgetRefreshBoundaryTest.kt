package com.athar.core.data.backup

import com.athar.core.data.planning.WidgetRefreshingSavedPlanRepository
import com.athar.core.domain.repo.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class WidgetRefreshBoundaryTest {
    private class Refresh : WidgetRefresher {
        var requested = 0
        var immediate = 0
        var immediateFailure: Exception? = null
        val count get() = requested + immediate
        override fun requestRefresh() { requested++ }
        override suspend fun refreshNow() { immediate++; immediateFailure?.let { throw it } }
    }
    private class Backup : BackupRepository {
        var fail: Exception? = null
        var imports = 0
        override suspend fun export(output: OutputStream, passphrase: CharArray) { output.write(1) }
        override suspend fun import(bytes: ByteArray, passphrase: CharArray) { fail?.let { throw it }; imports++ }
        override suspend fun import(input: InputStream, passphrase: CharArray) { fail?.let { throw it }; imports++ }
    }
    private class Plans : SavedFinancialPlanRepository {
        var fail: Exception? = null
        val stored = SavedFinancialPlan("synthetic",SavedFinancialPlanKind.CASH,"Synthetic","SAR",1,"{}",2,1,2)
        var savedRevision: Long? = null
        override fun observe(kind: SavedFinancialPlanKind) = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
            fail?.let { throw it };savedRevision=expectedRevision;return stored
        }
        override suspend fun delete(id: String, expectedRevision: Long) { fail?.let { throw it } }
    }
    @Test fun `both authenticated import overloads notify only after delegate success`() = runTest {
        val source=Backup();val refresh=Refresh();val repo=WidgetRefreshingBackupRepository(source,refresh)
        repo.import(byteArrayOf(1),charArrayOf());assertThat(source.imports).isEqualTo(1);assertThat(refresh.count).isEqualTo(1)
        repo.import(ByteArrayInputStream(byteArrayOf(2)),charArrayOf());assertThat(source.imports).isEqualTo(2);assertThat(refresh.count).isEqualTo(2)
        repo.export(ByteArrayOutputStream(),charArrayOf());assertThat(refresh.count).isEqualTo(2)
        assertThat(refresh.immediate).isEqualTo(2);assertThat(refresh.requested).isEqualTo(0)
    }
    @Test fun `refused and cancelled imports never notify and preserve exact exception`() = runTest {
        val source=Backup();val refresh=Refresh();val repo=WidgetRefreshingBackupRepository(source,refresh)
        for (error in listOf(IllegalArgumentException("synthetic"),CancellationException("synthetic"))) {
            source.fail=error
            var caught: Exception?=null
            try { repo.import(byteArrayOf(1),charArrayOf()) } catch (e: Exception) { caught=e }
            assertThat(caught).isSameInstanceAs(error)
            caught=null
            try { repo.import(ByteArrayInputStream(byteArrayOf(1)),charArrayOf()) } catch(e: Exception) { caught=e }
            assertThat(caught).isSameInstanceAs(error)
        }
        assertThat(refresh.count).isEqualTo(0)
    }
    @Test fun `saved plan write returns exact delegate revision then refreshes and delete refreshes`() = runTest {
        val source=Plans();val refresh=Refresh();val repo=WidgetRefreshingSavedPlanRepository(source,refresh)
        val result=repo.save(source.stored.copy(revision=0),1)
        assertThat(result).isSameInstanceAs(source.stored);assertThat(source.savedRevision).isEqualTo(1)
        assertThat(refresh.count).isEqualTo(1)
        repo.delete(result.id,2);assertThat(refresh.count).isEqualTo(2)
        repo.get(result.id);repo.observe(result.kind);assertThat(refresh.count).isEqualTo(2)
        assertThat(refresh.requested).isEqualTo(2);assertThat(refresh.immediate).isEqualTo(0)
    }
    @Test fun `projection failure after commit does not become financial restore failure`() = runTest {
        val source=Backup();val refresh=Refresh();refresh.immediateFailure=IllegalStateException("synthetic")
        WidgetRefreshingBackupRepository(source,refresh).import(byteArrayOf(1),charArrayOf())
        assertThat(source.imports).isEqualTo(1);assertThat(refresh.immediate).isEqualTo(1);assertThat(refresh.requested).isEqualTo(1)
    }
    @Test fun `post commit projection cancellation remains cancellation`() = runTest {
        val source=Backup();val refresh=Refresh();val error=CancellationException("synthetic");refresh.immediateFailure=error
        var caught:Exception?=null
        try { WidgetRefreshingBackupRepository(source,refresh).import(byteArrayOf(1),charArrayOf()) } catch(e:Exception) { caught=e }
        assertThat(caught).isSameInstanceAs(error);assertThat(source.imports).isEqualTo(1);assertThat(refresh.requested).isEqualTo(0)
    }
    @Test fun `saved plan conflicts and cancellation never request refresh`() = runTest {
        val source=Plans();val refresh=Refresh();val repo=WidgetRefreshingSavedPlanRepository(source,refresh)
        for(error in listOf(SavedFinancialPlanConflictException(),CancellationException("synthetic"))) {
            source.fail=error
            var caught:Exception?=null
            try { repo.save(source.stored,1) } catch(e:Exception) { caught=e }
            assertThat(caught).isSameInstanceAs(error)
            caught=null
            try { repo.delete(source.stored.id,1) } catch(e:Exception) { caught=e }
            assertThat(caught).isSameInstanceAs(error)
        }
        assertThat(refresh.count).isEqualTo(0)
    }
}
