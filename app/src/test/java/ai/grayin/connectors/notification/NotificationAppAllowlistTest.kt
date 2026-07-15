package ai.grayin.connectors.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAppAllowlistTest {
    @Test
    fun parseNormalizesAndDeduplicatesPackageNames() {
        val result = NotificationAppAllowlist.parse(
            "com.Example.Pay, com.example.chat\ncom.example.pay",
        )

        assertEquals(setOf("com.example.chat", "com.example.pay"), result.allowedPackages)
        assertEquals(emptyList<String>(), result.invalidEntries)
    }

    @Test
    fun parseRejectsNonPackageValues() {
        val result = NotificationAppAllowlist.parse("com.example.pay https://example.com plain")

        assertEquals(setOf("com.example.pay"), result.allowedPackages)
        assertEquals(listOf("https://example.com", "plain"), result.invalidEntries)
    }
}
