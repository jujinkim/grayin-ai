# UI

The Compose UI now wires Ask, Sources, Timeline, and Settings to local connector/store state.

The bottom navigation bar shows icons and localized labels for all top-level screens.

## Screens

- Ask
- Timeline
- Places
- Sources
- Settings

Ask remains the normal first screen. On first installed launch only, Sources opens first to explain source connection and indexing requirements.

## Ask

The Ask screen includes:

- memory question input
- enabled search command when query text is present
- answer card
- confidence label
- expandable evidence section
- missing-data section

The answer card starts empty, then renders grounded answers from indexed evidence across connected sources.

## Sources

The Sources screen lists connector-backed state, sensitivity labels, and an intro explaining that sources must be explicitly connected and indexed before Grayin can read/analyze them for Ask.

Local Files supports:

- Add Text, Markdown, or PDF document
- Index now
- Revoke all selected local document permissions
- Delete derived data

The Local Files row discloses the supported formats and that originals are read transiently while PDF/OCR processing stays in the on-device private process. Selection failures cover unsupported formats, the 128-document selection bound, and unavailable persisted read access. Revoke removes all selected document grants and derived Local Files data; deleting derived data keeps the grants for later reindexing.

Location, Photos, Calendar, Notifications, and App Usage expose permission/settings connection, indexing state, revoke, and delete-derived-data controls.

Location also exposes a separate default-OFF external place/weather enrichment switch. Its disclosure lists the rounded coordinate/date projection, fixed provider, Open-Meteo attribution, possible 90-day provider URL/IP log retention, and local fallback behavior.

Above the connector list, Sources provides Index all now and automatic-indexing controls for enabled state, charging-only behavior, and the local low-usage window. Equal start/end hours are rejected before persistence; a legacy invalid window is durably repaired to a disabled one-hour window.

The indexing-status card refreshes only while Sources and its Activity lifecycle are visible without reloading all derived memory. It shows combined manual/automatic queue depth, only live-lease running sources, latest queue completion, latest automatic control/worker activity, its localized stable outcome/reason, derived-event count, and bounded recent task metadata. One concise dynamic summary uses a polite accessibility live region while the detailed history remains normally navigable; switch rows expose one toggle action and hour controls use localized action descriptions.

## Settings

Settings includes language selection, manual indexing, OCR language-data controls, runtime local-model selection and official-page controls, local Gemma `.litertlm` import/delete fallback controls, operation status, indexed counts, and local-first/network-boundary policy status rows. Model download/cancel/delete actions are conditional and currently hidden because no catalog entry has complete transport metadata.

The OCR section exposes only the fixed English, Korean, and Japanese packs. It shows exact download size, Apache-2.0 license, pinned `tessdata_fast` commit, localized status/progress/failure, and install/cancel/delete actions. Its disclosure states that documents stay on-device, the artifact host can see the selected pack path and ordinary network metadata, and no document data is sent. Installing a pack is separate from selecting or indexing a PDF; indexing never initiates a download.

Language options are:

- `system`
- `korean`
- `english`
- `japanese`
