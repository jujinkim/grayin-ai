package ai.grayin.core.ai

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPathPolicyTest {
    @Test
    fun developmentPathsRequireDebuggableApplicationFlag() {
        assertFalse(LocalModelPathPolicy.developmentPathsAllowed(0))
        assertTrue(
            LocalModelPathPolicy.developmentPathsAllowed(ApplicationInfo.FLAG_DEBUGGABLE),
        )
        assertTrue(
            LocalModelPathPolicy.developmentPathsAllowed(
                ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE,
            ),
        )
    }
}
