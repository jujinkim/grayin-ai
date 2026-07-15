package ai.grayin.connectors.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes notification consent changes with direct listener-derived writes. */
object NotificationConsentCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock { block() }
}
