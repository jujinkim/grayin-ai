# Non-Agentic Boundary

Grayin AI is intentionally non-agentic.

It does not act on behalf of the user. It helps the user remember, understand, and decide.

## Forbidden Action APIs

Do not implement APIs such as:

- sendEmail
- sendMessage
- makeCall
- createCalendarEvent
- deleteExternalFile
- uploadData
- syncToServer
- postToSocial
- purchase
- reserve
- book
- autoReply

## Allowed Memory APIs

Allowed capabilities include:

- recall
- searchMemory
- summarizeEvidence
- explainReasoning
- showSource
- getTimeline
- getPlaceHistory
- getTopicHistory

## Rule

Do not rely on prompting an LLM not to act.

The action APIs must not exist.
