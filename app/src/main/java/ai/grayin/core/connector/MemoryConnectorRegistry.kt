package ai.grayin.core.connector

class MemoryConnectorRegistry(connectors: List<MemoryConnector>) {
    val all: List<MemoryConnector> = connectors.toList()

    private val connectorsById: Map<String, MemoryConnector>

    init {
        require(all.all { connector -> connector.metadata.connectorId.isNotBlank() }) {
            "Connector IDs must not be blank."
        }
        val duplicateIds = all.groupingBy { connector -> connector.metadata.connectorId }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Connector IDs must be unique: ${duplicateIds.sorted().joinToString()}."
        }
        connectorsById = all.associateBy { connector -> connector.metadata.connectorId }
    }

    fun find(connectorId: String): MemoryConnector? = connectorsById[connectorId]

    fun require(connectorId: String): MemoryConnector {
        return requireNotNull(find(connectorId)) { "Connector not found: $connectorId." }
    }
}
