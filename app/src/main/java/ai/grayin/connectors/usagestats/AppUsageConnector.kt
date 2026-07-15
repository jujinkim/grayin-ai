package ai.grayin.connectors.usagestats

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class AppUsageConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasUsageAccess()
        val enabled = permissionGranted && isEnabled()
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
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
        val permissionGranted = hasUsageAccess()
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = listOf(Manifest.permission.PACKAGE_USAGE_STATS),
            explanation = if (permissionGranted) {
                "Usage access is available. Invoke this source before indexing."
            } else {
                "Grant usage access in Android settings to invoke app usage summaries for indexing."
            },
        )
    }

    override suspend fun invoke(): ConnectorPermissionState {
        val permissionState = permissionState()
        if (permissionState.permissionGranted) {
            prefs().edit().putBoolean(KEY_ENABLED, true).apply()
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (!hasUsageAccess()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED)
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, ConnectorScanIssueCode.SOURCE_NOT_INVOKED)
        }

        val until = scope.until ?: now
        val from = scope.from ?: until.minus(DEFAULT_USAGE_WINDOW)
        val rows = readUsageRows(from, until, now)
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = if (rows.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = rows.map { it.sourceReference },
            derivedEvents = rows.map { it.derivedEvent },
            citations = rows.map { it.citation },
            missingSources = if (rows.isEmpty()) {
                missingSources(SourceAvailability.NOT_INDEXED, ConnectorScanIssueCode.NO_APP_USAGE_IN_RANGE)
            } else {
                emptyList()
            },
            scopeFrom = from,
            scopeUntil = until,
            scannedAt = now,
        )
    }

    override suspend fun onScanStored(scanResult: ConnectorScanResult) {
        if (scanResult.processingState == ProcessingState.COMPLETED) {
            prefs().edit().putString(KEY_LAST_INDEXED_AT, scanResult.scannedAt.toString()).apply()
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        prefs().edit().clear().apply()
        return ConnectorRevokeResult(
            connectorId = CONNECTOR_ID,
            revokedAt = Instant.now(),
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = CONNECTOR_ID),
        )
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }

    private fun readUsageRows(
        from: Instant,
        until: Instant,
        observedAt: Instant,
    ): List<AppUsageExtractionResult> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            from.toEpochMilli(),
            until.toEpochMilli(),
        ).orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .sortedByDescending { it.totalTimeInForeground }
            .take(MAX_USAGE_ROWS)
            .map { it.toExtractionResult(observedAt) }
    }

    private fun UsageStats.toExtractionResult(observedAt: Instant): AppUsageExtractionResult {
        val sourceHash = sha256("$packageName:$firstTimeStamp:$lastTimeStamp:$totalTimeInForeground")
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val firstSeenAt = Instant.ofEpochMilli(firstTimeStamp)
        val lastSeenAt = Instant.ofEpochMilli(lastTimeStamp)
        val minutes = (totalTimeInForeground / MILLIS_PER_MINUTE).coerceAtLeast(1L)
        val appAlias = appAlias(packageName)
        return AppUsageExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.APP_USAGE,
                externalIdHash = sourceHash,
                sourceAppIdentifier = packageName,
                observedAt = observedAt,
                modifiedAt = lastSeenAt,
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.APP_USAGE,
                sourceReferenceIds = listOf(sourceId),
                summary = "App usage indexed: $appAlias used for about $minutes minute(s) between $firstSeenAt and $lastSeenAt.",
                startedAt = firstSeenAt,
                endedAt = lastSeenAt,
                keywords = keywords(listOf(packageName, appAlias).joinToString(" ")),
                labels = listOf("app-usage", usageBucket(minutes)),
                entities = listOf(packageName),
                confidence = ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.VERY_HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "App usage: $appAlias",
                observedAt = observedAt,
                confidence = ConfidenceLevel.MEDIUM,
            ),
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

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appAlias(packageName: String): String {
        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private fun usageBucket(minutes: Long): String {
        return when {
            minutes >= 180 -> "long-session"
            minutes >= 30 -> "medium-session"
            else -> "short-session"
        }
    }

    private fun keywords(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH && it !in STOP_WORDS }
            .distinct()
            .take(MAX_KEYWORDS)
            .toList()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private data class AppUsageExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
    )

    companion object {
        const val CONNECTOR_ID = "app_usage"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "App Usage",
            sourceKinds = setOf(SourceKind.APP_USAGE),
            connectorCapabilities = setOf(ConnectorCapability.APP_USAGE),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_APP_USAGE),
            indexingMode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            defaultEnabled = false,
            sensitivity = SensitivityLevel.VERY_HIGH,
        )

        private const val PREFS_NAME = "grayin_app_usage"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val MAX_USAGE_ROWS = 100
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MAX_KEYWORDS = 16
        private const val HASH_ID_CHARS = 32
        private val DEFAULT_USAGE_WINDOW = Duration.ofDays(7)
        private val WORD_PATTERN = Regex("[\\p{L}\\p{Nd}]+")
        private val STOP_WORDS = setOf("com", "android", "google")
    }
}
