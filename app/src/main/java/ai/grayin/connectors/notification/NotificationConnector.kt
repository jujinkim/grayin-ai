package ai.grayin.connectors.notification

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import java.time.Instant

class NotificationConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = isListenerEnabled(context)
        val consentEnabled = isSourceEnabled(context)
        val enabled = permissionGranted && consentEnabled
        val lastIndexedAt = prefs(context).getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
            consentEnabled = consentEnabled,
            availability = when {
                enabled -> SourceAvailability.AVAILABLE
                permissionGranted -> SourceAvailability.DISABLED
                else -> SourceAvailability.MISSING_PERMISSION
            },
            permissionGranted = permissionGranted,
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = when {
                !enabled -> ProcessingState.SKIPPED
                lastIndexedAt != null -> ProcessingState.COMPLETED
                else -> ProcessingState.STALE
            },
            lastIndexedAt = lastIndexedAt,
        )
    }

    override suspend fun permissionState(): ConnectorPermissionState {
        val permissionGranted = isListenerEnabled(context)
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = listOf(BIND_NOTIFICATION_LISTENER_SERVICE),
            explanation = if (permissionGranted) {
                "Notification listener access is available. Invoke this source before indexing new notifications."
            } else {
                "Enable Grayin notification listener access in Android settings to index future notification signals."
            },
        )
    }

    override suspend fun invoke(): ConnectorPermissionState {
        return NotificationConsentCoordinator.withExclusiveAccess {
            val permissionState = permissionState()
            if (permissionState.permissionGranted) {
                check(prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()) {
                    "Could not persist notification connector consent."
                }
            }
            permissionState
        }
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        return when {
            !isListenerEnabled(context) -> skipped(
                now,
                SourceAvailability.MISSING_PERMISSION,
                ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED,
            )

            !isSourceEnabled(context) -> skipped(
                now,
                SourceAvailability.DISABLED,
                ConnectorScanIssueCode.SOURCE_NOT_INVOKED,
            )

            else -> skipped(
                now,
                SourceAvailability.NOT_INDEXED,
                if (NotificationAppAllowlist(context).load().isEmpty()) {
                    ConnectorScanIssueCode.NOTIFICATION_ALLOWLIST_EMPTY
                } else {
                    ConnectorScanIssueCode.NOTIFICATION_HISTORY_UNAVAILABLE
                },
            )
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        return NotificationConsentCoordinator.withExclusiveAccess {
            check(prefs(context).edit().clear().commit()) {
                "Could not clear notification connector consent."
            }
            NotificationAppAllowlist(context).replace(emptySet())
            ConnectorRevokeResult(
                connectorId = CONNECTOR_ID,
                revokedAt = Instant.now(),
                permissionState = permissionState(),
                deleteRequest = ConnectorDeleteRequest(connectorId = CONNECTOR_ID),
            )
        }
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }

    private fun skipped(
        scannedAt: Instant,
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.SKIPPED,
            missingSources = missingSources(availability, issueCode),
            scannedAt = scannedAt,
        )
    }

    private fun missingSources(
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = issueCode.defaultEnglish,
                connectorId = CONNECTOR_ID,
                issueCode = issueCode,
            )
        }
    }

    companion object {
        const val CONNECTOR_ID = "notification"
        const val BIND_NOTIFICATION_LISTENER_SERVICE = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Notifications",
            sourceKinds = setOf(SourceKind.NOTIFICATION),
            connectorCapabilities = setOf(ConnectorCapability.NOTIFICATIONS),
            memoryCapabilities = setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_PAYMENT,
                MemoryCapability.HAS_DELIVERY,
                MemoryCapability.HAS_RESERVATION,
                MemoryCapability.HAS_TRANSPORT,
                MemoryCapability.HAS_TEXT,
            ),
            indexingMode = ConnectorIndexingMode.EVENT_DRIVEN,
            defaultEnabled = false,
            sensitivity = SensitivityLevel.VERY_HIGH,
        )

        private const val PREFS_NAME = "grayin_notifications"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"

        fun isSourceEnabled(context: Context): Boolean {
            return prefs(context).getBoolean(KEY_ENABLED, false)
        }

        fun markIndexed(context: Context, indexedAt: Instant) {
            prefs(context).edit().putString(KEY_LAST_INDEXED_AT, indexedAt.toString()).apply()
        }

        fun isListenerEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GrayinNotificationListenerService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(":")
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
