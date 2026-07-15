package ai.grayin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectorSettingsAccessTest {
    @Test
    fun `usage and notification map to fixed settings destinations`() {
        assertEquals(
            ConnectorSettingsDestination.USAGE_ACCESS,
            ConnectorSettingsAccessState.destinationForConnector("app_usage"),
        )
        assertEquals(
            ConnectorSettingsDestination.NOTIFICATION_LISTENER_ACCESS,
            ConnectorSettingsAccessState.destinationForConnector("notification"),
        )
        assertNull(ConnectorSettingsAccessState.destinationForConnector("location"))
    }

    @Test
    fun `returned connector is consumed exactly once`() {
        val pending = ConnectorSettingsAccessState().begin("app_usage")

        val first = pending.consumeReturn()
        val second = first.nextState.consumeReturn()

        assertEquals("app_usage", first.connectorId)
        assertNull(second.connectorId)
        assertNull(second.nextState.pendingConnectorId)
    }

    @Test
    fun `unsupported connector cannot become pending`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorSettingsAccessState().begin("calendar")
        }
    }

    @Test
    fun `a second launch cannot replace the connector awaiting return`() {
        val pending = ConnectorSettingsAccessState().begin("app_usage")

        assertThrows(IllegalArgumentException::class.java) {
            pending.begin("notification")
        }
        assertEquals("app_usage", pending.consumeReturn().connectorId)
    }
}
