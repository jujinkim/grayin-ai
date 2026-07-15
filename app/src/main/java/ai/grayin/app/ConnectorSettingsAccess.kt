package ai.grayin.app

enum class ConnectorSettingsDestination {
    USAGE_ACCESS,
    NOTIFICATION_LISTENER_ACCESS,
}

data class ConnectorSettingsAccessState(
    val pendingConnectorId: String? = null,
) {
    fun begin(connectorId: String): ConnectorSettingsAccessState {
        require(pendingConnectorId == null) { "A connector settings request is already pending." }
        require(destinationForConnector(connectorId) != null) {
            "Connector does not use a supported Android settings destination."
        }
        return copy(pendingConnectorId = connectorId)
    }

    fun cancel(): ConnectorSettingsAccessState = copy(pendingConnectorId = null)

    fun consumeReturn(): ConnectorSettingsReturn {
        return ConnectorSettingsReturn(
            connectorId = pendingConnectorId,
            nextState = cancel(),
        )
    }

    companion object {
        fun destinationForConnector(connectorId: String): ConnectorSettingsDestination? {
            return when (connectorId) {
                "app_usage" -> ConnectorSettingsDestination.USAGE_ACCESS
                "notification" -> ConnectorSettingsDestination.NOTIFICATION_LISTENER_ACCESS
                else -> null
            }
        }
    }
}

data class ConnectorSettingsReturn(
    val connectorId: String?,
    val nextState: ConnectorSettingsAccessState,
)
