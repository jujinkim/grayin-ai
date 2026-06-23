# Retrieval and Query Planning

Grayin AI uses deterministic query planning before retrieval.

## Query Intents

Supported intent models:

- location recall
- schedule recall
- future busyness check
- photo recall
- notification/payment recall
- app usage recall
- general memory recall
- night-out route reconstruction

## Approximate Time Expressions

The parser supports:

- today
- yesterday
- tomorrow
- this week
- last week
- next week
- last month
- next month
- around this time last year

Time ranges are local and represented as start-inclusive, end-exclusive instants.

## Capability Resolution

Each intent has required and optional `MemoryCapability` sets.

`DefaultQueryPlanner` compares required and optional capabilities against available indexed capabilities and emits missing-source explanations. Missing required capabilities must block confident answers; missing optional capabilities must lower confidence or narrow the answer.

The planner does not invent evidence. It only prepares retrieval requirements from the query, time range, and capability availability.

## Current Retrieval Path

The usable local MVP retrieves from SQLCipher-backed derived events and citations.

Available capabilities currently come from indexed local Text/Markdown files:

- `HAS_TIME`
- `HAS_TEXT`

The Ask flow converts matching derived events into an `EvidencePack`, then passes only that evidence pack to the grounded answer generator. If location, media, calendar, payment, app usage, or person data is unavailable, the answer lists those capabilities as missing instead of inventing claims.
