# Local Store

Grayin AI now uses a SQLCipher-backed local store for derived memory data.

The store is opened through `SqlCipherLocalMemoryStore`. The SQLCipher passphrase is generated locally, encrypted with an Android Keystore AES-GCM key, and kept in app-private preferences.

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

The store contract must not accept original file bytes, notification originals, message originals, raw calendar records, raw usage logs, raw local-file content, or raw source payloads.

## Read APIs

Read APIs return only `SourceReference`, `DerivedMemoryEvent`, and `MemoryCitation` records.

They do not return raw source content. Query and UI layers must build evidence packs from those derived records only.

## Connector Delete

Connector-level delete removes source references, derived memory events, citations, summaries, and index entries associated with one connector.

Index invalidation must run after connector-level delete so retrieval cannot cite stale evidence.

## Security

- SQLCipher encrypts derived memory at rest.
- Android Keystore protects the SQLCipher passphrase.
- Android backup remains disabled in the manifest.
- Export/import must use a separately documented encrypted envelope before backup or transfer is added.
