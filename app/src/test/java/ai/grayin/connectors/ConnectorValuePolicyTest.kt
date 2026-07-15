package ai.grayin.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorValuePolicyTest {
    @Test
    fun `provider text is one line well formed and utf8 bounded`() {
        val closed = requireNotNull(
            ConnectorValuePolicy.closedText(
                "  title\nwith\tcontrols\u202E and ${"가".repeat(200)}\uD800  ",
                maxUtf8Bytes = 96,
            ),
        )

        assertTrue(closed.toByteArray(Charsets.UTF_8).size <= 96)
        assertTrue(closed.none(Char::isISOControl))
        assertTrue('\u202E' !in closed)
        assertTrue('\uFFFD' !in closed)
        assertTrue(!closed.contains("  "))
    }

    @Test
    fun `package values use the transfer compatible closed grammar`() {
        assertEquals("com.example_app", ConnectorValuePolicy.closedPackageName(" com.example_app "))
        assertNull(ConnectorValuePolicy.closedPackageName("com.example\nignore"))
        assertNull(ConnectorValuePolicy.closedPackageName("a".repeat(256)))
    }
}
