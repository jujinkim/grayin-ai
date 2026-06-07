# Privacy Model

Grayin AI is designed around user-owned local memory.

## Defaults

- Offline by default.
- No network permission in MVP.
- No account.
- No server.
- No cloud.
- No telemetry.
- No ads.
- No crash analytics SDK.
- No raw data retention.

## Consent

Every connector requires explicit opt-in.

Every connector must support:

- enable
- disable
- revoke
- delete derived data
- show permission state
- show indexing state

## Derived Data Is Sensitive

Even without raw originals, derived data can reveal sensitive information.

Examples:

- place clusters
- payment events
- app usage patterns
- photo keywords
- OCR text
- calendar summaries
- daily summaries

Final implementation must encrypt local storage.

## Local Store Security TODOs

- SQLCipher-backed persistence must be chosen before a real database is added.
- Android Keystore must protect database keys before sensitive derived data is stored persistently.
- Store APIs must accept only source references, derived memory, citations, summaries, clusters, and index metadata.
- Android backup remains disabled in the MVP manifest until encrypted export/import is implemented.
