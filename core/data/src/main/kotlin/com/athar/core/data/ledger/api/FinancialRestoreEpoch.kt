package com.athar.core.data.ledger.api

import java.util.concurrent.atomic.AtomicReference

/** Opaque process-lifetime token for in-flight recording; never restored from a backup. */
class FinancialRecordingSession internal constructor(internal val identity: Any)

/** Process death cancels old work; whole-store replacement invalidates surviving coroutines. */
internal object FinancialRestoreEpoch {
    private val identity = AtomicReference(Any())
    fun capture() = FinancialRecordingSession(identity.get())
    fun isCurrent(session: FinancialRecordingSession) = identity.get() === session.identity
    fun invalidate() { identity.set(Any()) }
}
