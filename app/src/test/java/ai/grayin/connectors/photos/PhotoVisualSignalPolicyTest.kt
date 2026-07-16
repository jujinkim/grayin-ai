package ai.grayin.connectors.photos

import ai.grayin.core.model.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoVisualSignalPolicyTest {
    @Test
    fun `only reviewed labels and usable confidence survive`() {
        assertEquals(
            ClosedPhotoVisualSignals(listOf("food", "drink", "person-present"), ConfidenceLevel.HIGH),
            PhotoVisualSignalPolicy.close(
                listOf("Food", "caption: a private dinner", "drink", "person-present", "outdoor"),
                ConfidenceLevel.HIGH,
            ),
        )
        assertNull(PhotoVisualSignalPolicy.close(listOf("food"), ConfidenceLevel.LOW))
        assertNull(PhotoVisualSignalPolicy.close(listOf("caption"), ConfidenceLevel.HIGH))
    }
}
