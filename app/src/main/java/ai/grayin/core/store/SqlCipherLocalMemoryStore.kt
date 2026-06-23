package ai.grayin.core.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

class SqlCipherLocalMemoryStore(
    private val context: Context,
    private val passphraseProvider: StorePassphraseProvider = AndroidKeystorePassphraseProvider(),
) : LocalMemoryStore {
    override suspend fun saveSourceReferences(sourceReferences: List<SourceReference>): StoreWriteResult {
        return writeRows(sourceReferences) { db, source ->
            db.insertWithOnConflict(TABLE_SOURCE_REFERENCES, null, source.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun saveDerivedMemoryEvents(events: List<DerivedMemoryEvent>): StoreWriteResult {
        return writeRows(events) { db, event ->
            db.insertWithOnConflict(TABLE_DERIVED_EVENTS, null, event.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun saveCitations(citations: List<MemoryCitation>): StoreWriteResult {
        return writeRows(citations) { db, citation ->
            db.insertWithOnConflict(TABLE_CITATIONS, null, citation.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
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
        val sourceIds = readSourceReferences(db, connectorId).map { it.id }
        val eventsToDelete = readDerivedMemoryEvents(db).filter { event ->
            event.sourceReferenceIds.any { it in sourceIds }
        }
        val eventIds = eventsToDelete.map { it.id }
        val citationsToDelete = readCitations(db).filter { citation ->
            citation.sourceReferenceId in sourceIds || citation.derivedMemoryEventId in eventIds
        }

        db.beginTransaction()
        try {
            deleteIn(db, TABLE_CITATIONS, "id", citationsToDelete.map { it.id })
            deleteIn(db, TABLE_DERIVED_EVENTS, "id", eventIds)
            deleteIn(db, TABLE_SOURCE_REFERENCES, "id", sourceIds)
            db.delete(TABLE_DAILY_SUMMARIES, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        StoreDeleteResult(
            connectorId = connectorId,
            deletedSourceReferenceIds = sourceIds,
            deletedDerivedMemoryEventIds = eventIds,
            deletedCitationIds = citationsToDelete.map { it.id },
            completedAt = Instant.now(),
        )
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

    private fun <T> writeRows(
        rows: List<T>,
        insert: (SQLiteDatabase, T) -> Long,
    ): StoreWriteResult = withDatabase { db ->
        var inserted = 0
        var skipped = 0
        db.beginTransaction()
        try {
            rows.forEach { row ->
                if (insert(db, row) >= 0L) inserted += 1 else skipped += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        StoreWriteResult(
            insertedCount = inserted,
            updatedCount = 0,
            skippedCount = skipped,
            completedAt = Instant.now(),
        )
    }

    private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
        loadSqlCipher()
        val dbFile = context.getDatabasePath(DB_NAME)
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
        return listOf(capability.name, availability.name, explanation, connectorId.orEmpty()).joinToString("|")
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

    private fun Cursor.nullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableInstant(columnName: String): Instant? {
        return nullableString(columnName)?.let(Instant::parse)
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

        var loaded = false

        fun loadSqlCipher() {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}
