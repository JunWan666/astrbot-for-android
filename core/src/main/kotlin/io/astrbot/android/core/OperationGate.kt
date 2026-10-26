package io.astrbot.android.core

import java.util.concurrent.atomic.AtomicReference

/** A token owns the operation; unrelated callers cannot release another operation's lock. */
class OperationGate {
    class Lease internal constructor(val name: String)
    private val current = AtomicReference<Lease?>(null)
    val activeName: String? get() = current.get()?.name

    fun acquire(name: String): Lease? {
        require(name.isNotBlank())
        val lease = Lease(name)
        return if (current.compareAndSet(null, lease)) lease else null
    }

    fun release(lease: Lease): Boolean = current.compareAndSet(lease, null)
}
