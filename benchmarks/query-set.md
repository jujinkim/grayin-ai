# Grayin AI Benchmark Query Set

## 1. Where did I go yesterday?

Required capabilities:

- HAS_TIME
- HAS_LOCATION

Optional capabilities:

- HAS_MEDIA
- HAS_PAYMENT
- HAS_CALENDAR

Expected evidence:

- place visits
- place clusters
- related photo clusters
- related notification events

Missing-data behavior:

- If location connector is unavailable, answer must say location data is unavailable.

Confidence rules:

- High when multiple place visits exist with good duration/accuracy.
- Medium when location is sparse.
- Low when inferred only from photos/calendar.

## 2. What did I do yesterday?

Required capabilities:

- HAS_TIME

Optional capabilities:

- HAS_LOCATION
- HAS_CALENDAR
- HAS_MEDIA
- HAS_PAYMENT
- HAS_APP_USAGE

Expected evidence:

- daily summary
- place visits
- calendar events
- photo clusters
- notification-derived events
- app usage summaries

## 3. Was I drinking last week?

Required capabilities:

- HAS_TIME

Optional capabilities:

- HAS_LOCATION
- HAS_PAYMENT
- HAS_MEDIA
- HAS_TEXT

Expected evidence:

- evening/night place visits
- payment notification events
- food/drink photo labels
- message hints if enabled

Missing-data behavior:

- If notification/payment data is disabled, exact business/payment evidence cannot be confirmed.

## 4. Did I call my family this week?

Required capabilities:

- HAS_TIME
- HAS_PERSON

Optional capabilities:

- call event connector if implemented later
- notification missed-call hints
- contacts

Missing-data behavior:

- If call log is unavailable, answer must say direct call log is unavailable.

## 5. What meetings did I have yesterday?

Required capabilities:

- HAS_TIME
- HAS_CALENDAR

Optional capabilities:

- local notes
- app usage
- notification reminders

## 6. Am I busy next week?

Required capabilities:

- HAS_TIME
- HAS_CALENDAR

Expected evidence:

- future calendar events
- busy-time summaries

## 7. Find food photos from last month.

Required capabilities:

- HAS_TIME
- HAS_MEDIA

Optional capabilities:

- HAS_VISUAL_LABEL
- HAS_LOCATION

Expected evidence:

- photo memory index
- food labels
- photo clusters

## 8. Around this time last year, did I go drinking in Seoul, and what was the route?

Required capabilities:

- HAS_TIME
- HAS_LOCATION

Optional capabilities:

- HAS_PAYMENT
- HAS_MEDIA
- HAS_TEXT
- HAS_APP_USAGE

Expected evidence:

- place visits in Seoul
- evening/night route reconstruction
- payment notification events
- photo clusters
- transport notification events

Missing-data behavior:

- If old data was not indexed or app was not installed then, answer must say so.
