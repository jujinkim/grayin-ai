# Product Principles

Grayin AI is a local-first Android memory indexer.

## Principles

- The user's data stays where it is.
- Grayin AI creates a local memory index, not a raw data vault.
- All sources are explicit opt-in.
- Every source is independently revocable and deletable.
- The app has no application backend, account, cloud storage, or cloud sync.
- Network access is limited to typed map/place/reverse-geocode/weather enrichment and fixed-catalog model, authenticated manifest, or OCR language-data downloads.
- Do not expose arbitrary or user-supplied URL or endpoint calls inside app feature code, connectors, or model output.
- Network requests must not upload raw sources, stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside an approved ephemeral enrichment-request projection.
- Each new network capability requires a typed contract, fixed provider configuration, tests, UI disclosure, and a `docs/network-policy.md` update before implementation.
- The app has no commercial LLM API in MVP.
- The app does not act on behalf of the user.
- The app answers only from local indexed evidence.
- Missing data must be shown as missing data.
- Confidence must reflect available evidence.

## Primary UX

The home screen is Ask-first.

The user asks natural-language memory questions. The app retrieves local evidence and returns grounded answers.
