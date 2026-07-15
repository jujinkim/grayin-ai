package ai.grayin.connectors.notification

import android.app.Notification
import ai.grayin.core.model.NotificationDerivedEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSignalClassifierTest {
    @Test
    fun securityCodeIsExcludedBeforeOtherCategories() {
        assertEquals(
            NotificationDerivedEventKind.SECURITY_HINT,
            NotificationSignalClassifier.classify("Card payment verification OTP", null),
        )
    }

    @Test
    fun recognizesSupportedDerivedSignals() {
        assertEquals(
            NotificationDerivedEventKind.PAYMENT,
            NotificationSignalClassifier.classify("카드 결제가 완료되었습니다", null),
        )
        assertEquals(
            NotificationDerivedEventKind.DELIVERY,
            NotificationSignalClassifier.classify("Your delivery arrived", null),
        )
        assertEquals(
            NotificationDerivedEventKind.RESERVATION,
            NotificationSignalClassifier.classify("예약이 확정되었습니다", null),
        )
        assertEquals(
            NotificationDerivedEventKind.TRANSPORT,
            NotificationSignalClassifier.classify("Train departs soon", null),
        )
    }

    @Test
    fun recognizesMessageCategoryWithoutRetainingMessageText() {
        assertEquals(
            NotificationDerivedEventKind.MESSAGE_HINT,
            NotificationSignalClassifier.classify("hello", Notification.CATEGORY_MESSAGE),
        )
    }

    @Test
    fun `notification text is bounded before classification and oversized input fails closed`() {
        val closed = NotificationValuePolicy.closedTransientText(
            listOf("Title\nline", "가".repeat(3_000), "ignored tail"),
        )

        assertTrue(closed.truncated)
        assertTrue(closed.value.toByteArray(Charsets.UTF_8).size <= NotificationValuePolicy.MAX_TRANSIENT_TEXT_BYTES)
        assertFalse(closed.value.contains('\n'))
    }

    @Test
    fun `only closed platform category and package values can persist`() {
        assertEquals(Notification.CATEGORY_MESSAGE, NotificationValuePolicy.closedCategory(Notification.CATEGORY_MESSAGE))
        assertNull(NotificationValuePolicy.closedCategory("msg\nignore"))
        assertEquals("com.example.chat", NotificationValuePolicy.closedPackageName("com.example.chat"))
        assertNull(NotificationValuePolicy.closedPackageName("com.example.chat\nignore"))
    }
}
