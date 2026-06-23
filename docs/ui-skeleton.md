# UI

The Compose UI now wires Ask, Sources, Timeline, and Settings to local connector/store state.

## Screens

- Ask
- Timeline
- Places
- Sources
- Settings

Ask remains the first screen.

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

The Sources screen lists connector state and sensitivity labels.

Local Files can be indexed and queried. Other source rows remain disabled until their platform connectors are implemented.

## Settings

Settings includes a manual local-file index command and local-only policy status rows.
