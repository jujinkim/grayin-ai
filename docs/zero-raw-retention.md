# Zero Raw Retention

Zero Raw Retention is the central privacy rule of Grayin AI.

## Definition

Raw original data is only accessed transiently in memory, processed immediately, and discarded.

It must never be:

- stored
- logged
- cached
- exported
- synced
- transmitted
- backed up
- passed to the local store
- passed to the LLM

## Raw Original Examples

- original photos
- notification text
- message text
- PDF files
- local file content
- calendar raw records
- app usage event dumps
- browser history dumps
- note files
- audio/video originals

## Allowed Data

Grayin AI may store:

- SourceReference
- HMAC hash
- timestamp
- source app/package
- minimal extracted fields
- summary
- keywords
- labels
- entities
- embedding
- citation metadata
- confidence
- derived memory events
- daily summaries

All derived data is still sensitive and must be encrypted in the final implementation.

## Engineering Rule

There must be no store API that accepts raw content.

Connectors may read raw source data only inside connector-owned transient processing scopes. They may emit only derived events and source references.

## Core Model Rule

Core model types may represent only:

- source references
- derived memory events
- derived summaries
- keywords, labels, and entities
- citation metadata
- confidence
- missing-source explanations
- connector and processing state

Core model types must not add fields for original file bytes, raw notification text, raw message text, raw local-file content, raw calendar records, raw usage event dumps, or any raw content blob.
