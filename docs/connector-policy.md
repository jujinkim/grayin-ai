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

All connectors default OFF.

Local Files supports explicit user-selected `.txt` and `.md` documents through the Android document picker. It reads source text transiently, emits only derived keyword/summary metadata, and does not store raw file contents.

Calendar supports explicit runtime calendar read permission plus app-level connection before indexing. It reads Android calendar instances transiently, emits derived calendar events/citations/source references, and does not store raw calendar records.

Photos supports explicit runtime media read permission plus app-level connection before indexing. It reads Android MediaStore image metadata transiently, emits derived photo index events/citations/source references, and never reads or stores original image bytes. Metadata-only events provide media capability but do not claim visual-label capability.

Location supports explicit runtime location permission plus app-level connection before indexing. It reads last-known provider location transiently, emits rounded derived place-visit events/citations/source references, and does not store raw location provider dumps. A separate default-OFF enrichment switch permits the connector to pass only a transient 0.001-degree coordinate to `OnlineEnrichmentGateway.reverseGeocode`; failure or denial keeps the coordinate-only local result. Revocation disables this consent.

App Usage supports explicit usage-access settings permission plus app-level connection before indexing. It reads Android UsageStats transiently, emits derived app-duration events/citations/source references, and does not store raw usage event dumps.

Notifications supports explicit notification-listener settings access plus app-level connection before indexing future notification arrivals. Its application-package allowlist is empty by default, so no notification content is read until the user adds an exact Android package name. The listener checks the package before reading notification extras, reads allowed title/text transiently, skips security-code notifications, emits derived notification signal events/citations/source references, and does not store raw notification text. Revocation clears both source enablement and the allowlist.

## Indexing Modes

- Calendar, Photos, App Usage, and user-selected Local Files are background-scannable after their existing consent is valid.
- Location is foreground-only. Grayin does not request background-location permission, so WorkManager must never invoke the location connector.
- Notifications are event-driven. The listener derives and stores allowed arrivals immediately; Grayin cannot reconstruct notifications that arrived before listener consent and must not retain raw notification text for later scheduled processing.

Automatic indexing plans only background-scannable connectors. Foreground-only connectors remain available through explicit user actions, and event-driven connectors do not show a misleading manual historical-index action.

All Android entry points use the same validated connector registry. Registry construction rejects blank or duplicate connector IDs, preventing controller and background execution from resolving the same ID to different implementations. Manual and automatic scans share the durable indexing command executor; notification arrivals remain on their event-driven listener path.

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

Connectors must not own network clients or endpoints. A connector that needs map/place, reverse-geocode, or weather enrichment may call a typed `OnlineEnrichmentGateway` method with only approved derived lookup inputs. All other connector network access is forbidden by `docs/network-policy.md`.

`OnlineEnrichmentGateway` returns typed available/unavailable results. Provider errors, response bodies, and arbitrary endpoint values never cross the gateway boundary.
