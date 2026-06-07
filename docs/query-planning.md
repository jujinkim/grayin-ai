# Retrieval and Query Planning

MVP 7 adds deterministic query planning contracts. It does not retrieve from storage yet.

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
