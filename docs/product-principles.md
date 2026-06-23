# Product Principles

Grayin AI is a local-first Android memory indexer.

## Principles

- The user's data stays where it is.
- Grayin AI creates a local memory index, not a raw data vault.
- All sources are explicit opt-in.
- Every source is independently revocable and deletable.
- The app has no server, account, or cloud in MVP.
- Internet permission is allowed only through typed online enrichment methods, such as weather or reverse-geocode lookups.
- Do not expose arbitrary URL or endpoint calls inside app feature code.
- Online enrichment must not upload raw/original source data or create cloud sync.
- The app has no commercial LLM API in MVP.
- The app does not act on behalf of the user.
- The app answers only from local indexed evidence.
- Missing data must be shown as missing data.
- Confidence must reflect available evidence.

## Primary UX

The home screen is Ask-first.

The user asks natural-language memory questions. The app retrieves local evidence and returns grounded answers.
