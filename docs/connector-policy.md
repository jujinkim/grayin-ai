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

Local Files supports explicitly selected Text, Markdown, and PDF documents through the Android document picker. It stores only a domain-separated Android Keystore HMAC selection marker and resolves the live URI from Android's persisted SAF permission at scan time. Selection metadata lookup has a 15-second cancellation bound. Text is read transiently, and cancellation directly closes its underlying descriptor. PDFs are passed as read-only descriptors to the private on-device document process, which returns only bounded page signals. The connector emits HMAC-only source references, canonical structural summaries, bounded keywords, exact closed labels, and closed citations without storing URI, file name, MIME, source text, PDF bytes, images, or OCR transcripts.

Calendar supports explicit runtime calendar read permission plus app-level connection before indexing. It reads Android calendar instances transiently, emits derived calendar events/citations/source references, and does not store raw calendar records. Provider-authored title and location fields are normalized to one line, stripped of control/format characters, and independently capped by UTF-8 byte count before they enter a derived summary, keyword, or citation. Calendar display/account names are not projected or retained; `sourceAppIdentifier` is the fixed `android-calendar` marker. A completed empty provider query emits `NO_CALENDAR_EVENTS_IN_RANGE`; a null cursor emits `SOURCE_UNAVAILABLE` and preserves the prior incremental Calendar graph.

Photos supports explicit runtime media read permission plus app-level connection before indexing. On Android 14+, full-library and user-selected-only access are separate states; selected-only access remains usable, is disclosed as partial, and keeps a reselection action available. The connector sees only MediaStore rows Android permits. It reads metadata transiently, never reads or stores original image bytes, and does not persist `DISPLAY_NAME` or another file name. Only a closed image MIME allowlist, plausible positive dimensions, time, orientation, and fixed citation text can enter derived output. `DATE_TAKEN` is preferred, while a missing/non-positive value uses positive `DATE_MODIFIED` seconds in both range selection and ordering. Every successfully completed MediaStore query atomically replaces the complete stored Photos graph with the bounded result for that latest requested half-open range, including an empty result reported as `NO_PHOTOS_IN_RANGE`. This removes rows that are outside a later narrower range, deleted, or no longer exposed after selected-photo reselection. A query that cannot return a cursor is not authoritative, reports `SOURCE_UNAVAILABLE`, and retains the prior snapshot. Metadata-only events provide media capability but do not claim visual-label capability.

Location supports explicit runtime location permission plus app-level connection before indexing. It reads last-known provider location transiently, emits rounded derived place-visit events/citations/source references, and does not store raw location provider dumps. Android's provider name is reduced to `gps`, `network`, `passive`, `fused`, or `other` before it enters identity, pointer, keyword, or label fields. A separate default-OFF enrichment switch permits the connector to call typed reverse-geocode and weather methods. Reverse geocoding receives only the transient 0.001-degree coordinate; the weather provider projection rounds it again to 0.01 degrees and transmits one UTC date. The two lookups fail independently. Unavailable/invalid/provider failures discard only that enrichment and always preserve local coordinate indexing; coroutine cancellation remains cancellation. Stored weather output is built only from closed WMO code, temperature, and precipitation numbers and never copies provider text, response bodies, URLs, or errors. Revocation disables this consent.

App Usage supports explicit usage-access settings permission plus app-level connection before indexing. It reads Android UsageStats transiently, emits derived app-duration events/citations/source references, and does not store raw usage event dumps. Package names must match the closed transfer grammar and 255-byte bound; invalid rows are skipped. Provider app labels are normalized to one line and capped at 256 UTF-8 bytes, with the package name as fallback. Activity class names are transiently capped before aggregation. The stored minute count is exactly the completed foreground-session duration in milliseconds divided by 60,000 and floored, with a minimum of one; live and imported canonical validation reject a count that disagrees with the closed start/end timestamps. An available provider with no completed sessions reports `NO_APP_USAGE_IN_RANGE` and replaces the prior range snapshot. A missing UsageStats service or null event stream reports `SOURCE_UNAVAILABLE` and preserves that snapshot; transient event-limit failure also preserves it.

