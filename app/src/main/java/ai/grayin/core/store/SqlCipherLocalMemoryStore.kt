package ai.grayin.core.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.connector.hasIndexableOutput
import ai.grayin.core.connector.missingSourceIdentity
import ai.grayin.core.indexing.AutomaticIndexingControl
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStatus
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStore
import ai.grayin.core.indexing.ConnectorScanCommitResult
import ai.grayin.core.indexing.ConnectorScanWriter
import ai.grayin.core.indexing.IndexingQueue
import ai.grayin.core.indexing.IndexingQueueItem
import ai.grayin.core.indexing.IndexingQueueSnapshot
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingQueueValidator
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTask
import ai.grayin.core.indexing.IndexingTrigger
import ai.grayin.core.indexing.LeaseRecoveryResult
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceReference
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

class SqlCipherLocalMemoryStore(
    private val context: Context,
    private val passphraseProvider: StorePassphraseProvider = AndroidKeystorePassphraseProvider(),
    private val databaseName: String = DB_NAME,
    private val clock: Clock = Clock.systemUTC(),
) : LocalMemoryStore, IndexingQueue, AutomaticIndexingRuntimeStore, ConnectorScanWriter {
    override suspend fun saveConnectorScan(scanResult: ConnectorScanResult): StoreWriteResult = withDatabase { db ->
        db.beginTransaction()
        try {
            requireConnectorWriteAllowed(db, scanResult.connectorId)
            val result = writeConnectorScanRows(db, scanResult, completedAt = clock.instant())
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun commitClaimedConnectorScan(
        scanResult: ConnectorScanResult,
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
    ): ConnectorScanCommitResult = withDatabase { db ->
        require(itemId.isNotBlank()) { "Indexing item ID must not be blank." }
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        require(attemptCount > 0) { "Attempt count must be positive." }
        requireValidConnectorId(scanResult.connectorId)
        db.beginTransaction()
        try {
            val completedAt = clock.instant()
            val current = readQueueItem(db, itemId)
            val automaticControlIsCurrent = current?.task?.let { task ->
                task.trigger != IndexingTrigger.AUTOMATIC || readAutomaticIndexingControl(db).let { control ->
                    control.enabled && task.automaticGeneration == control.generation
                }
            } == true
            val ownsLiveLease = current?.state == IndexingQueueState.RUNNING &&
                current.leaseOwner == leaseOwner &&
                current.attemptCount == attemptCount &&
                current.leaseUntil?.isAfter(completedAt) == true &&
                automaticControlIsCurrent &&
                !isConnectorReconsentRequired(db, scanResult.connectorId)
            if (!ownsLiveLease) {
                db.setTransactionSuccessful()
                return@withDatabase ConnectorScanCommitResult.LeaseLost
            }

            require(scanResult.connectorId == current.connectorId) {
                "Connector scan does not belong to the claimed indexing task."
            }
            writeConnectorScanRows(db, scanResult, completedAt)
            val hasIndexableOutput = scanResult.hasIndexableOutput()
            val updated = checkNotNull(current).copy(
                state = if (hasIndexableOutput) IndexingQueueState.COMPLETED else IndexingQueueState.SKIPPED,
                indexedEventCount = if (hasIndexableOutput) scanResult.derivedEvents.size else 0,
                completedAt = completedAt,
                leaseOwner = null,
                leaseUntil = null,
                skipReason = if (hasIndexableOutput) null else IndexingSkipReason.NO_INDEXABLE_DATA,
            )
            val count = db.update(
                TABLE_INDEXING_QUEUE,
                updated.toValues(),
                "id = ? AND state = ? AND lease_owner = ? AND attempt_count = ? AND lease_until_ms > ?",
                arrayOf(
                    itemId,
                    IndexingQueueState.RUNNING.name,
                    leaseOwner,
                    attemptCount.toString(),
                    completedAt.toEpochMilli().toString(),
                ),
            )
            check(count == 1) { "Indexing lease changed before the derived scan committed." }
            db.setTransactionSuccessful()
            ConnectorScanCommitResult.Committed(updated)
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun saveDailySummaries(summaries: List<DailyMemorySummary>): StoreWriteResult {
        return writeRows(summaries) { db, summary ->
            db.insertWithOnConflict(TABLE_DAILY_SUMMARIES, null, summary.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun savePlaceClusters(clusters: List<PlaceCluster>): StoreWriteResult {
        return writeRows(clusters) { db, cluster ->
            db.insertWithOnConflict(TABLE_PLACE_CLUSTERS, null, cluster.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun saveAppUsageSummaries(summaries: List<AppUsageSummary>): StoreWriteResult {
        return writeRows(summaries) { db, summary ->
            db.insertWithOnConflict(TABLE_APP_USAGE_SUMMARIES, null, summary.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun deleteConnectorData(
        connectorId: String,
        requireReconsent: Boolean,
    ): StoreDeleteResult = withDatabase { db ->
        requireValidConnectorId(connectorId)
        db.beginTransaction()
        try {
            val completedAt = clock.instant()
            fenceActiveConnectorTasks(db, connectorId, completedAt)
            val result = deleteConnectorRows(db, connectorId, completedAt = completedAt)
            if (requireReconsent) {
                writeConnectorReconsentRequirement(db, connectorId, completedAt)
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun invalidateIndexesAfterDelete(
        request: IndexInvalidationRequest,
    ): IndexInvalidationResult {
        return IndexInvalidationResult(
            connectorId = request.connectorId,
            invalidatedIndexIds = request.derivedMemoryEventIds,
            completedAt = Instant.now(),
        )
    }

    override suspend fun loadSourceReferences(connectorId: String?): List<SourceReference> = withDatabase { db ->
        readSourceReferences(db, connectorId)
    }

    override suspend fun loadDerivedMemoryEvents(): List<DerivedMemoryEvent> = withDatabase { db ->
        readDerivedMemoryEvents(db)
    }

    override suspend fun loadCitations(): List<MemoryCitation> = withDatabase { db ->
        readCitations(db)
    }

    override suspend fun loadDailySummaries(): List<DailyMemorySummary> = withDatabase { db ->
        readDailySummaries(db)
    }

    override suspend fun loadPlaceClusters(): List<PlaceCluster> = withDatabase { db ->
        readPlaceClusters(db)
    }

    override suspend fun loadAppUsageSummaries(): List<AppUsageSummary> = withDatabase { db ->
        readAppUsageSummaries(db)
    }

    override suspend fun loadConnectorScanStatuses(): List<ConnectorScanStatus> = withDatabase { db ->
        readConnectorScanStatuses(db)
    }

    override suspend fun loadSnapshot(): LocalMemorySnapshot = withDatabase { db ->
        db.beginTransaction()
        try {
            val snapshot = LocalMemorySnapshot(
                sourceReferences = readSourceReferences(db, connectorId = null),
                derivedMemoryEvents = readDerivedMemoryEvents(db),
                citations = readCitations(db),
                dailySummaries = readDailySummaries(db),
                placeClusters = readPlaceClusters(db),
                appUsageSummaries = readAppUsageSummaries(db),
                connectorScanStatuses = readConnectorScanStatuses(db),
            )
            db.setTransactionSuccessful()
            snapshot
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun isConnectorReconsentRequired(connectorId: String): Boolean = withDatabase { db ->
        requireValidConnectorId(connectorId)
        isConnectorReconsentRequired(db, connectorId)
    }

    override suspend fun markConnectorReconsented(connectorId: String): Boolean = withDatabase { db ->
        requireValidConnectorId(connectorId)
        db.beginTransaction()
        try {
            val cleared = db.delete(
                TABLE_CONNECTOR_RECONSENT,
                "connector_id = ?",
                arrayOf(connectorId),
            ) == 1
            db.setTransactionSuccessful()
            cleared
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun replaceDerivedDataFromImport(
        snapshot: LocalMemorySnapshot,
        trustedConnectorIds: Set<String>,
        importedAt: Instant,
    ): StoreImportResult {
        validateImportedSnapshotBounds(snapshot, trustedConnectorIds)
        val trustedIds = trustedConnectorIds.toSortedSet()
        val detachedSnapshot = snapshot.detachedCopy()
        validateImportedSnapshot(detachedSnapshot, trustedIds, importedAt)
        return withDatabase { db ->
            db.beginTransaction()
            try {
                // Revalidate the detached value at the storage boundary before any destructive write.
                validateImportedSnapshot(detachedSnapshot, trustedIds, importedAt)
                val currentControl = readAutomaticIndexingControl(db)
                check(currentControl.generation < Long.MAX_VALUE) {
                    "Automatic indexing generation is exhausted."
                }

                // Queue/runtime records are device-local coordination state and are never imported.
                // Deleting every queue item makes any worker holding an old lease lose its commit fence.
                db.delete(TABLE_INDEXING_QUEUE, null, null)
                db.delete(TABLE_AUTOMATIC_INDEXING_RUNTIME, null, null)
                writeAutomaticIndexingControl(
                    db,
                    AutomaticIndexingControl(
                        enabled = false,
                        generation = currentControl.generation + 1L,
                        settingsKey = IMPORT_RECONSENT_AUTOMATIC_SETTINGS_KEY,
                    ),
                )

                clearDerivedMemoryTables(db)
                insertImportedSnapshot(db, detachedSnapshot)

                db.delete(TABLE_CONNECTOR_RECONSENT, null, null)
                trustedIds.forEach { connectorId ->
                    val values = ContentValues().apply {
                        put("connector_id", connectorId)
                        put("required_since", importedAt.toString())
                    }
                    check(
                        db.insertWithOnConflict(
                            TABLE_CONNECTOR_RECONSENT,
                            null,
                            values,
                            SQLiteDatabase.CONFLICT_ABORT,
                        ) >= 0L,
                    ) { "Could not persist connector re-consent requirement." }
                }

                val result = StoreImportResult(
                    sourceReferenceCount = detachedSnapshot.sourceReferences.size,
                    derivedMemoryEventCount = detachedSnapshot.derivedMemoryEvents.size,
                    citationCount = detachedSnapshot.citations.size,
                    dailySummaryCount = detachedSnapshot.dailySummaries.size,
                    placeClusterCount = detachedSnapshot.placeClusters.size,
                    appUsageSummaryCount = detachedSnapshot.appUsageSummaries.size,
                    connectorScanStatusCount = detachedSnapshot.connectorScanStatuses.size,
                    connectorsRequiringReconsent = trustedIds,
                    completedAt = importedAt,
                )
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun LocalMemorySnapshot.detachedCopy(): LocalMemorySnapshot {
        return copy(
            sourceReferences = sourceReferences.toList(),
            derivedMemoryEvents = derivedMemoryEvents.map { event ->
                event.copy(
                    sourceReferenceIds = event.sourceReferenceIds.toList(),
                    keywords = event.keywords.toList(),
                    labels = event.labels.toList(),
                    entities = event.entities.toList(),
                    citationIds = event.citationIds.toList(),
                )
            },
            citations = citations.toList(),
            dailySummaries = dailySummaries.map { summary ->
                summary.copy(
                    derivedMemoryEventIds = summary.derivedMemoryEventIds.toList(),
                    placeClusterIds = summary.placeClusterIds.toList(),
                    appUsageSummaryIds = summary.appUsageSummaryIds.toList(),
                    missingSources = summary.missingSources.toList(),
                )
            },
            placeClusters = placeClusters.map { cluster ->
                cluster.copy(sourceReferenceIds = cluster.sourceReferenceIds.toList())
            },
            appUsageSummaries = appUsageSummaries.map { summary ->
                summary.copy(
                    sourceReferenceIds = summary.sourceReferenceIds.toList(),
                    activeTimeBucketLabels = summary.activeTimeBucketLabels.toList(),
                )
            },
            connectorScanStatuses = connectorScanStatuses.map { status ->
                status.copy(missingSources = status.missingSources.toList())
            },
        )
    }

    private fun validateImportedSnapshot(
        snapshot: LocalMemorySnapshot,
        trustedConnectorIds: Set<String>,
        importedAt: Instant,
    ) {
        validateImportedSnapshotBounds(snapshot, trustedConnectorIds)
        trustedConnectorIds.forEach(::requireValidConnectorId)
        requireDistinctImportIds("source reference", snapshot.sourceReferences.map { it.id })
        requireDistinctImportIds("derived event", snapshot.derivedMemoryEvents.map { it.id })
        requireDistinctImportIds("citation", snapshot.citations.map { it.id })
        requireDistinctImportIds("daily summary", snapshot.dailySummaries.map { it.id })
        requireDistinctImportIds("place cluster", snapshot.placeClusters.map { it.id })
        requireDistinctImportIds("app usage summary", snapshot.appUsageSummaries.map { it.id })
        requireDistinctImportIds(
            "connector scan status",
            snapshot.connectorScanStatuses.map { it.connectorId },
        )
        require(snapshot.connectorScanStatuses.size <= trustedConnectorIds.size) {
            "An imported snapshot contains too many connector scan statuses."
        }

        snapshot.sourceReferences.forEach { source ->
            require(source.connectorId in trustedConnectorIds) {
                "An imported source reference belongs to an untrusted connector."
            }
            require(source.localPointer == null) {
                "An imported source reference cannot contain a device-local pointer."
            }
        }
        snapshot.derivedMemoryEvents.forEach { event ->
            requireTrustedScopedId("event", event.id, trustedConnectorIds)
        }
        snapshot.citations.forEach { citation ->
            requireTrustedScopedId("citation", citation.id, trustedConnectorIds)
        }
        snapshot.placeClusters.forEach { cluster ->
            requireTrustedScopedId("place-cluster", cluster.id, trustedConnectorIds)
        }
        snapshot.appUsageSummaries.forEach { summary ->
            requireTrustedScopedId("app-usage", summary.id, trustedConnectorIds)
        }
        snapshot.connectorScanStatuses.forEach { status ->
            require(status.connectorId in trustedConnectorIds) {
                "An imported scan status belongs to an untrusted connector."
            }
        }

        val statusesByConnector = snapshot.connectorScanStatuses.associateBy { it.connectorId }
        trustedConnectorIds.forEach { connectorId ->
            val sources = snapshot.sourceReferences.filter { it.connectorId == connectorId }
            val events = snapshot.derivedMemoryEvents.filter {
                it.id.startsWith("event:$connectorId:")
            }
            val citations = snapshot.citations.filter {
                it.id.startsWith("citation:$connectorId:")
            }
            val placeClusters = snapshot.placeClusters.filter {
                it.id.startsWith("place-cluster:$connectorId:")
            }
            val appUsageSummaries = snapshot.appUsageSummaries.filter {
                it.id.startsWith("app-usage:$connectorId:")
            }
            val status = statusesByConnector[connectorId]
            if (
                sources.isEmpty() &&
                events.isEmpty() &&
                citations.isEmpty() &&
                placeClusters.isEmpty() &&
                appUsageSummaries.isEmpty() &&
                status == null
            ) {
                return@forEach
            }
            ConnectorScanValidator.validate(
                ConnectorScanResult(
                    connectorId = connectorId,
                    processingState = status?.processingState ?: ProcessingState.COMPLETED,
                    sourceReferences = sources,
                    derivedEvents = events,
                    citations = citations,
                    placeClusters = placeClusters,
                    appUsageSummaries = appUsageSummaries,
                    missingSources = status?.missingSources.orEmpty(),
                    scopeFrom = status?.scopeFrom,
                    scopeUntil = status?.scopeUntil,
                    replaceExistingConnectorData = connectorId == LOCAL_FILES_CONNECTOR_ID,
                    scannedAt = status?.scannedAt ?: importedAt,
                ),
            )
        }

        val eventIds = snapshot.derivedMemoryEvents.mapTo(hashSetOf()) { it.id }
        val placeClusterIds = snapshot.placeClusters.mapTo(hashSetOf()) { it.id }
        val appUsageSummaryIds = snapshot.appUsageSummaries.mapTo(hashSetOf()) { it.id }
        snapshot.dailySummaries.forEach { summary ->
            require(summary.derivedMemoryEventIds.distinct().size == summary.derivedMemoryEventIds.size) {
                "An imported daily summary contains duplicate derived-event references."
            }
            require(summary.placeClusterIds.distinct().size == summary.placeClusterIds.size) {
                "An imported daily summary contains duplicate place-cluster references."
            }
            require(summary.appUsageSummaryIds.distinct().size == summary.appUsageSummaryIds.size) {
                "An imported daily summary contains duplicate app-usage references."
            }
            require(summary.derivedMemoryEventIds.all { it in eventIds }) {
                "An imported daily summary references a missing derived event."
            }
            require(summary.placeClusterIds.all { it in placeClusterIds }) {
                "An imported daily summary references a missing place cluster."
            }
            require(summary.appUsageSummaryIds.all { it in appUsageSummaryIds }) {
                "An imported daily summary references a missing app-usage summary."
            }
            require(
                summary.missingSources.all { missing ->
                    missing.connectorId == null || missing.connectorId in trustedConnectorIds
                },
            ) {
                "An imported daily summary contains an untrusted connector status."
            }
        }
    }

    private fun validateImportedSnapshotBounds(
        snapshot: LocalMemorySnapshot,
        trustedConnectorIds: Set<String>,
    ) {
        require(trustedConnectorIds.isNotEmpty()) {
            "An imported snapshot requires at least one trusted connector ID."
        }
        require(trustedConnectorIds.size <= MAX_TRUSTED_CONNECTORS) {
            "An imported snapshot contains too many trusted connector IDs."
        }
        require(snapshot.sourceReferences.size <= MAX_IMPORTED_SOURCE_REFERENCES) {
            "An imported snapshot contains too many source references."
        }
        require(snapshot.derivedMemoryEvents.size <= MAX_IMPORTED_DERIVED_EVENTS) {
            "An imported snapshot contains too many derived events."
        }
        require(snapshot.citations.size <= MAX_IMPORTED_CITATIONS) {
            "An imported snapshot contains too many citations."
        }
        require(snapshot.dailySummaries.size <= MAX_IMPORTED_DAILY_SUMMARIES) {
            "An imported snapshot contains too many daily summaries."
        }
        require(snapshot.placeClusters.size <= MAX_IMPORTED_PLACE_CLUSTERS) {
            "An imported snapshot contains too many place clusters."
        }
        require(snapshot.appUsageSummaries.size <= MAX_IMPORTED_APP_USAGE_SUMMARIES) {
            "An imported snapshot contains too many app-usage summaries."
        }
        require(snapshot.connectorScanStatuses.size <= MAX_IMPORTED_CONNECTOR_SCAN_STATUSES) {
            "An imported snapshot contains too many connector scan statuses."
        }
        val totalRecordCount = snapshot.sourceReferences.size.toLong() +
            snapshot.derivedMemoryEvents.size +
            snapshot.citations.size +
            snapshot.dailySummaries.size +
            snapshot.placeClusters.size +
            snapshot.appUsageSummaries.size +
            snapshot.connectorScanStatuses.size
        require(totalRecordCount <= MAX_IMPORTED_TOTAL_RECORDS) {
            "An imported snapshot contains too many total records."
        }
    }

    private fun requireTrustedScopedId(
        prefix: String,
        id: String,
        trustedConnectorIds: Set<String>,
    ) {
        require(trustedConnectorIds.any { connectorId ->
            id.startsWith("$prefix:$connectorId:") && id.length > prefix.length + connectorId.length + 2
        }) {
            "An imported $prefix ID is not scoped to a trusted connector."
        }
    }

    private fun requireDistinctImportIds(label: String, ids: List<String>) {
        require(ids.all { it.isNotBlank() }) { "Every imported $label ID must be non-blank." }
        require(ids.distinct().size == ids.size) { "An imported snapshot contains duplicate $label IDs." }
    }

    private fun requireValidConnectorId(connectorId: String) {
        require(
            connectorId.isNotBlank() &&
                connectorId.length <= MAX_CONNECTOR_ID_CHARS &&
                ':' !in connectorId &&
                connectorId.none(Char::isISOControl),
        ) { "Connector ID is invalid." }
    }

    private fun clearDerivedMemoryTables(db: SQLiteDatabase) {
        db.delete(TABLE_DAILY_SUMMARIES, null, null)
        db.delete(TABLE_PLACE_CLUSTERS, null, null)
        db.delete(TABLE_APP_USAGE_SUMMARIES, null, null)
        db.delete(TABLE_CITATIONS, null, null)
        db.delete(TABLE_DERIVED_EVENTS, null, null)
        db.delete(TABLE_SOURCE_REFERENCES, null, null)
        db.delete(TABLE_CONNECTOR_SCAN_STATUS, null, null)
    }

    private fun insertImportedSnapshot(db: SQLiteDatabase, snapshot: LocalMemorySnapshot) {
        snapshot.sourceReferences.forEach { source ->
            insertImportedRow(db, TABLE_SOURCE_REFERENCES, source.toValues())
        }
        snapshot.derivedMemoryEvents.forEach { event ->
            insertImportedRow(db, TABLE_DERIVED_EVENTS, event.toValues())
        }
        snapshot.citations.forEach { citation ->
            insertImportedRow(db, TABLE_CITATIONS, citation.toValues())
        }
        snapshot.dailySummaries.forEach { summary ->
            insertImportedRow(db, TABLE_DAILY_SUMMARIES, summary.toValues())
        }
        snapshot.placeClusters.forEach { cluster ->
            insertImportedRow(db, TABLE_PLACE_CLUSTERS, cluster.toValues())
        }
        snapshot.appUsageSummaries.forEach { summary ->
            insertImportedRow(db, TABLE_APP_USAGE_SUMMARIES, summary.toValues())
        }
        snapshot.connectorScanStatuses.forEach { status ->
            insertImportedRow(db, TABLE_CONNECTOR_SCAN_STATUS, status.toValues())
        }
    }

    private fun insertImportedRow(
        db: SQLiteDatabase,
        table: String,
        values: ContentValues,
    ) {
        check(
            db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_ABORT) >= 0L,
        ) { "Could not persist an imported derived-memory record." }
    }

    override suspend fun enqueue(tasks: List<IndexingTask>): List<IndexingQueueItem> = withDatabase { db ->
        db.beginTransaction()
        try {
            val automaticControl = readAutomaticIndexingControl(db)
            val items = tasks.mapNotNull { task ->
                if (
                    task.trigger == IndexingTrigger.AUTOMATIC &&
                    (!automaticControl.enabled || task.automaticGeneration != automaticControl.generation)
                ) {
                    return@mapNotNull null
                }
                val item = IndexingQueueItem(task = task)
                val inserted = db.insertWithOnConflict(
                    TABLE_INDEXING_QUEUE,
                    null,
                    item.toValues(),
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) >= 0L
                if (inserted) {
                    item
                } else {
                    val existing = when (task.trigger) {
                        IndexingTrigger.MANUAL -> readQueueItem(db, task.id)
                        IndexingTrigger.AUTOMATIC -> readAutomaticQueueItem(
                            db = db,
                            connectorId = task.connectorId,
                            automaticWindowKey = checkNotNull(task.automaticWindowKey),
                            automaticGeneration = checkNotNull(task.automaticGeneration),
                        )
                    }
                    checkNotNull(existing) { "Could not resolve an indexing queue conflict." }
                    if (existing.id == task.id) {
                        require(existing.task == task) { "An indexing task ID already belongs to different work." }
                    }
                    existing
                }
            }
            db.setTransactionSuccessful()
            items
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun claimNextAtomically(
        leaseOwner: String,
        claimedAt: Instant,
        leaseUntil: Instant,
        trigger: IndexingTrigger?,
        automaticGeneration: Long?,
    ): IndexingQueueItem? = withDatabase { db ->
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        require(leaseUntil.isAfter(claimedAt)) { "Lease expiry must be after claim time." }
        when (trigger) {
            IndexingTrigger.AUTOMATIC -> require(
                automaticGeneration != null && automaticGeneration >= 0L,
            ) { "Automatic claims require a non-negative generation." }

            IndexingTrigger.MANUAL,
            null,
            -> require(automaticGeneration == null) {
                "Only automatic claims may specify an automatic generation."
            }
        }
        db.beginTransaction()
        try {
            val automaticControl = readAutomaticIndexingControl(db)
            if (
                trigger == IndexingTrigger.AUTOMATIC &&
                (!automaticControl.enabled || automaticGeneration != automaticControl.generation)
            ) {
                db.setTransactionSuccessful()
                return@withDatabase null
            }
            val (selection, selectionArgs) = when (trigger) {
                IndexingTrigger.MANUAL -> {
                    "state = ? AND trigger = ?" to arrayOf(
                        IndexingQueueState.PENDING.name,
                        IndexingTrigger.MANUAL.name,
                    )
                }

                IndexingTrigger.AUTOMATIC -> {
                    "state = ? AND trigger = ? AND automatic_generation = ?" to arrayOf(
                        IndexingQueueState.PENDING.name,
                        IndexingTrigger.AUTOMATIC.name,
                        checkNotNull(automaticGeneration).toString(),
                    )
                }

                null -> if (automaticControl.enabled) {
                    "state = ? AND (trigger != ? OR automatic_generation = ?)" to arrayOf(
                        IndexingQueueState.PENDING.name,
                        IndexingTrigger.AUTOMATIC.name,
                        automaticControl.generation.toString(),
                    )
                } else {
                    "state = ? AND trigger != ?" to arrayOf(
                        IndexingQueueState.PENDING.name,
                        IndexingTrigger.AUTOMATIC.name,
                    )
                }
            }
            val serializedSelection = "($selection) AND NOT EXISTS (" +
                "SELECT 1 FROM $TABLE_INDEXING_QUEUE AS active " +
                "WHERE active.connector_id = $TABLE_INDEXING_QUEUE.connector_id " +
                "AND active.state = ? AND active.lease_until_ms > ?)"
            val serializedSelectionArgs = selectionArgs + arrayOf(
                IndexingQueueState.RUNNING.name,
                claimedAt.toEpochMilli().toString(),
            )
            val pending = readQueueItems(
                db = db,
                selection = serializedSelection,
                selectionArgs = serializedSelectionArgs,
                orderBy = "requested_at_ms ASC, id ASC",
                limit = 1,
            ).firstOrNull()
            val claimed = pending?.let { item ->
                val updated = item.copy(
                    state = IndexingQueueState.RUNNING,
                    attemptCount = item.attemptCount + 1,
                    lastAttemptAt = claimedAt,
                    startedAt = claimedAt,
                    leaseOwner = leaseOwner,
                    leaseUntil = leaseUntil,
                )
                val updateSelection = if (item.task.trigger == IndexingTrigger.AUTOMATIC) {
                    "id = ? AND state = ? AND automatic_generation = ?"
                } else {
                    "id = ? AND state = ?"
                }
                val updateArgs = buildList {
                    add(item.id)
                    add(IndexingQueueState.PENDING.name)
                    item.task.automaticGeneration?.let { add(it.toString()) }
                }.toTypedArray()
                val count = db.update(
                    TABLE_INDEXING_QUEUE,
                    updated.toValues(),
                    updateSelection,
                    updateArgs,
                )
                if (count == 1) updated else null
            }
            db.setTransactionSuccessful()
            claimed
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun complete(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        indexedEventCount: Int,
    ): IndexingQueueItem {
        require(indexedEventCount >= 0) { "Indexed event count must not be negative." }
        return transitionTerminal(itemId, leaseOwner, attemptCount, IndexingQueueState.COMPLETED) { current ->
            current.copy(
                state = IndexingQueueState.COMPLETED,
                indexedEventCount = indexedEventCount,
                completedAt = completedAt,
                leaseOwner = null,
                leaseUntil = null,
            )
        }
    }

    override suspend fun skip(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        reason: IndexingSkipReason,
    ): IndexingQueueItem {
        return transitionTerminal(itemId, leaseOwner, attemptCount, IndexingQueueState.SKIPPED) { current ->
            current.copy(
                state = IndexingQueueState.SKIPPED,
                completedAt = completedAt,
                leaseOwner = null,
                leaseUntil = null,
                skipReason = reason,
            )
        }
    }

    override suspend fun fail(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        code: IndexingFailureCode,
    ): IndexingQueueItem {
        return transitionTerminal(itemId, leaseOwner, attemptCount, IndexingQueueState.FAILED) { current ->
            current.copy(
                state = IndexingQueueState.FAILED,
                completedAt = completedAt,
                leaseOwner = null,
                leaseUntil = null,
                failureCode = code,
            )
        }
    }

    override suspend fun recoverExpiredLeases(
        now: Instant,
        maxAttempts: Int,
    ): LeaseRecoveryResult = withDatabase { db ->
        require(maxAttempts > 0) { "Maximum attempts must be positive." }
        var requeuedCount = 0
        var failedCount = 0
        db.beginTransaction()
        try {
            val expired = readQueueItems(
                db = db,
                selection = "state = ? AND lease_until_ms <= ?",
                selectionArgs = arrayOf(IndexingQueueState.RUNNING.name, now.toEpochMilli().toString()),
                orderBy = "lease_until_ms ASC, id ASC",
            )
            expired.forEach { item ->
                val updated = if (item.attemptCount >= maxAttempts) {
                    failedCount += 1
                    item.copy(
                        state = IndexingQueueState.FAILED,
                        completedAt = now,
                        leaseOwner = null,
                        leaseUntil = null,
                        failureCode = IndexingFailureCode.ATTEMPT_LIMIT_REACHED,
                    )
                } else {
                    requeuedCount += 1
                    item.copy(
                        state = IndexingQueueState.PENDING,
                        startedAt = null,
                        leaseOwner = null,
                        leaseUntil = null,
                    )
                }
                val count = db.update(
                    TABLE_INDEXING_QUEUE,
                    updated.toValues(),
                    "id = ? AND state = ? AND lease_until_ms <= ?",
                    arrayOf(item.id, IndexingQueueState.RUNNING.name, now.toEpochMilli().toString()),
                )
                check(count == 1) { "An expired indexing lease changed concurrently." }
            }
            db.setTransactionSuccessful()
            LeaseRecoveryResult(requeuedCount = requeuedCount, failedCount = failedCount)
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun snapshot(limit: Int): IndexingQueueSnapshot = withDatabase { db ->
        require(limit in 1..MAX_QUEUE_SNAPSHOT_ITEMS) { "Queue snapshot limit is out of range." }
        db.beginTransaction()
        try {
            val observedAt = clock.instant()
            val items = readQueueItems(
                db = db,
                orderBy = "requested_at_ms DESC, id DESC",
                limit = limit,
            )
            val queueDepth = countQueueRows(db, "state = ?", arrayOf(IndexingQueueState.PENDING.name))
            val runningConnectorIds = readQueueItems(
                db = db,
                selection = "state = ? AND lease_until_ms > ?",
                selectionArgs = arrayOf(
                    IndexingQueueState.RUNNING.name,
                    observedAt.toEpochMilli().toString(),
                ),
                orderBy = "requested_at_ms ASC, id ASC",
            ).mapTo(mutableSetOf()) { it.connectorId }
            val lastCompletedAt = db.rawQuery(
                "SELECT MAX(completed_at_ms) FROM $TABLE_INDEXING_QUEUE WHERE completed_at_ms IS NOT NULL",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) Instant.ofEpochMilli(cursor.getLong(0)) else null
            }
            val snapshot = IndexingQueueSnapshot(
                items = items,
                queueDepth = queueDepth,
                runningConnectorIds = runningConnectorIds,
                lastCompletedAt = lastCompletedAt,
                observedAt = observedAt,
            )
            db.setTransactionSuccessful()
            snapshot
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun pruneTerminalItems(
        completedBefore: Instant,
        keepLatest: Int,
    ): Int = withDatabase { db ->
        require(keepLatest >= 0) { "Latest-item retention must not be negative." }
        db.beginTransaction()
        try {
            val terminalItems = readQueueItems(
                db = db,
                selection = "state IN (?, ?, ?)",
                selectionArgs = arrayOf(
                    IndexingQueueState.COMPLETED.name,
                    IndexingQueueState.SKIPPED.name,
                    IndexingQueueState.FAILED.name,
                ),
                orderBy = "completed_at_ms DESC, id DESC",
            )
            val idsToDelete = terminalItems.drop(keepLatest)
                .filter { item -> item.completedAt?.isBefore(completedBefore) == true }
                .map { it.id }
            deleteIn(db, TABLE_INDEXING_QUEUE, "id", idsToDelete)
            db.setTransactionSuccessful()
            idsToDelete.size
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun loadAutomaticIndexingControl(): AutomaticIndexingControl = withDatabase { db ->
        readAutomaticIndexingControl(db)
    }

    override suspend fun synchronizeAutomaticIndexingControl(
        enabled: Boolean,
        settingsKey: String,
        changedAt: Instant,
    ): AutomaticIndexingControl = withDatabase { db ->
        require(settingsKey.isNotBlank()) { "Automatic indexing settings key must not be blank." }
        db.beginTransaction()
        try {
            val current = readAutomaticIndexingControl(db)
            val changed = current.enabled != enabled || current.settingsKey != settingsKey
            check(!changed || current.generation < Long.MAX_VALUE) {
                "Automatic indexing generation is exhausted."
            }
            val synchronized = AutomaticIndexingControl(
                enabled = enabled,
                generation = if (changed) current.generation + 1L else current.generation,
                settingsKey = settingsKey,
            )
            writeAutomaticIndexingControl(db, synchronized)

            if (!enabled || changed) {
                val reason = if (enabled) {
                    IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED
                } else {
                    IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED
                }
                val (activeItems, reconciledAt) = skipActiveAutomaticItems(
                    db = db,
                    completedAt = changedAt,
                    reason = reason,
                )
                val status = AutomaticIndexingRuntimeStatus(
                    lastCheckedAt = reconciledAt,
                    lastStartedAt = activeItems.mapNotNull { item -> item.startedAt }.maxOrNull(),
                    lastCompletedAt = reconciledAt,
                    lastOutcome = AutomaticIndexingOutcome.SKIPPED,
                    lastSkipReason = reason,
                )
                writeAutomaticIndexingRuntime(db, status, synchronized.generation)
            }
            db.setTransactionSuccessful()
            synchronized
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun loadAutomaticIndexingRuntime(): AutomaticIndexingRuntimeStatus = withDatabase { db ->
        readAutomaticIndexingRuntime(db)?.status ?: AutomaticIndexingRuntimeStatus()
    }

    override suspend fun saveAutomaticIndexingRuntime(
        status: AutomaticIndexingRuntimeStatus,
        expectedGeneration: Long,
    ): Boolean = withDatabase { db ->
        require(expectedGeneration >= 0L) {
            "Expected automatic indexing generation must not be negative."
        }
        db.beginTransaction()
        try {
            val control = readAutomaticIndexingControl(db)
            if (!control.enabled || expectedGeneration != control.generation) {
                db.setTransactionSuccessful()
                return@withDatabase false
            }
            val targetGeneration = expectedGeneration
            val stored = readAutomaticIndexingRuntime(db)
            val staleGeneration = stored != null && stored.generation > targetGeneration
            val staleTimestamp = stored != null &&
                stored.generation == targetGeneration &&
                stored.status.lastCheckedAt != null &&
                (
                    status.lastCheckedAt == null ||
                        stored.status.lastCheckedAt.isAfter(status.lastCheckedAt)
                    )
            if (staleGeneration || staleTimestamp) {
                db.setTransactionSuccessful()
                return@withDatabase false
            }
            writeAutomaticIndexingRuntime(db, status, targetGeneration)
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    private fun writeConnectorScanRows(
        db: SQLiteDatabase,
        scanResult: ConnectorScanResult,
        completedAt: Instant,
    ): StoreWriteResult {
        ConnectorScanValidator.validate(scanResult)
        val existingSourceReferenceIds = existingIds(
            db,
            TABLE_SOURCE_REFERENCES,
            scanResult.sourceReferences.map { it.id },
        )
        val existingCount = existingSourceReferenceIds.size + existingIds(
            db,
            TABLE_DERIVED_EVENTS,
            scanResult.derivedEvents.map { it.id },
        ).size + existingIds(
            db,
            TABLE_CITATIONS,
            scanResult.citations.map { it.id },
        ).size + existingIds(
            db,
            TABLE_PLACE_CLUSTERS,
            scanResult.placeClusters.map { it.id },
        ).size + existingIds(
            db,
            TABLE_APP_USAGE_SUMMARIES,
            scanResult.appUsageSummaries.map { it.id },
        ).size
        if (scanResult.replaceExistingConnectorData) {
            deleteConnectorRows(db, scanResult.connectorId, completedAt)
        }
        writeConnectorScanStatus(db, scanResult)

        scanResult.sourceReferences.forEach { source ->
            check(
                db.insertWithOnConflict(
                    TABLE_SOURCE_REFERENCES,
                    null,
                    source.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) >= 0L,
            ) { "Could not persist connector source reference." }
        }
        scanResult.derivedEvents.forEach { event ->
            check(
                db.insertWithOnConflict(
                    TABLE_DERIVED_EVENTS,
                    null,
                    event.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) >= 0L,
            ) { "Could not persist connector derived event." }
        }
        scanResult.citations.forEach { citation ->
            check(
                db.insertWithOnConflict(
                    TABLE_CITATIONS,
                    null,
                    citation.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) >= 0L,
            ) { "Could not persist connector citation." }
        }
        scanResult.placeClusters.forEach { cluster ->
            val clusterToStore = if (
                scanResult.connectorId == LOCATION_CONNECTOR_ID &&
                !scanResult.replaceExistingConnectorData
            ) {
                mergeLocationPlaceCluster(
                    existing = readPlaceCluster(db, cluster.id),
                    incoming = cluster,
                    newSourceReferenceIds = cluster.sourceReferenceIds
                        .filterNotTo(linkedSetOf()) { sourceId -> sourceId in existingSourceReferenceIds },
                )
            } else {
                cluster
            }
            check(
                db.insertWithOnConflict(
                    TABLE_PLACE_CLUSTERS,
                    null,
                    clusterToStore.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) >= 0L,
            ) { "Could not persist connector place cluster." }
        }
        scanResult.appUsageSummaries.forEach { summary ->
            check(
                db.insertWithOnConflict(
                    TABLE_APP_USAGE_SUMMARIES,
                    null,
                    summary.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) >= 0L,
            ) { "Could not persist connector app-usage summary." }
        }
        val totalCount = scanResult.sourceReferences.size +
            scanResult.derivedEvents.size +
            scanResult.citations.size +
            scanResult.placeClusters.size +
            scanResult.appUsageSummaries.size
        return StoreWriteResult(
            insertedCount = totalCount - existingCount,
            updatedCount = existingCount,
            completedAt = completedAt,
        )
    }

    private fun deleteConnectorRows(
        db: SQLiteDatabase,
        connectorId: String,
        completedAt: Instant,
    ): StoreDeleteResult {
        val sourceIds = readSourceReferences(db, connectorId).map { it.id }.toSet()
        val eventIds = readDerivedMemoryEvents(db)
            .filter { event ->
                event.id.startsWith("event:$connectorId:") ||
                    event.sourceReferenceIds.any { it in sourceIds }
            }
            .map { it.id }
            .toSet()
        val citationIds = readCitations(db)
            .filter { citation ->
                citation.id.startsWith("citation:$connectorId:") ||
                    citation.sourceReferenceId in sourceIds ||
                    citation.derivedMemoryEventId in eventIds
            }
            .map { it.id }
        val placeClusterIds = readPlaceClusters(db)
            .filter { cluster ->
                cluster.id.startsWith("place-cluster:$connectorId:") ||
                    cluster.sourceReferenceIds.any { it in sourceIds }
            }
            .map { it.id }
            .toSet()
        val appUsageSummaryIds = readAppUsageSummaries(db)
            .filter { summary ->
                summary.id.startsWith("app-usage:$connectorId:") ||
                    summary.sourceReferenceIds.any { it in sourceIds }
            }
            .map { it.id }
            .toSet()
        val dailySummaryIds = readDailySummaries(db)
            .filter { summary ->
                summary.derivedMemoryEventIds.any { it in eventIds } ||
                    summary.placeClusterIds.any { it in placeClusterIds } ||
                    summary.appUsageSummaryIds.any { it in appUsageSummaryIds }
            }
            .map { it.id }

        deleteIn(db, TABLE_DAILY_SUMMARIES, "id", dailySummaryIds)
        deleteIn(db, TABLE_PLACE_CLUSTERS, "id", placeClusterIds.toList())
        deleteIn(db, TABLE_APP_USAGE_SUMMARIES, "id", appUsageSummaryIds.toList())
        deleteIn(db, TABLE_CITATIONS, "id", citationIds)
        deleteIn(db, TABLE_DERIVED_EVENTS, "id", eventIds.toList())
        deleteIn(db, TABLE_SOURCE_REFERENCES, "id", sourceIds.toList())
        db.delete(TABLE_CONNECTOR_SCAN_STATUS, "connector_id = ?", arrayOf(connectorId))
        return StoreDeleteResult(
            connectorId = connectorId,
            deletedSourceReferenceIds = sourceIds.toList(),
            deletedDerivedMemoryEventIds = eventIds.toList(),
            deletedCitationIds = citationIds,
            deletedSummaryIds = dailySummaryIds,
            deletedPlaceClusterIds = placeClusterIds.toList(),
            deletedAppUsageSummaryIds = appUsageSummaryIds.toList(),
            completedAt = completedAt,
        )
    }

    private fun transitionTerminal(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        targetState: IndexingQueueState,
        transition: (IndexingQueueItem) -> IndexingQueueItem,
    ): IndexingQueueItem = withDatabase { db ->
        require(itemId.isNotBlank()) { "Indexing item ID must not be blank." }
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        require(attemptCount > 0) { "Attempt count must be positive." }
        db.beginTransaction()
        try {
            val current = checkNotNull(readQueueItem(db, itemId)) { "Indexing queue item was not found." }
            IndexingQueueValidator.requireValidTransition(current.state, targetState)
            require(current.leaseOwner == leaseOwner) { "Indexing lease owner does not match." }
            require(current.attemptCount == attemptCount) { "Indexing attempt does not match." }
            val updated = transition(current)
            check(updated.state == targetState) { "Indexing transition produced the wrong state." }
            val completedAt = checkNotNull(updated.completedAt) {
                "A terminal indexing transition must have a completion timestamp."
            }
            require(current.leaseUntil?.isAfter(completedAt) == true) {
                "Indexing lease expired before the terminal transition."
            }
            val count = db.update(
                TABLE_INDEXING_QUEUE,
                updated.toValues(),
                "id = ? AND state = ? AND lease_owner = ? AND attempt_count = ? AND lease_until_ms > ?",
                arrayOf(
                    itemId,
                    current.state.name,
                    leaseOwner,
                    attemptCount.toString(),
                    completedAt.toEpochMilli().toString(),
                ),
            )
            check(count == 1) { "Indexing queue item changed concurrently." }
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> writeRows(
        rows: List<T>,
        insert: (SQLiteDatabase, T) -> Long,
    ): StoreWriteResult = withDatabase { db ->
        var inserted = 0
        db.beginTransaction()
        try {
            rows.forEach { row ->
                check(insert(db, row) >= 0L) { "Could not persist a derived-memory record." }
                inserted += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        StoreWriteResult(
            insertedCount = inserted,
            updatedCount = 0,
            completedAt = Instant.now(),
        )
    }

    private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
        loadSqlCipher()
        val dbFile = context.getDatabasePath(databaseName)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            passphraseProvider.getPassphrase(context),
            null,
            null,
        )
        try {
            synchronized(SCHEMA_MIGRATION_LOCK) {
                ensureSchema(db)
            }
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            check(cursor.moveToFirst()) { "Could not read the local-store schema version." }
            cursor.getInt(0)
        }
        check(version <= SCHEMA_VERSION) {
            "Local-store schema version $version is newer than supported version $SCHEMA_VERSION."
        }
        if (version < CORE_SCHEMA_VERSION) {
            runMigration(db, CORE_SCHEMA_VERSION, ::createCoreTables)
        }
        if (version < INDEXING_SCHEMA_VERSION) {
            runMigration(db, INDEXING_SCHEMA_VERSION, ::createIndexingTables)
        }
        if (version < AUTOMATIC_CONTROL_SCHEMA_VERSION) {
            runMigration(db, AUTOMATIC_CONTROL_SCHEMA_VERSION, ::addAutomaticIndexingControl)
        }
        if (version < CONNECTOR_SCAN_STATUS_SCHEMA_VERSION) {
            runMigration(db, CONNECTOR_SCAN_STATUS_SCHEMA_VERSION, ::createConnectorScanStatusTable)
        }
        if (version < CONNECTOR_SCAN_ISSUE_CODE_SCHEMA_VERSION) {
            runMigration(db, CONNECTOR_SCAN_ISSUE_CODE_SCHEMA_VERSION, ::migrateConnectorScanIssueCodes)
        }
        if (version < LOCAL_SOURCE_IDENTITY_SCHEMA_VERSION) {
            runMigration(db, LOCAL_SOURCE_IDENTITY_SCHEMA_VERSION, ::purgeLegacyLocalFileIdentity)
        }
        if (version < CONNECTOR_RECONSENT_SCHEMA_VERSION) {
            runMigration(db, CONNECTOR_RECONSENT_SCHEMA_VERSION, ::createConnectorReconsentTable)
        }
    }

    private fun runMigration(
        db: SQLiteDatabase,
        targetVersion: Int,
        migrate: (SQLiteDatabase) -> Unit,
    ) {
        db.beginTransaction()
        try {
            migrate(db)
            db.execSQL("PRAGMA user_version = $targetVersion")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SOURCE_REFERENCES (
                id TEXT PRIMARY KEY NOT NULL,
                connector_id TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                local_pointer TEXT,
                external_id_hash TEXT,
                hmac_hash TEXT,
                source_app_identifier TEXT,
                observed_at TEXT,
                modified_at TEXT,
                sensitivity TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DERIVED_EVENTS (
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                source_reference_ids TEXT NOT NULL,
                summary TEXT NOT NULL,
                started_at TEXT,
                ended_at TEXT,
                keywords TEXT NOT NULL,
                labels TEXT NOT NULL,
                entities TEXT NOT NULL,
                confidence TEXT NOT NULL,
                sensitivity TEXT NOT NULL,
                citation_ids TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CITATIONS (
                id TEXT PRIMARY KEY NOT NULL,
                source_reference_id TEXT NOT NULL,
                derived_memory_event_id TEXT,
                label TEXT NOT NULL,
                observed_at TEXT,
                confidence TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY_SUMMARIES (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                summary TEXT NOT NULL,
                derived_memory_event_ids TEXT NOT NULL,
                place_cluster_ids TEXT NOT NULL,
                app_usage_summary_ids TEXT NOT NULL,
                confidence TEXT NOT NULL,
                missing_sources TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLACE_CLUSTERS (
                id TEXT PRIMARY KEY NOT NULL,
                label TEXT,
                region_label TEXT,
                centroid_latitude REAL,
                centroid_longitude REAL,
                radius_meters REAL,
                first_seen_at TEXT,
                last_seen_at TEXT,
                visit_count INTEGER NOT NULL,
                source_reference_ids TEXT NOT NULL,
                confidence TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_APP_USAGE_SUMMARIES (
                id TEXT PRIMARY KEY NOT NULL,
                source_reference_ids TEXT NOT NULL,
                date TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_alias TEXT,
                category TEXT NOT NULL,
                total_duration_minutes INTEGER NOT NULL,
                launch_count INTEGER,
                active_time_bucket_labels TEXT NOT NULL,
                confidence TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createIndexingTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_INDEXING_QUEUE (
                id TEXT PRIMARY KEY NOT NULL,
                connector_id TEXT NOT NULL,
                trigger TEXT NOT NULL,
                requested_at_ms INTEGER NOT NULL,
                from_at_ms INTEGER,
                until_at_ms INTEGER,
                force_refresh INTEGER NOT NULL,
                automatic_window_key TEXT,
                state TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                indexed_event_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at_ms INTEGER,
                started_at_ms INTEGER,
                completed_at_ms INTEGER,
                lease_owner TEXT,
                lease_until_ms INTEGER,
                skip_reason TEXT,
                failure_code TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS indexing_queue_claim_idx
            ON $TABLE_INDEXING_QUEUE(state, requested_at_ms, id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS indexing_queue_automatic_window_idx
            ON $TABLE_INDEXING_QUEUE(automatic_window_key, connector_id)
            WHERE trigger = 'AUTOMATIC'
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATIC_INDEXING_RUNTIME (
                singleton_id INTEGER PRIMARY KEY NOT NULL CHECK(singleton_id = 1),
                last_checked_at_ms INTEGER,
                last_started_at_ms INTEGER,
                last_completed_at_ms INTEGER,
                last_outcome TEXT,
                last_skip_reason TEXT,
                last_failure_code TEXT,
                last_indexed_event_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun addAutomaticIndexingControl(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE $TABLE_INDEXING_QUEUE ADD COLUMN automatic_generation INTEGER")
        db.execSQL(
            """
            UPDATE $TABLE_INDEXING_QUEUE
            SET automatic_generation = 0
            WHERE trigger = 'AUTOMATIC' AND automatic_generation IS NULL
            """.trimIndent(),
        )
        db.execSQL("DROP INDEX IF EXISTS indexing_queue_automatic_window_idx")
        db.execSQL(
            """
            CREATE UNIQUE INDEX indexing_queue_automatic_window_idx
            ON $TABLE_INDEXING_QUEUE(automatic_generation, automatic_window_key, connector_id)
            WHERE trigger = 'AUTOMATIC'
            """.trimIndent(),
        )
        db.execSQL(
            "ALTER TABLE $TABLE_AUTOMATIC_INDEXING_RUNTIME " +
                "ADD COLUMN runtime_generation INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATIC_INDEXING_CONTROL (
                singleton_id INTEGER PRIMARY KEY NOT NULL CHECK(singleton_id = 1),
                enabled INTEGER NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                settings_key TEXT NOT NULL CHECK(length(settings_key) > 0)
            )
            """.trimIndent(),
        )
        writeAutomaticIndexingControl(
            db,
            AutomaticIndexingControl(
                enabled = false,
                generation = 0L,
                settingsKey = UNINITIALIZED_AUTOMATIC_SETTINGS_KEY,
            ),
        )
    }

    private fun createConnectorScanStatusTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONNECTOR_SCAN_STATUS (
                connector_id TEXT PRIMARY KEY NOT NULL,
                processing_state TEXT NOT NULL,
                missing_sources TEXT NOT NULL,
                scope_from TEXT,
                scope_until TEXT,
                scanned_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun migrateConnectorScanIssueCodes(db: SQLiteDatabase) {
        val rows = db.query(
            TABLE_CONNECTOR_SCAN_STATUS,
            arrayOf("connector_id", "missing_sources"),
            null,
            null,
            null,
            null,
            null,
        ).useRows { row -> row.string("connector_id") to row.string("missing_sources") }
        rows.forEach { (connectorId, encodedMissingSources) ->
            val migrated = decodeList(encodedMissingSources)
                .map(::decodeConnectorScanMissingSource)
                .distinctBy(::missingSourceIdentity)
                .take(ConnectorScanStatus.MAX_MISSING_SOURCES)
                .map { missing -> missing.toConnectorScanStorageString() }
            val values = ContentValues().apply {
                put("missing_sources", encodeList(migrated))
            }
            check(
                db.update(
                    TABLE_CONNECTOR_SCAN_STATUS,
                    values,
                    "connector_id = ?",
                    arrayOf(connectorId),
                ) == 1,
            ) { "Could not migrate connector scan issue codes." }
        }
    }

    private fun purgeLegacyLocalFileIdentity(db: SQLiteDatabase) {
        deleteConnectorRows(
            db = db,
            connectorId = LOCAL_FILES_CONNECTOR_ID,
            completedAt = clock.instant(),
        )
    }

    private fun createConnectorReconsentTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONNECTOR_RECONSENT (
                connector_id TEXT PRIMARY KEY NOT NULL,
                required_since TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun fenceActiveConnectorTasks(
        db: SQLiteDatabase,
        connectorId: String,
        completedAt: Instant,
    ) {
        val activeItems = readQueueItems(
            db = db,
            selection = "connector_id = ? AND state IN (?, ?)",
            selectionArgs = arrayOf(
                connectorId,
                IndexingQueueState.PENDING.name,
                IndexingQueueState.RUNNING.name,
            ),
            orderBy = "requested_at_ms ASC, id ASC",
        )
        activeItems.forEach { item ->
            val terminalAt = maxOf(completedAt, item.task.requestedAt, item.startedAt ?: completedAt)
            val updated = item.copy(
                state = IndexingQueueState.SKIPPED,
                completedAt = terminalAt,
                leaseOwner = null,
                leaseUntil = null,
                skipReason = IndexingSkipReason.SOURCE_DATA_DELETED,
                failureCode = null,
            )
            check(
                db.update(
                    TABLE_INDEXING_QUEUE,
                    updated.toValues(),
                    "id = ? AND state = ?",
                    arrayOf(item.id, item.state.name),
                ) == 1,
            ) { "Could not fence active connector indexing before deletion." }
        }
    }

    private fun IndexingQueueItem.toValues(): ContentValues = ContentValues().apply {
        put("id", task.id)
        put("connector_id", task.connectorId)
        put("trigger", task.trigger.name)
        put("requested_at_ms", task.requestedAt.toEpochMilli())
        putNullableLong("from_at_ms", task.from?.toEpochMilli())
        putNullableLong("until_at_ms", task.until?.toEpochMilli())
        put("force_refresh", if (task.forceRefresh) 1 else 0)
        put("automatic_window_key", task.automaticWindowKey)
        put("automatic_generation", task.automaticGeneration)
        put("state", state.name)
        put("attempt_count", attemptCount)
        put("indexed_event_count", indexedEventCount)
        putNullableLong("last_attempt_at_ms", lastAttemptAt?.toEpochMilli())
        putNullableLong("started_at_ms", startedAt?.toEpochMilli())
        putNullableLong("completed_at_ms", completedAt?.toEpochMilli())
        put("lease_owner", leaseOwner)
        putNullableLong("lease_until_ms", leaseUntil?.toEpochMilli())
        put("skip_reason", skipReason?.name)
        put("failure_code", failureCode?.name)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun AutomaticIndexingRuntimeStatus.toAutomaticRuntimeValues(
        generation: Long,
    ): ContentValues {
        return ContentValues().apply {
            put("singleton_id", 1)
            put("runtime_generation", generation)
            putNullableLong("last_checked_at_ms", lastCheckedAt?.toEpochMilli())
            putNullableLong("last_started_at_ms", lastStartedAt?.toEpochMilli())
            putNullableLong("last_completed_at_ms", lastCompletedAt?.toEpochMilli())
            put("last_outcome", lastOutcome?.name)
            put("last_skip_reason", lastSkipReason?.name)
            put("last_failure_code", lastFailureCode?.name)
            put("last_indexed_event_count", lastIndexedEventCount)
        }
    }

    private fun readAutomaticIndexingControl(db: SQLiteDatabase): AutomaticIndexingControl {
        return db.query(
            TABLE_AUTOMATIC_INDEXING_CONTROL,
            null,
            "singleton_id = 1",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                AutomaticIndexingControl(
                    enabled = false,
                    generation = 0L,
                    settingsKey = UNINITIALIZED_AUTOMATIC_SETTINGS_KEY,
                )
            } else {
                AutomaticIndexingControl(
                    enabled = cursor.int("enabled") == 1,
                    generation = cursor.long("generation"),
                    settingsKey = cursor.string("settings_key"),
                )
            }
        }
    }

    private fun writeAutomaticIndexingControl(
        db: SQLiteDatabase,
        control: AutomaticIndexingControl,
    ) {
        val values = ContentValues().apply {
            put("singleton_id", 1)
            put("enabled", if (control.enabled) 1 else 0)
            put("generation", control.generation)
            put("settings_key", control.settingsKey)
        }
        check(
            db.insertWithOnConflict(
                TABLE_AUTOMATIC_INDEXING_CONTROL,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L,
        ) { "Could not persist automatic indexing control." }
    }

    private fun readAutomaticIndexingRuntime(db: SQLiteDatabase): StoredAutomaticRuntime? {
        return db.query(
            TABLE_AUTOMATIC_INDEXING_RUNTIME,
            null,
            "singleton_id = 1",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                StoredAutomaticRuntime(
                    generation = cursor.long("runtime_generation"),
                    status = AutomaticIndexingRuntimeStatus(
                        lastCheckedAt = cursor.nullableInstantFromMillis("last_checked_at_ms"),
                        lastStartedAt = cursor.nullableInstantFromMillis("last_started_at_ms"),
                        lastCompletedAt = cursor.nullableInstantFromMillis("last_completed_at_ms"),
                        lastOutcome = cursor.nullableString("last_outcome")
                            ?.let { enumValueOf<AutomaticIndexingOutcome>(it) },
                        lastSkipReason = cursor.nullableString("last_skip_reason")
                            ?.let { enumValueOf<IndexingSkipReason>(it) },
                        lastFailureCode = cursor.nullableString("last_failure_code")
                            ?.let { enumValueOf<IndexingFailureCode>(it) },
                        lastIndexedEventCount = cursor.int("last_indexed_event_count"),
                    ),
                )
            }
        }
    }

    private fun writeAutomaticIndexingRuntime(
        db: SQLiteDatabase,
        status: AutomaticIndexingRuntimeStatus,
        generation: Long,
    ) {
        check(
            db.insertWithOnConflict(
                TABLE_AUTOMATIC_INDEXING_RUNTIME,
                null,
                status.toAutomaticRuntimeValues(generation),
                SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L,
        ) { "Could not persist automatic indexing status." }
    }

    private fun skipActiveAutomaticItems(
        db: SQLiteDatabase,
        completedAt: Instant,
        reason: IndexingSkipReason,
    ): Pair<List<IndexingQueueItem>, Instant> {
        val activeItems = readQueueItems(
            db = db,
            selection = "trigger = ? AND state IN (?, ?)",
            selectionArgs = arrayOf(
                IndexingTrigger.AUTOMATIC.name,
                IndexingQueueState.PENDING.name,
                IndexingQueueState.RUNNING.name,
            ),
            orderBy = "requested_at_ms ASC, id ASC",
        )
        val reconciledAt = activeItems.fold(completedAt) { latest, item ->
            listOfNotNull(item.task.requestedAt, item.startedAt, latest).max()
        }
        activeItems.forEach { item ->
            IndexingQueueValidator.requireValidTransition(item.state, IndexingQueueState.SKIPPED)
            val updated = item.copy(
                state = IndexingQueueState.SKIPPED,
                indexedEventCount = 0,
                completedAt = reconciledAt,
                leaseOwner = null,
                leaseUntil = null,
                skipReason = reason,
                failureCode = null,
            )
            val count = db.update(
                TABLE_INDEXING_QUEUE,
                updated.toValues(),
                "id = ? AND trigger = ? AND state = ? AND attempt_count = ?",
                arrayOf(
                    item.id,
                    IndexingTrigger.AUTOMATIC.name,
                    item.state.name,
                    item.attemptCount.toString(),
                ),
            )
            check(count == 1) { "Automatic indexing task changed during control synchronization." }
        }
        return activeItems to reconciledAt
    }

    private fun readQueueItem(db: SQLiteDatabase, itemId: String): IndexingQueueItem? {
        return readQueueItems(
            db = db,
            selection = "id = ?",
            selectionArgs = arrayOf(itemId),
            orderBy = "requested_at_ms ASC",
            limit = 1,
        ).firstOrNull()
    }

    private fun readAutomaticQueueItem(
        db: SQLiteDatabase,
        connectorId: String,
        automaticWindowKey: String,
        automaticGeneration: Long,
    ): IndexingQueueItem? {
        return readQueueItems(
            db = db,
            selection = "trigger = ? AND connector_id = ? AND automatic_window_key = ? " +
                "AND automatic_generation = ?",
            selectionArgs = arrayOf(
                IndexingTrigger.AUTOMATIC.name,
                connectorId,
                automaticWindowKey,
                automaticGeneration.toString(),
            ),
            orderBy = "requested_at_ms ASC",
            limit = 1,
        ).firstOrNull()
    }

    private fun readQueueItems(
        db: SQLiteDatabase,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String,
        limit: Int? = null,
    ): List<IndexingQueueItem> {
        return db.query(
            TABLE_INDEXING_QUEUE,
            null,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            limit?.toString(),
        ).useRows { row -> row.toIndexingQueueItem() }
    }

    private fun Cursor.toIndexingQueueItem(): IndexingQueueItem {
        val task = IndexingTask(
            id = string("id"),
            connectorId = string("connector_id"),
            trigger = enumValueOf(string("trigger")),
            requestedAt = instantFromMillis("requested_at_ms"),
            from = nullableInstantFromMillis("from_at_ms"),
            until = nullableInstantFromMillis("until_at_ms"),
            forceRefresh = int("force_refresh") == 1,
            automaticWindowKey = nullableString("automatic_window_key"),
            automaticGeneration = nullableLong("automatic_generation"),
        )
        return IndexingQueueItem(
            task = task,
            state = enumValueOf(string("state")),
            attemptCount = int("attempt_count"),
            indexedEventCount = int("indexed_event_count"),
            lastAttemptAt = nullableInstantFromMillis("last_attempt_at_ms"),
            startedAt = nullableInstantFromMillis("started_at_ms"),
            completedAt = nullableInstantFromMillis("completed_at_ms"),
            leaseOwner = nullableString("lease_owner"),
            leaseUntil = nullableInstantFromMillis("lease_until_ms"),
            skipReason = nullableString("skip_reason")?.let { enumValueOf<IndexingSkipReason>(it) },
            failureCode = nullableString("failure_code")?.let { enumValueOf<IndexingFailureCode>(it) },
        )
    }

    private fun countQueueRows(
        db: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>,
    ): Int {
        return db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_INDEXING_QUEUE WHERE $selection",
            selectionArgs,
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Could not count indexing queue rows." }
            cursor.getInt(0)
        }
    }

    private fun existingIds(db: SQLiteDatabase, table: String, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return buildSet {
            ids.distinct().chunked(SQLITE_BIND_LIMIT).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.query(
                    table,
                    arrayOf("id"),
                    "id IN ($placeholders)",
                    chunk.toTypedArray(),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }
    }

    private fun readSourceReferences(db: SQLiteDatabase, connectorId: String?): List<SourceReference> {
        val cursor = if (connectorId == null) {
            db.query(TABLE_SOURCE_REFERENCES, null, null, null, null, null, "modified_at DESC")
        } else {
            db.query(
                TABLE_SOURCE_REFERENCES,
                null,
                "connector_id = ?",
                arrayOf(connectorId),
                null,
                null,
                "modified_at DESC",
            )
        }
        return cursor.useRows { row ->
            SourceReference(
                id = row.string("id"),
                connectorId = row.string("connector_id"),
                sourceKind = enumValueOf(row.string("source_kind")),
                localPointer = row.nullableString("local_pointer"),
                externalIdHash = row.nullableString("external_id_hash"),
                hmacHash = row.nullableString("hmac_hash"),
                sourceAppIdentifier = row.nullableString("source_app_identifier"),
                observedAt = row.nullableInstant("observed_at"),
                modifiedAt = row.nullableInstant("modified_at"),
                sensitivity = enumValueOf(row.string("sensitivity")),
            )
        }
    }

    private fun readDerivedMemoryEvents(db: SQLiteDatabase): List<DerivedMemoryEvent> {
        return db.query(TABLE_DERIVED_EVENTS, null, null, null, null, null, "created_at DESC")
            .useRows { row ->
                DerivedMemoryEvent(
                    id = row.string("id"),
                    kind = enumValueOf(row.string("kind")),
                    sourceReferenceIds = decodeList(row.string("source_reference_ids")),
                    summary = row.string("summary"),
                    startedAt = row.nullableInstant("started_at"),
                    endedAt = row.nullableInstant("ended_at"),
                    keywords = decodeList(row.string("keywords")),
                    labels = decodeList(row.string("labels")),
                    entities = decodeList(row.string("entities")),
                    confidence = enumValueOf(row.string("confidence")),
                    sensitivity = enumValueOf(row.string("sensitivity")),
                    citationIds = decodeList(row.string("citation_ids")),
                    createdAt = Instant.parse(row.string("created_at")),
                )
            }
    }

    private fun readCitations(db: SQLiteDatabase): List<MemoryCitation> {
        return db.query(TABLE_CITATIONS, null, null, null, null, null, "observed_at DESC")
            .useRows { row ->
                MemoryCitation(
                    id = row.string("id"),
                    sourceReferenceId = row.string("source_reference_id"),
                    derivedMemoryEventId = row.nullableString("derived_memory_event_id"),
                    label = row.string("label"),
                    observedAt = row.nullableInstant("observed_at"),
                    confidence = enumValueOf(row.string("confidence")),
                )
            }
    }

    private fun readDailySummaries(db: SQLiteDatabase): List<DailyMemorySummary> {
        return db.query(TABLE_DAILY_SUMMARIES, null, null, null, null, null, "date DESC")
            .useRows { row ->
                DailyMemorySummary(
                    id = row.string("id"),
                    date = LocalDate.parse(row.string("date")),
                    summary = row.string("summary"),
                    derivedMemoryEventIds = decodeList(row.string("derived_memory_event_ids")),
                    placeClusterIds = decodeList(row.string("place_cluster_ids")),
                    appUsageSummaryIds = decodeList(row.string("app_usage_summary_ids")),
                    confidence = enumValueOf(row.string("confidence")),
                    missingSources = decodeList(row.string("missing_sources")).map(::decodeMissingSource),
                )
            }
    }

    private fun readPlaceClusters(db: SQLiteDatabase): List<PlaceCluster> {
        return db.query(TABLE_PLACE_CLUSTERS, null, null, null, null, null, "last_seen_at DESC")
            .useRows { row -> row.toPlaceCluster() }
    }

    private fun readPlaceCluster(db: SQLiteDatabase, id: String): PlaceCluster? {
        return db.query(
            TABLE_PLACE_CLUSTERS,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).useRows { row -> row.toPlaceCluster() }.singleOrNull()
    }

    private fun Cursor.toPlaceCluster(): PlaceCluster {
        return PlaceCluster(
            id = string("id"),
            label = nullableString("label"),
            regionLabel = nullableString("region_label"),
            centroidLatitude = nullableDouble("centroid_latitude"),
            centroidLongitude = nullableDouble("centroid_longitude"),
            radiusMeters = nullableDouble("radius_meters"),
            firstSeenAt = nullableInstant("first_seen_at"),
            lastSeenAt = nullableInstant("last_seen_at"),
            visitCount = int("visit_count"),
            sourceReferenceIds = decodeList(string("source_reference_ids")),
            confidence = enumValueOf(string("confidence")),
        )
    }

    private fun readAppUsageSummaries(db: SQLiteDatabase): List<AppUsageSummary> {
        return db.query(TABLE_APP_USAGE_SUMMARIES, null, null, null, null, null, "date DESC")
            .useRows { row ->
                AppUsageSummary(
                    id = row.string("id"),
                    sourceReferenceIds = decodeList(row.string("source_reference_ids")),
                    date = LocalDate.parse(row.string("date")),
                    packageName = row.string("package_name"),
                    appAlias = row.nullableString("app_alias"),
                    category = enumValueOf(row.string("category")),
                    totalDurationMinutes = row.long("total_duration_minutes"),
                    launchCount = row.nullableInt("launch_count"),
                    activeTimeBucketLabels = decodeList(row.string("active_time_bucket_labels")),
                    confidence = enumValueOf(row.string("confidence")),
                )
            }
    }

    private fun readConnectorScanStatuses(db: SQLiteDatabase): List<ConnectorScanStatus> {
        return db.query(TABLE_CONNECTOR_SCAN_STATUS, null, null, null, null, null, "scanned_at DESC")
            .useRows { row ->
                ConnectorScanStatus(
                    connectorId = row.string("connector_id"),
                    processingState = enumValueOf(row.string("processing_state")),
                    missingSources = decodeList(row.string("missing_sources"))
                        .map(::decodeConnectorScanMissingSource),
                    scopeFrom = row.nullableInstant("scope_from"),
                    scopeUntil = row.nullableInstant("scope_until"),
                    scannedAt = Instant.parse(row.string("scanned_at")),
                )
            }
    }

    private fun writeConnectorScanStatus(db: SQLiteDatabase, scanResult: ConnectorScanResult) {
        val values = ConnectorScanStatus(
            connectorId = scanResult.connectorId,
            processingState = scanResult.processingState,
            missingSources = scanResult.missingSources,
            scopeFrom = scanResult.scopeFrom,
            scopeUntil = scanResult.scopeUntil,
            scannedAt = scanResult.scannedAt,
        ).toValues()
        check(
            db.insertWithOnConflict(
                TABLE_CONNECTOR_SCAN_STATUS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L,
        ) { "Could not persist connector scan status." }
    }

    private fun ConnectorScanStatus.toValues(): ContentValues = ContentValues().apply {
        put("connector_id", connectorId)
        put("processing_state", processingState.name)
        put(
            "missing_sources",
            encodeList(missingSources.map { it.toConnectorScanStorageString() }),
        )
        put("scope_from", scopeFrom?.toString())
        put("scope_until", scopeUntil?.toString())
        put("scanned_at", scannedAt.toString())
    }

    private fun isConnectorReconsentRequired(
        db: SQLiteDatabase,
        connectorId: String,
    ): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM $TABLE_CONNECTOR_RECONSENT WHERE connector_id = ? LIMIT 1",
            arrayOf(connectorId),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun requireConnectorWriteAllowed(db: SQLiteDatabase, connectorId: String) {
        requireValidConnectorId(connectorId)
        check(!isConnectorReconsentRequired(db, connectorId)) {
            "Connector requires explicit re-consent before derived data can be stored."
        }
    }

    private fun writeConnectorReconsentRequirement(
        db: SQLiteDatabase,
        connectorId: String,
        requiredSince: Instant,
    ) {
        val values = ContentValues().apply {
            put("connector_id", connectorId)
            put("required_since", requiredSince.toString())
        }
        check(
            db.insertWithOnConflict(
                TABLE_CONNECTOR_RECONSENT,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L,
        ) { "Could not persist connector re-consent requirement." }
    }

    private fun deleteIn(db: SQLiteDatabase, table: String, column: String, ids: List<String>) {
        ids.chunked(SQLITE_BIND_LIMIT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.delete(table, "$column IN ($placeholders)", chunk.toTypedArray())
        }
    }

    private fun SourceReference.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("connector_id", connectorId)
        put("source_kind", sourceKind.name)
        put("local_pointer", localPointer)
        put("external_id_hash", externalIdHash)
        put("hmac_hash", hmacHash)
        put("source_app_identifier", sourceAppIdentifier)
        put("observed_at", observedAt?.toString())
        put("modified_at", modifiedAt?.toString())
        put("sensitivity", sensitivity.name)
    }

    private fun DerivedMemoryEvent.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("kind", kind.name)
        put("source_reference_ids", encodeList(sourceReferenceIds))
        put("summary", summary)
        put("started_at", startedAt?.toString())
        put("ended_at", endedAt?.toString())
        put("keywords", encodeList(keywords))
        put("labels", encodeList(labels))
        put("entities", encodeList(entities))
        put("confidence", confidence.name)
        put("sensitivity", sensitivity.name)
        put("citation_ids", encodeList(citationIds))
        put("created_at", createdAt.toString())
    }

    private fun MemoryCitation.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("source_reference_id", sourceReferenceId)
        put("derived_memory_event_id", derivedMemoryEventId)
        put("label", label)
        put("observed_at", observedAt?.toString())
        put("confidence", confidence.name)
    }

    private fun DailyMemorySummary.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("date", date.toString())
        put("summary", summary)
        put("derived_memory_event_ids", encodeList(derivedMemoryEventIds))
        put("place_cluster_ids", encodeList(placeClusterIds))
        put("app_usage_summary_ids", encodeList(appUsageSummaryIds))
        put("confidence", confidence.name)
        put("missing_sources", encodeList(missingSources.map { it.toStorageString() }))
    }

    private fun PlaceCluster.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("label", label)
        put("region_label", regionLabel)
        put("centroid_latitude", centroidLatitude)
        put("centroid_longitude", centroidLongitude)
        put("radius_meters", radiusMeters)
        put("first_seen_at", firstSeenAt?.toString())
        put("last_seen_at", lastSeenAt?.toString())
        put("visit_count", visitCount)
        put("source_reference_ids", encodeList(sourceReferenceIds))
        put("confidence", confidence.name)
    }

    private fun AppUsageSummary.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("source_reference_ids", encodeList(sourceReferenceIds))
        put("date", date.toString())
        put("package_name", packageName)
        put("app_alias", appAlias)
        put("category", category.name)
        put("total_duration_minutes", totalDurationMinutes)
        put("launch_count", launchCount)
        put("active_time_bucket_labels", encodeList(activeTimeBucketLabels))
        put("confidence", confidence.name)
    }

    private fun MissingSource.toStorageString(): String {
        return JSONArray()
            .put(capability.name)
            .put(availability.name)
            .put(explanation)
            .put(connectorId)
            .put(issueCode?.storageKey)
            .toString()
    }

    private fun MissingSource.toConnectorScanStorageString(): String {
        val stableIssueCode = checkNotNull(issueCode) {
            "Connector scan missing-source status requires a stable issue code."
        }
        return JSONArray()
            .put(CONNECTOR_SCAN_ISSUE_STORAGE_VERSION)
            .put(capability.name)
            .put(availability.name)
            .put(stableIssueCode.storageKey)
            .put(connectorId)
            .toString()
    }

    private fun decodeMissingSource(value: String): MissingSource {
        if (value.startsWith("[")) {
            val array = JSONArray(value)
            return MissingSource(
                capability = enumValueOf<MemoryCapability>(array.getString(0)),
                availability = enumValueOf<SourceAvailability>(array.getString(1)),
                explanation = array.getString(2),
                connectorId = if (array.isNull(3)) null else array.getString(3).takeIf(String::isNotBlank),
                issueCode = if (array.length() <= 4 || array.isNull(4)) {
                    null
                } else {
                    ConnectorScanIssueCode.fromStorageKey(array.getString(4))
                        ?: runCatching { enumValueOf<ConnectorScanIssueCode>(array.getString(4)) }.getOrNull()
                },
            )
        }
        val legacy = value.split('|', limit = 4)
        require(legacy.size == 4) { "Invalid stored missing-source record." }
        return MissingSource(
            capability = enumValueOf(legacy[0]),
            availability = enumValueOf(legacy[1]),
            explanation = legacy[2],
            connectorId = legacy[3].takeIf(String::isNotBlank),
        )
    }

    private fun decodeConnectorScanMissingSource(value: String): MissingSource {
        val array = value.takeIf { it.startsWith("[") }?.let(::JSONArray)
        if (array?.optString(0) == CONNECTOR_SCAN_ISSUE_STORAGE_VERSION) {
            val issueCode = ConnectorScanIssueCode.fromStorageKey(array.getString(3))
                ?: ConnectorScanIssueCode.SOURCE_UNAVAILABLE
            return MissingSource(
                capability = enumValueOf<MemoryCapability>(array.getString(1)),
                availability = enumValueOf<SourceAvailability>(array.getString(2)),
                explanation = issueCode.defaultEnglish,
                connectorId = if (array.isNull(4)) null else array.getString(4).takeIf(String::isNotBlank),
                issueCode = issueCode,
            )
        }
        val decoded = decodeMissingSource(value)
        val issueCode = decoded.issueCode
            ?: ConnectorScanIssueCode.fromStorageKey(decoded.explanation)
            ?: runCatching { enumValueOf<ConnectorScanIssueCode>(decoded.explanation) }.getOrNull()
            ?: ConnectorScanIssueCode.fromLegacyExplanation(decoded.explanation)
            ?: ConnectorScanIssueCode.SOURCE_UNAVAILABLE
        return decoded.copy(
            explanation = issueCode.defaultEnglish,
            issueCode = issueCode,
        )
    }

    private fun encodeList(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    private fun decodeList(value: String): List<String> {
        val array = JSONArray(value)
        return List(array.length()) { index -> array.getString(index) }
    }

    private fun <T> Cursor.useRows(mapper: (Cursor) -> T): List<T> {
        return use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(mapper(cursor))
            }
        }
    }

    private fun Cursor.string(columnName: String): String = getString(getColumnIndexOrThrow(columnName))

    private fun Cursor.int(columnName: String): Int = getInt(getColumnIndexOrThrow(columnName))

    private fun Cursor.long(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))

    private fun Cursor.nullableLong(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.nullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableInstant(columnName: String): Instant? {
        return nullableString(columnName)?.let(Instant::parse)
    }

    private fun Cursor.instantFromMillis(columnName: String): Instant {
        return Instant.ofEpochMilli(long(columnName))
    }

    private fun Cursor.nullableInstantFromMillis(columnName: String): Instant? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else Instant.ofEpochMilli(getLong(index))
    }

    private fun Cursor.nullableDouble(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.nullableInt(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private data class StoredAutomaticRuntime(
        val generation: Long,
        val status: AutomaticIndexingRuntimeStatus,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 7

        private const val DB_NAME = "grayin-memory.db"
        private const val TABLE_SOURCE_REFERENCES = "source_references"
        private const val TABLE_DERIVED_EVENTS = "derived_memory_events"
        private const val TABLE_CITATIONS = "citations"
        private const val TABLE_DAILY_SUMMARIES = "daily_summaries"
        private const val TABLE_PLACE_CLUSTERS = "place_clusters"
        private const val TABLE_APP_USAGE_SUMMARIES = "app_usage_summaries"
        private const val TABLE_INDEXING_QUEUE = "indexing_queue"
        private const val TABLE_AUTOMATIC_INDEXING_RUNTIME = "automatic_indexing_runtime"
        private const val TABLE_AUTOMATIC_INDEXING_CONTROL = "automatic_indexing_control"
        private const val TABLE_CONNECTOR_SCAN_STATUS = "connector_scan_status"
        private const val TABLE_CONNECTOR_RECONSENT = "connector_reconsent"
        private const val CONNECTOR_SCAN_ISSUE_STORAGE_VERSION = "connector-scan-issue-v1"
        private const val SQLITE_BIND_LIMIT = 900
        private const val CORE_SCHEMA_VERSION = 1
        private const val INDEXING_SCHEMA_VERSION = 2
        private const val AUTOMATIC_CONTROL_SCHEMA_VERSION = 3
        private const val CONNECTOR_SCAN_STATUS_SCHEMA_VERSION = 4
        private const val CONNECTOR_SCAN_ISSUE_CODE_SCHEMA_VERSION = 5
        private const val LOCAL_SOURCE_IDENTITY_SCHEMA_VERSION = 6
        private const val CONNECTOR_RECONSENT_SCHEMA_VERSION = CURRENT_SCHEMA_VERSION
        private const val SCHEMA_VERSION = CURRENT_SCHEMA_VERSION
        private const val MAX_QUEUE_SNAPSHOT_ITEMS = 500
        private const val MAX_CONNECTOR_ID_CHARS = 128
        private const val MAX_TRUSTED_CONNECTORS = 64
        private const val MAX_IMPORTED_SOURCE_REFERENCES = 50_000
        private const val MAX_IMPORTED_DERIVED_EVENTS = 50_000
        private const val MAX_IMPORTED_CITATIONS = 50_000
        private const val MAX_IMPORTED_DAILY_SUMMARIES = 10_000
        private const val MAX_IMPORTED_PLACE_CLUSTERS = 10_000
        private const val MAX_IMPORTED_APP_USAGE_SUMMARIES = 50_000
        private const val MAX_IMPORTED_CONNECTOR_SCAN_STATUSES = 64
        private const val MAX_IMPORTED_TOTAL_RECORDS = 200_000L
        private const val UNINITIALIZED_AUTOMATIC_SETTINGS_KEY = "v1:uninitialized"
        private const val IMPORT_RECONSENT_AUTOMATIC_SETTINGS_KEY = "v1:import-reconsent-required"
        private const val LOCAL_FILES_CONNECTOR_ID = "local_files"
        private const val LOCATION_CONNECTOR_ID = "location"

        @Volatile
        private var loaded = false

        private val SQLCIPHER_LOAD_LOCK = Any()
        private val SCHEMA_MIGRATION_LOCK = Any()

        private fun loadSqlCipher() {
            if (loaded) return
            synchronized(SQLCIPHER_LOAD_LOCK) {
                if (!loaded) {
                    System.loadLibrary("sqlcipher")
                    loaded = true
                }
            }
        }
    }
}

internal fun mergeLocationPlaceCluster(
    existing: PlaceCluster?,
    incoming: PlaceCluster,
    newSourceReferenceIds: Set<String>,
): PlaceCluster {
    require(incoming.id.startsWith("place-cluster:location:")) {
        "A location cluster must use the location-scoped ID prefix."
    }
    require(incoming.centroidLatitude != null && incoming.centroidLongitude != null) {
        "A location cluster must have a complete rounded centroid."
    }
    require(
        incoming.centroidLatitude.isFinite() && incoming.centroidLatitude in -90.0..90.0 &&
            incoming.centroidLongitude.isFinite() && incoming.centroidLongitude in -180.0..180.0,
    ) {
        "A location cluster centroid is outside the supported range."
    }
    require(incoming.firstSeenAt != null && incoming.lastSeenAt != null) {
        "A location cluster must have a complete observation range."
    }
    require(!incoming.lastSeenAt.isBefore(incoming.firstSeenAt)) {
        "A location cluster observation range is invalid."
    }
    require(incoming.visitCount == 1 && incoming.sourceReferenceIds.distinct().size == 1) {
        "An incoming location cluster must represent one indexed observation."
    }
    require(newSourceReferenceIds.all { sourceId -> sourceId in incoming.sourceReferenceIds }) {
        "New location-cluster sources must belong to the incoming scan."
    }
    if (existing == null) return incoming.copy(sourceReferenceIds = incoming.sourceReferenceIds.distinct())

    require(existing.id == incoming.id) { "Location cluster IDs must match before merge." }
    require(existing.visitCount >= 0) { "A stored location cluster cannot have a negative visit count." }
    require(
        existing.centroidLatitude == incoming.centroidLatitude &&
            existing.centroidLongitude == incoming.centroidLongitude,
    ) {
        "A stable location cluster ID cannot change its rounded centroid."
    }
    val mergedVisitCount = existing.visitCount.toLong() + newSourceReferenceIds.size
    require(mergedVisitCount <= Int.MAX_VALUE) { "Location cluster visit count exceeds the supported range." }
    return existing.copy(
        label = existing.label ?: incoming.label,
        regionLabel = incoming.regionLabel ?: existing.regionLabel,
        radiusMeters = listOfNotNull(existing.radiusMeters, incoming.radiusMeters).maxOrNull(),
        firstSeenAt = listOfNotNull(existing.firstSeenAt, incoming.firstSeenAt).minOrNull(),
        lastSeenAt = listOfNotNull(existing.lastSeenAt, incoming.lastSeenAt).maxOrNull(),
        visitCount = mergedVisitCount.toInt(),
        sourceReferenceIds = (existing.sourceReferenceIds + incoming.sourceReferenceIds).distinct(),
        confidence = maxOf(existing.confidence, incoming.confidence),
    )
}
