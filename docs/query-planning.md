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

`MemoryCapabilityResolver` derives capability availability from all indexed `DerivedMemoryEvent` kinds. It also assigns the same event-specific capabilities to each retrieved `EvidenceItem`:

- place visits/clusters: time and location
- calendar events: time and calendar
- photo indexes/clusters: time, media, and visual label
- payment, delivery, reservation, and transport signals: time plus their matching capability
- app usage: time and app usage
- local-file, daily-summary, and inferred-context events: time and text

The planner does not invent evidence. It only prepares retrieval requirements from the query, time range, and capability availability.

## Current Retrieval Path

The usable local MVP retrieves from SQLCipher-backed derived events and citations across every connected connector. Capability availability is the union of the capabilities resolved from those stored events, not a fixed Local Files capability set.

The Ask flow converts matching derived events into an `EvidencePack`, then passes only that evidence pack to the grounded answer generator. `MissingEvidenceResolver` preserves planner gaps and, when no event matches the query/time range, reports the required queried capabilities without falsely attributing the gap to Local Files. If location, media, calendar, payment, app usage, or person data is unavailable, the answer lists those capabilities as missing instead of inventing claims.
