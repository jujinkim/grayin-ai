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
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GrayinNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractor = NotificationSignalExtractor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationConnector.isSourceEnabled(applicationContext)) return
        val result = extractor.extract(sbn) ?: return
        scope.launch {
            val store = SqlCipherLocalMemoryStore(applicationContext)
            store.saveSourceReferences(listOf(result.sourceReference))
            store.saveDerivedMemoryEvents(listOf(result.derivedEvent))
            store.saveCitations(listOf(result.citation))
            NotificationConnector.markIndexed(applicationContext, result.indexedAt)
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
        val kind = classify(sbn.notification)
        if (kind == NotificationDerivedEventKind.SECURITY_HINT) return null
        val sourceHash = sha256("${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}:${sbn.postTime}")
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
                sourceAppIdentifier = sbn.packageName,
                observedAt = indexedAt,
                modifiedAt = postedAt,
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = derivedKind,
                sourceReferenceIds = listOf(sourceId),
                summary = "Notification-derived $kindLabel signal from ${sbn.packageName} at $postedAt.",
                startedAt = postedAt,
                keywords = keywords(sbn.packageName, kindLabel),
                labels = listOf("notification", kindLabel, sbn.notification.category.orEmpty()).filter { it.isNotBlank() },
                entities = listOf(sbn.packageName),
                confidence = if (kind == NotificationDerivedEventKind.OTHER) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.VERY_HIGH,
                citationIds = listOf(citationId),
                createdAt = indexedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "Notification signal: ${sbn.packageName}",
                observedAt = indexedAt,
                confidence = if (kind == NotificationDerivedEventKind.OTHER) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
            ),
            indexedAt = indexedAt,
        )
    }

    private fun classify(notification: Notification): NotificationDerivedEventKind {
        val extras = notification.extras
        val text = listOfNotNull(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            notification.category,
        ).joinToString(" ").lowercase()
        return when {
            text.anyContains("otp", "verification", "인증", "確認コード", "security code") -> {
                NotificationDerivedEventKind.SECURITY_HINT
            }

            text.anyContains("paid", "payment", "card", "결제", "支払い") -> NotificationDerivedEventKind.PAYMENT
            text.anyContains("delivery", "delivered", "shipping", "배달", "배송", "配達") -> {
                NotificationDerivedEventKind.DELIVERY
            }

            text.anyContains("reservation", "booking", "예약", "予約") -> NotificationDerivedEventKind.RESERVATION
            text.anyContains("taxi", "train", "flight", "bus", "ride", "택시", "기차", "버스") -> {
                NotificationDerivedEventKind.TRANSPORT
            }

            notification.category == Notification.CATEGORY_MESSAGE -> NotificationDerivedEventKind.MESSAGE_HINT
            else -> NotificationDerivedEventKind.OTHER
        }
    }

    private fun keywords(packageName: String, kindLabel: String): List<String> {
        return (packageName.split('.') + kindLabel.split('-') + "notification")
            .map { it.lowercase() }
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun String.anyContains(vararg needles: String): Boolean {
        return needles.any { contains(it) }
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
