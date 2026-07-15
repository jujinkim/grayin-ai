package ai.grayin.connectors.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.NotificationDerivedEventKind
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GrayinNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractor = NotificationSignalExtractor()
    private val allowlist by lazy { NotificationAppAllowlist(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            NotificationConsentCoordinator.withExclusiveAccess {
                val store = SqlCipherLocalMemoryStore(applicationContext)
                if (store.isConnectorReconsentRequired(NotificationConnector.CONNECTOR_ID)) {
                    return@withExclusiveAccess
                }
                if (!NotificationConnector.isSourceEnabled(applicationContext)) return@withExclusiveAccess
                if (!allowlist.isAllowed(sbn.packageName)) return@withExclusiveAccess
                val result = extractor.extract(sbn) ?: return@withExclusiveAccess
                try {
                    store.saveConnectorScan(
                        ConnectorScanResult(
                            connectorId = NotificationConnector.CONNECTOR_ID,
                            processingState = ProcessingState.COMPLETED,
                            sourceReferences = listOf(result.sourceReference),
                            derivedEvents = listOf(result.derivedEvent),
                            citations = listOf(result.citation),
                            scannedAt = result.indexedAt,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withExclusiveAccess
                }
                NotificationConnector.markIndexed(applicationContext, result.indexedAt)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private data class NotificationExtractionResult(
    val sourceReference: SourceReference,
    val derivedEvent: DerivedMemoryEvent,
    val citation: MemoryCitation,
    val indexedAt: Instant,
)

private class NotificationSignalExtractor {
    fun extract(sbn: StatusBarNotification): NotificationExtractionResult? {
        val postedAt = Instant.ofEpochMilli(sbn.postTime)
        val indexedAt = Instant.now()
        val packageName = NotificationValuePolicy.closedPackageName(sbn.packageName) ?: return null
        val category = NotificationValuePolicy.closedCategory(sbn.notification.category)
        val transientText = notificationText(sbn.notification)
        if (transientText.truncated) return null
        val kind = NotificationSignalClassifier.classify(
            text = transientText.value,
            category = category,
        )
        if (kind == NotificationDerivedEventKind.SECURITY_HINT) return null
        val sourceTag = NotificationValuePolicy.closedSourceTag(sbn.tag).orEmpty()
        val sourceHash = sha256("$packageName:${sbn.id}:$sourceTag:${sbn.postTime}")
        val sourceId = "source:${NotificationConnector.CONNECTOR_ID}:$sourceHash"
        val eventId = "event:${NotificationConnector.CONNECTOR_ID}:$sourceHash"
        val citationId = "citation:${NotificationConnector.CONNECTOR_ID}:$sourceHash"
        val derivedKind = when (kind) {
            NotificationDerivedEventKind.PAYMENT -> DerivedMemoryEventKind.PAYMENT
            NotificationDerivedEventKind.DELIVERY -> DerivedMemoryEventKind.DELIVERY
            NotificationDerivedEventKind.RESERVATION -> DerivedMemoryEventKind.RESERVATION
            NotificationDerivedEventKind.TRANSPORT -> DerivedMemoryEventKind.TRANSPORT
            else -> DerivedMemoryEventKind.INFERRED_CONTEXT
        }
        val kindLabel = kind.name.lowercase().replace('_', '-')
        return NotificationExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = NotificationConnector.CONNECTOR_ID,
                sourceKind = SourceKind.NOTIFICATION,
                externalIdHash = sourceHash,
                sourceAppIdentifier = packageName,
                observedAt = indexedAt,
                modifiedAt = postedAt,
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = derivedKind,
                sourceReferenceIds = listOf(sourceId),
                summary = "Notification-derived $kindLabel signal from $packageName at $postedAt.",
                startedAt = postedAt,
                keywords = keywords(packageName, kindLabel),
                labels = listOfNotNull("notification", kindLabel, category),
                entities = listOf(packageName),
                confidence = if (kind == NotificationDerivedEventKind.OTHER) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.VERY_HIGH,
                citationIds = listOf(citationId),
                createdAt = indexedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "Notification signal: $packageName",
                observedAt = indexedAt,
                confidence = if (kind == NotificationDerivedEventKind.OTHER) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
            ),
            indexedAt = indexedAt,
        )
    }

    private fun notificationText(notification: Notification): ClosedNotificationTransientText {
        val extras = notification.extras
        return NotificationValuePolicy.closedTransientText(
            listOf(
                extras?.getCharSequence(Notification.EXTRA_TITLE),
                extras?.getCharSequence(Notification.EXTRA_TEXT),
                extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ),
        )
    }

    private fun keywords(packageName: String, kindLabel: String): List<String> {
        return (packageName.split('.') + kindLabel.split('-') + "notification")
            .map { it.lowercase() }
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private companion object {
        const val HASH_ID_CHARS = 32
        const val MAX_KEYWORDS = 16
    }
}
