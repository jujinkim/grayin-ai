package ai.grayin.connectors.notification

import android.app.Notification
import ai.grayin.core.model.NotificationDerivedEventKind
import org.junit.Assert.assertEquals
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
}
