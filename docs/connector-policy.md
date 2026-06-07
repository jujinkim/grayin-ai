# Connector Policy

Connectors are the only modules that may access original sources.

## Connector Responsibilities

A connector may:

- request or inspect permission state
- scan for source references
- process a source reference transiently
- emit derived memory events
- report connector state
- support revoke/delete

A connector must not:

- persist raw content
- log raw content
- cache raw content
- export raw content
- transmit raw content
- expose raw content to store/core/LLM

## MVP Connectors

- Location Connector
- Photos Connector
- Calendar Connector
- Notification Connector
- App Usage Connector
- Local Files Connector

## Sensitivity

Notification and app usage connectors are highly sensitive.

They must default to OFF and require strong opt-in UX.

MVP 6 sensitivity defaults:

- Location: high
- Photos: high
- Calendar: high
- Notifications: very high
- App Usage: very high
- Local Files: high

All MVP 6 stubs default OFF.

## MVP API Boundary

The MVP connector interface exposes only:

- connector metadata
- connector state
- connector permission state
- scan scope
- scan result
- revoke result
- derived-data deletion result

`ConnectorScanResult` may contain only `SourceReference`, `DerivedMemoryEvent`, `MemoryCitation`, and missing-source explanations.

Connectors must not expose raw/original content through return values, callbacks, store handoff objects, logs, caches, or errors.

Store APIs must accept connector outputs only as source references, derived memory, citations, summaries, clusters, and index metadata. They must not accept original file bytes, raw notification text, raw message text, raw usage dumps, raw calendar records, raw local-file content, or raw source payloads.
