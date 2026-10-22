package tv.aura.image

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RawTransfersTest {
    @Test fun concurrentSizesDownloadOnlyOnce() = runBlocking {
        val transfers = RawTransfers()
        var present = false
        var downloads = 0
        (1..100).map { async(Dispatchers.Default) {
            transfers.withKey("same-url") {
                if (!present) { delay(10); downloads++; present = true }
            }
        } }.awaitAll()
        assertEquals(1, downloads)
        assertEquals(0, transfers.activeKeys())
    }
    @Test fun slowUrlDoesNotBlockAnotherUrlAndCancelledWaitersCleanUp() = runBlocking {
        val transfers = RawTransfers()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { transfers.withKey("slow") { entered.complete(Unit); release.await() } }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { transfers.withKey("slow") { fail("Cancelled waiter ran") } }
        withTimeout(1000) { transfers.withKey("fast") { } }
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertEquals(0, transfers.activeKeys())
    }
}
