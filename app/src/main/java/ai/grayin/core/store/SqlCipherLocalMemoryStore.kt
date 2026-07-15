package ai.grayin.core.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import ai.grayin.core.connector.ConnectorScanResult
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
import java.time.Instant
import java.time.LocalDate
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

class SqlCipherLocalMemoryStore(
    private val context: Context,
    private val passphraseProvider: StorePassphraseProvider = AndroidKeystorePassphraseProvider(),
    private val databaseName: String = DB_NAME,
) : LocalMemoryStore {
    override suspend fun saveConnectorScan(scanResult: ConnectorScanResult): StoreWriteResult = withDatabase { db ->
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
            val totalCount = scanResult.sourceReferences.size +
                scanResult.derivedEvents.size +
                scanResult.citations.size +
                scanResult.placeClusters.size +
                scanResult.appUsageSummaries.size
            StoreWriteResult(
                insertedCount = totalCount - existingCount,
                updatedCount = existingCount,
                completedAt = Instant.now(),
            )
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
            ensureSchema(db)
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
        if (version >= SCHEMA_VERSION) return

        db.beginTransaction()
        try {
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
            db.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    private fun Cursor.nullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableInstant(columnName: String): Instant? {
        return nullableString(columnName)?.let(Instant::parse)
    }

    private fun Cursor.nullableDouble(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.nullableInt(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private companion object {
        const val DB_NAME = "grayin-memory.db"
        const val TABLE_SOURCE_REFERENCES = "source_references"
        const val TABLE_DERIVED_EVENTS = "derived_memory_events"
        const val TABLE_CITATIONS = "citations"
        const val TABLE_DAILY_SUMMARIES = "daily_summaries"
        const val TABLE_PLACE_CLUSTERS = "place_clusters"
        const val TABLE_APP_USAGE_SUMMARIES = "app_usage_summaries"
        const val SQLITE_BIND_LIMIT = 900
        const val SCHEMA_VERSION = 1

        @Volatile
        var loaded = false

        val SQLCIPHER_LOAD_LOCK = Any()

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
