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

The answer card starts empty, then renders grounded answers from indexed local Text/Markdown evidence.

## Sources

The Sources screen lists connector-backed state, sensitivity labels, and an intro explaining that sources must be explicitly connected and indexed before Grayin can read/analyze them for Ask.

Local Files supports:

- Add local file
- Index now
- Revoke
- Delete derived data

Other source rows remain disabled until their platform connectors are implemented.

## Settings

Settings includes language selection, a manual local-file index command, operation status, indexed counts, and local-only policy status rows.

Language options are:

- `system`
- `korean`
- `english`
- `japanese`