Notifications supports explicit notification-listener settings access plus app-level connection before indexing future notification arrivals. Its application-package allowlist is empty by default, so no notification content is read until the user adds an exact bounded Android package name. The listener checks that package before reading extras. Allowed title/text is normalized and capped at 4 KiB total before classification; any field or combined input that exceeds the transient bound causes the whole arrival to be skipped rather than partially classified. Only a closed Android category set can influence classification or labels. The connector skips security-code notifications, emits fixed-shape derived signal events/citations/source references, and never stores raw notification text or a category outside that closed set. Revocation clears both source enablement and the allowlist.

## Indexing Modes

- Calendar, Photos, App Usage, and user-selected Local Files are background-scannable after their existing consent is valid.
- Location is foreground-only. Grayin does not request background-location permission, so WorkManager must never invoke the location connector.
- Notifications are event-driven. The listener derives and stores allowed arrivals immediately; Grayin cannot reconstruct notifications that arrived before listener consent and must not retain raw notification text for later scheduled processing.

Automatic indexing plans only background-scannable connectors. Foreground-only connectors remain available through explicit user actions, and event-driven connectors do not show a misleading manual historical-index action.

All Android entry points use the same validated connector registry. Registry construction rejects blank or duplicate connector IDs, preventing controller and background execution from resolving the same ID to different implementations. Manual and automatic scans share the durable indexing command executor; notification arrivals remain on their event-driven listener path.

Local Files readiness is connector-wide once at least one document is selected; each SAF grant is rechecked inside the scan so one revoked document cannot block valid documents. A Keystore HMAC failure is a connector failure, not revoked permission, so the prior encrypted snapshot remains intact. Every terminal Local Files scan, including an empty or partially unavailable result, is a full atomic connector-snapshot replacement. A 10-minute scan timeout cancels without publishing a partial replacement, while per-document typed failures may coexist with validated output from other selected documents. Selection and revoke serialize grant ownership; revoke verifies that every app-held persisted read grant is gone before clearing markers.

## MVP API Boundary

The MVP connector interface exposes only:

- connector metadata
- connector state
- connector permission state
- scan scope
- scan result
- revoke result
- derived-data deletion result

`ConnectorScanResult` may contain only `SourceReference`, `DerivedMemoryEvent`, `MemoryCitation`, supported derived aggregates, and typed missing-source issue codes. Scan status rejects free-form explanations, runtime exception text, per-file identity, and duplicate issue tuples.

The store applies the same schema-v8 closed-record validator to live scans and encrypted imports. Location, Photos, Calendar, Notifications, and App Usage must use their current canonical source/event/citation shapes, fixed provider/category vocabularies, and bounded aliases; claiming schema v8 cannot bypass those checks with a legacy open record. Schema v8 has no canonical `DailyMemorySummary` or `AppUsageSummary` producer, so both aggregate sections must be empty. The current App Usage connector instead produces canonical per-session events.

Connectors must not expose raw/original content through return values, callbacks, store handoff objects, logs, caches, or errors.

Store APIs must accept connector outputs only as source references, derived memory, citations, summaries, clusters, and index metadata. They must not accept original file bytes, raw notification text, raw message text, raw usage dumps, raw calendar records, raw local-file content, or raw source payloads.

Connectors must not own network clients or endpoints. A connector that needs map/place, reverse-geocode, or weather enrichment may call a typed `OnlineEnrichmentGateway` method with only approved derived lookup inputs. All other connector network access is forbidden by `docs/network-policy.md`.

`OnlineEnrichmentGateway` returns typed available/unavailable results. Provider errors, response bodies, and arbitrary endpoint values never cross the gateway boundary.
