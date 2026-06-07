# Local Store Abstraction

MVP 4 defines the local store contract only. It does not add a database implementation.

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

## Connector Delete

Connector-level delete removes source references, derived memory events, citations, summaries, and index entries associated with one connector.

Index invalidation must run after connector-level delete so retrieval cannot cite stale evidence.

## Security TODO

- TODO: choose SQLCipher-backed persistence before adding a real database.
- TODO: derive and protect database keys with Android Keystore before storing sensitive derived data.
