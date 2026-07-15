# Local Store

Grayin AI now uses a SQLCipher-backed local store for derived memory data.

The store is opened through `SqlCipherLocalMemoryStore`. The SQLCipher passphrase is generated locally, encrypted with an Android Keystore AES-GCM key, and kept in app-private preferences. First creation is process-synchronized and committed synchronously so concurrent workers cannot open the same database with different transient passphrases. A partial ciphertext/IV preference pair is treated as corruption and fails closed instead of replacing the database passphrase.

## Accepted Data

`LocalMemoryStore` accepts only:

- `SourceReference`
- `DerivedMemoryEvent`
- `MemoryCitation`
- `DailyMemorySummary`
- `PlaceCluster`
- `AppUsageSummary`
- latest derived-only `ConnectorScanStatus`
- connector-scoped delete requests
- index invalidation requests

A connector scan is accepted as one derived-only `ConnectorScanResult`. Source references, events, citations, processing state, bounded scan scope, and typed missing-source issue codes are graph-validated and committed in one transaction. Connector scan status never stores explanation prose. The store rejects blank, duplicate, cross-connector, dangling, code-less, code-mismatched, or unbounded issue lists before any row is written. Local Files receives an additional closed-schema check: full replacement is mandatory; source/event/citation rows are one-to-one; place and app-usage sections are empty; source kinds are Text, Markdown, or PDF page; source identity is a 64-character HMAC with all pointer/external/app fields absent; events use only canonical structural summaries, bounded keyword signals, exact labels and consistent timestamps/confidence; and citation labels cannot contain a file identity. Local Files has no preferences checkpoint: Sources derives its indexed state from the encrypted graph and latest scan status. A scan with no derived rows still commits its encrypted status before the claimed queue item becomes `NO_INDEXABLE_DATA`, so unsupported and partial-source reasons are not silently lost.

Snapshot-style connectors may set `replaceExistingConnectorData`. The store then removes that connector's old derived graph and inserts the new graph in the same transaction, including cleanup of summaries that referenced removed rows. This is required for a PDF whose page count shrinks or for a user-selected file that disappears; event-driven connectors keep incremental upsert behavior.

The store contract must not accept original file bytes, notification originals, message originals, raw calendar records, raw usage logs, raw local-file content, or raw source payloads.

The v2 indexing queue is also SQLCipher-backed. Manual and automatic claims are filtered inside the same transaction that installs the worker lease. Claims exclude any connector with an unexpired running lease, preventing concurrent snapshot replacement for one connector while allowing different connectors to progress. A successful connector execution validates the scan connector ID, claimed item ID, lease owner, attempt number, and unexpired lease in the same SQLCipher transaction that writes derived rows and marks the task complete. A mismatched connector rolls back, and a stale worker returns a lease-lost result before any derived row is written. Other terminal writes require the claimed item ID, lease owner, and attempt number, and expired leases are requeued or failed by a bounded attempt policy.

Schema v3 adds a singleton automatic-control row, queue/runtime generation columns, and generation-scoped automatic-window uniqueness. Disabling automatic indexing performs one transaction that advances control, changes every pending/running automatic task to skipped, clears its lease, and writes the singleton disabled runtime status. Enabled settings changes also advance control and skip older-generation work. Automatic enqueue, claim, derived-row commit, and runtime writes compare against current control in their SQLCipher transaction; manual tasks remain untouched. This both fences in-flight stale output and permits a new generation to enqueue the same window after an off/on cycle. Schema v4 adds one encrypted latest-scan-status row per connector. Schema v5 rewrites legacy scan explanations to fixed issue keys and discards unknown legacy prose as `source_unavailable`. Schema v6 atomically purges legacy Local Files graphs, citations, statuses, and dependent daily summaries before HMAC-only reindexing.

## Read APIs

Read APIs cover all seven allowed derived sections. `loadSnapshot()` reads source references, events, citations, daily summaries, place clusters, app-usage summaries, and latest connector scan status in one database transaction so Ask and future encrypted export cannot mix generations. Ask adds only scan missing-sources relevant to the planned capabilities and whose stored scan scope contains the planned query range; global statuses remain applicable to unbounded queries.

They do not return raw source content. Query and UI layers must build evidence packs from those derived records only.

## Connector Delete

Connector-level delete first changes that connector's pending and running queue rows to `SOURCE_DATA_DELETED`, clears live leases, then calculates and removes source references, derived memory events, citations, place clusters, app-usage summaries, latest scan status, and only the daily summaries that reference those deleted records. The same transaction fences any in-flight commit before removing data. It also removes connector-prefixed orphan rows left by an interrupted legacy write. Selection and deletion happen in the same transaction; unrelated tasks, daily summaries, and connector statuses are preserved.

Index invalidation must run after connector-level delete so retrieval cannot cite stale evidence.

## Security

- SQLCipher encrypts derived memory at rest.
- Android Keystore protects the SQLCipher passphrase.
- The database uses explicit, process-serialized `PRAGMA user_version` migrations: v1 creates derived-memory tables, v2 adds the encrypted indexing queue/runtime tables, v3 adds automatic-control generations, v4 adds connector scan status, v5 converts that status to code-only issue storage, and v6 purges legacy Local Files raw identity. It rejects schemas newer than this app understands.
- Android backup remains disabled in the manifest, legacy full-backup rules, and Android 12+ cloud/device-transfer extraction rules.
- Export/import must use a separately documented encrypted envelope before backup or transfer is added.
