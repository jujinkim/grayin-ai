package ai.grayin.connectors.notification

import android.app.Notification
import ai.grayin.connectors.ConnectorValuePolicy
import ai.grayin.core.model.NotificationDerivedEventKind
import java.util.Locale

object NotificationSignalClassifier {
    fun classify(text: String, category: String?): NotificationDerivedEventKind {
        val closedCategory = NotificationValuePolicy.closedCategory(category)
        val normalized = listOfNotNull(
            ConnectorValuePolicy.closedText(text, NotificationValuePolicy.MAX_TRANSIENT_TEXT_BYTES),
            closedCategory,
        ).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            normalized.anyContains("otp", "verification", "인증", "確認コード", "security code") -> {
                NotificationDerivedEventKind.SECURITY_HINT
            }

            normalized.anyContains("paid", "payment", "card", "결제", "支払い") -> {
                NotificationDerivedEventKind.PAYMENT
            }

            normalized.anyContains("delivery", "delivered", "shipping", "배달", "배송", "配達") -> {
                NotificationDerivedEventKind.DELIVERY
            }

            normalized.anyContains("reservation", "booking", "예약", "予約") -> {
                NotificationDerivedEventKind.RESERVATION
            }

            normalized.anyContains("taxi", "train", "flight", "bus", "ride", "택시", "기차", "버스") -> {
                NotificationDerivedEventKind.TRANSPORT
            }

            closedCategory == Notification.CATEGORY_MESSAGE -> NotificationDerivedEventKind.MESSAGE_HINT
            else -> NotificationDerivedEventKind.OTHER
        }
    }

    private fun String.anyContains(vararg needles: String): Boolean {
        return needles.any(::contains)
    }
}

internal object NotificationValuePolicy {
    const val MAX_TRANSIENT_TEXT_BYTES = 4 * 1024

    fun closedPackageName(value: String?): String? {
        return ConnectorValuePolicy.closedPackageName(value)
    }

    fun closedCategory(value: String?): String? {
        return value?.trim()?.lowercase(Locale.ROOT)?.takeIf(ALLOWED_CATEGORIES::contains)
    }

    fun closedSourceTag(value: String?): String? {
        return ConnectorValuePolicy.closedText(value, MAX_SOURCE_TAG_BYTES)
    }

    fun closedTransientText(values: List<CharSequence?>): ClosedNotificationTransientText {
        val output = StringBuilder()
        var remainingBytes = MAX_TRANSIENT_TEXT_BYTES
        var truncated = false
        values.forEach { value ->
            if (value == null) return@forEach
            if (remainingBytes <= 0) {
                if (value.isNotEmpty()) truncated = true
                return@forEach
            }
            if (value.length > MAX_TRANSIENT_FIELD_CHARS) truncated = true
            val limited = value.subSequence(0, minOf(value.length, MAX_TRANSIENT_FIELD_CHARS)).toString()
            val separatorBytes = if (output.isEmpty()) 0 else 1
            if (remainingBytes <= separatorBytes) {
                if (limited.isNotBlank()) truncated = true
                return@forEach
            }
            val availableValueBytes = remainingBytes - separatorBytes
            if (limited.toByteArray(Charsets.UTF_8).size > availableValueBytes) truncated = true
            val closed = ConnectorValuePolicy.closedText(limited, availableValueBytes)
                ?: return@forEach
            if (separatorBytes == 1) output.append(' ')
            output.append(closed)
            val usedBytes = separatorBytes + closed.toByteArray(Charsets.UTF_8).size
            remainingBytes -= usedBytes
        }
        return ClosedNotificationTransientText(output.toString(), truncated)
    }

    private const val MAX_TRANSIENT_FIELD_CHARS = 2 * 1024
    private const val MAX_SOURCE_TAG_BYTES = 256
    private val ALLOWED_CATEGORIES = setOf(
        "alarm",
        "call",
        "email",
        "err",
        "event",
        "location_sharing",
        "missed_call",
        "msg",
        "navigation",
        "progress",
        "promo",
        "recommendation",
        "reminder",
        "service",
        "social",
        "status",
        "stopwatch",
        "sys",
        "transport",
        "voicemail",
        "workout",
    )
}

internal data class ClosedNotificationTransientText(
    val value: String,
    val truncated: Boolean,
)
