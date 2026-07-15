package ai.grayin.core.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import ai.grayin.core.connector.ConnectorScanResult
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
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
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
                automaticControlIsCurrent
            if (!ownsLiveLease) {
                db.setTransactionSuccessful()
                return@withDatabase ConnectorScanCommitResult.LeaseLost
            }

            require(scanResult.connectorId == current.connectorId) {
                "Connector scan does not belong to the claimed indexing task."
            }
            writeConnectorScanRows(db, scanResult, completedAt)
            val updated = checkNotNull(current).copy(
                state = IndexingQueueState.COMPLETED,
                indexedEventCount = scanResult.derivedEvents.size,
                completedAt = completedAt,
                leaseOwner = null,
                leaseUntil = null,
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

    override suspend fun deleteConnectorData(connectorId: String): StoreDeleteResult = withDatabase { db ->
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
            StoreDeleteResult(
                connectorId = connectorId,
                deletedSourceReferenceIds = sourceIds.toList(),
                deletedDerivedMemoryEventIds = eventIds.toList(),
                deletedCitationIds = citationIds,
                deletedSummaryIds = dailySummaryIds,
                deletedPlaceClusterIds = placeClusterIds.toList(),
                deletedAppUsageSummaryIds = appUsageSummaryIds.toList(),
                completedAt = Instant.now(),
            )
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
            )
            db.setTransactionSuccessful()
            snapshot
        } finally {
            db.endTransaction()
        }
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
            val pending = readQueueItems(
                db = db,
                selection = selection,
                selectionArgs = selectionArgs,
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
        val existingCount = existingIds(
            db,
            TABLE_SOURCE_REFERENCES,
            scanResult.sourceReferences.map { it.id },
        ).size + existingIds(
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
            check(
                db.insertWithOnConflict(
                    TABLE_PLACE_CLUSTERS,
                    null,
                    cluster.toValues(),
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
            .useRows { row ->
                PlaceCluster(
                    id = row.string("id"),
                    label = row.nullableString("label"),
                    regionLabel = row.nullableString("region_label"),
                    centroidLatitude = row.nullableDouble("centroid_latitude"),
                    centroidLongitude = row.nullableDouble("centroid_longitude"),
                    radiusMeters = row.nullableDouble("radius_meters"),
                    firstSeenAt = row.nullableInstant("first_seen_at"),
                    lastSeenAt = row.nullableInstant("last_seen_at"),
                    visitCount = row.int("visit_count"),
                    sourceReferenceIds = decodeList(row.string("source_reference_ids")),
                    confidence = enumValueOf(row.string("confidence")),
                )
            }
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

    private companion object {
        const val DB_NAME = "grayin-memory.db"
        const val TABLE_SOURCE_REFERENCES = "source_references"
        const val TABLE_DERIVED_EVENTS = "derived_memory_events"
        const val TABLE_CITATIONS = "citations"
        const val TABLE_DAILY_SUMMARIES = "daily_summaries"
        const val TABLE_PLACE_CLUSTERS = "place_clusters"
        const val TABLE_APP_USAGE_SUMMARIES = "app_usage_summaries"
        const val TABLE_INDEXING_QUEUE = "indexing_queue"
        const val TABLE_AUTOMATIC_INDEXING_RUNTIME = "automatic_indexing_runtime"
        const val TABLE_AUTOMATIC_INDEXING_CONTROL = "automatic_indexing_control"
        const val SQLITE_BIND_LIMIT = 900
        const val CORE_SCHEMA_VERSION = 1
        const val INDEXING_SCHEMA_VERSION = 2
        const val AUTOMATIC_CONTROL_SCHEMA_VERSION = 3
        const val SCHEMA_VERSION = AUTOMATIC_CONTROL_SCHEMA_VERSION
        const val MAX_QUEUE_SNAPSHOT_ITEMS = 500
        const val UNINITIALIZED_AUTOMATIC_SETTINGS_KEY = "v1:uninitialized"

        @Volatile
        var loaded = false

        val SQLCIPHER_LOAD_LOCK = Any()
        val SCHEMA_MIGRATION_LOCK = Any()

        fun loadSqlCipher() {
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
