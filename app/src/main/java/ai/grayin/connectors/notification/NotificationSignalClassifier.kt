package ai.grayin.connectors.notification

import android.app.Notification
import ai.grayin.core.model.NotificationDerivedEventKind
import java.util.Locale

object NotificationSignalClassifier {
    fun classify(text: String, category: String?): NotificationDerivedEventKind {
        val normalized = listOfNotNull(text, category).joinToString(" ").lowercase(Locale.ROOT)
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

            category == Notification.CATEGORY_MESSAGE -> NotificationDerivedEventKind.MESSAGE_HINT
            else -> NotificationDerivedEventKind.OTHER
        }
    }

    private fun String.anyContains(vararg needles: String): Boolean {
        return needles.any(::contains)
    }
}
