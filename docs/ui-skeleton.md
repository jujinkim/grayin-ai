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

- Add local file
- Index now
- Revoke
- Delete derived data

Location, Photos, Calendar, Notifications, and App Usage expose permission/settings connection, indexing state, revoke, and delete-derived-data controls.

Location also exposes a separate default-OFF external place/weather enrichment switch. Its disclosure lists the rounded coordinate/date projection, fixed provider, Open-Meteo attribution, possible 90-day provider URL/IP log retention, and local fallback behavior.

Above the connector list, Sources provides Index all now and automatic-indexing controls for enabled state, charging-only behavior, and the local low-usage window. Equal start/end hours are rejected before persistence; a legacy invalid window is durably repaired to a disabled one-hour window.

The indexing-status card refreshes only while Sources and its Activity lifecycle are visible without reloading all derived memory. It shows combined manual/automatic queue depth, only live-lease running sources, latest queue completion, latest automatic control/worker activity, its localized stable outcome/reason, derived-event count, and bounded recent task metadata. One concise dynamic summary uses a polite accessibility live region while the detailed history remains normally navigable; switch rows expose one toggle action and hour controls use localized action descriptions.

## Settings

Settings includes language selection, manual indexing, runtime local-model selection/download/cancel/delete controls, local Gemma `.litertlm` import/delete fallback controls, operation status, indexed counts, and local-first/network-boundary policy status rows.

Language options are:

- `system`
- `korean`
- `english`
- `japanese`
