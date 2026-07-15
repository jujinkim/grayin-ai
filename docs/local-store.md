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
- connector-scoped delete requests
- index invalidation requests

A connector scan is accepted as one derived-only `ConnectorScanResult`. Source references, events, and citations are graph-validated and committed in one transaction. The store rejects blank, duplicate, cross-connector, or dangling IDs before any row is written. A connector records `lastIndexedAt` only after that transaction succeeds.

The store contract must not accept original file bytes, notification originals, message originals, raw calendar records, raw usage logs, raw local-file content, or raw source payloads.

## Read APIs

Read APIs cover all six allowed derived sections. `loadSnapshot()` reads source references, events, citations, daily summaries, place clusters, and app-usage summaries in one database transaction so Ask and future encrypted export cannot mix generations.

They do not return raw source content. Query and UI layers must build evidence packs from those derived records only.

## Connector Delete

Connector-level delete calculates and removes source references, derived memory events, citations, place clusters, app-usage summaries, and only the daily summaries that reference those deleted records. It also removes connector-prefixed orphan rows left by an interrupted legacy write. Selection and deletion happen in the same transaction; unrelated daily summaries are preserved.

Index invalidation must run after connector-level delete so retrieval cannot cite stale evidence.

## Security

- SQLCipher encrypts derived memory at rest.
- Android Keystore protects the SQLCipher passphrase.
- The database uses an explicit `PRAGMA user_version` migration boundary and rejects schemas newer than this app understands.
- Android backup remains disabled in the manifest.
- Export/import must use a separately documented encrypted envelope before backup or transfer is added.
