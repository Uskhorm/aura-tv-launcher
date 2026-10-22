package tv.aura.image

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-URL single-flight gate. Callers recheck the raw file inside the gate; cancelled waiters release their reference. */
internal class RawTransfers {
    private class Gate { val mutex = Mutex(); var users = 0 }
    private val gates = mutableMapOf<String, Gate>()
    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val gate = synchronized(gates) { gates.getOrPut(key) { Gate() }.also { it.users++ } }
        try { return gate.mutex.withLock { block() } }
        finally { synchronized(gates) { if (--gate.users == 0) gates.remove(key) } }
    }
    internal fun activeKeys(): Int = synchronized(gates) { gates.size }
}
