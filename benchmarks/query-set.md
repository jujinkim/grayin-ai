# Grayin AI Benchmark Query Set

Each benchmark answer must be grounded in indexed evidence, include confidence, list missing data, and avoid claims that are not supported by citations.

## 1. Where did I go yesterday?

Required capabilities:

- HAS_TIME
- HAS_LOCATION

Optional capabilities:

- HAS_MEDIA
- HAS_PAYMENT
- HAS_CALENDAR

Expected evidence types:

- place visits
- place clusters
- related photo clusters
- related notification-derived events
- calendar events near visits

Missing-data behavior:

- If location data is unavailable, answer must say location data is unavailable.
- If photos, payments, or calendar data are unavailable, answer may still describe movement from location evidence and must list those sources as missing.

Confidence rules:

- High: multiple place visits exist with good duration and consistent sequence.
- Medium: location is sparse or one optional source supports the route.
- Low: location is missing and the answer depends only on optional indirect evidence.

## 2. What did I do yesterday?

Required capabilities:

- HAS_TIME

Optional capabilities:

- HAS_LOCATION
- HAS_CALENDAR
- HAS_MEDIA
- HAS_PAYMENT
- HAS_APP_USAGE
- HAS_TEXT

Expected evidence types:

- daily summary
- place visits
- calendar events
- photo clusters
- notification-derived events
- app usage summaries
- local note or OCR-derived summaries

Missing-data behavior:

- If no indexed evidence exists for yesterday, answer must say it cannot determine what happened.
- If only one evidence type exists, answer must describe only that evidence type and list unavailable sources.

Confidence rules:

- High: daily summary plus at least two independent evidence types agree.
- Medium: one strong evidence type or sparse evidence across multiple sources.
- Low: only weak indirect evidence exists.

## 3. Was I drinking last week?

Required capabilities:

- HAS_TIME

Optional capabilities:

- HAS_LOCATION
- HAS_PAYMENT
- HAS_MEDIA
- HAS_TEXT

Expected evidence types:

- evening or night place visits
- payment notification-derived events
- food or drink photo labels
- message hints if enabled
- daily summaries

Missing-data behavior:

- If notification/payment data is disabled, exact business or payment evidence cannot be confirmed.
- If location data is missing, route and venue context must be listed as missing.

Confidence rules:

- High: location, payment, and media/text evidence all point to drinking context.
- Medium: two evidence types support the drinking context.
- Low: one weak evidence type suggests drinking but cannot confirm it.

## 4. Did I call my family this week?

Required capabilities:

- HAS_TIME
- HAS_PERSON

Optional capabilities:

- HAS_TEXT
- HAS_CALENDAR

Expected evidence types:

- future call connector events if implemented
- notification missed-call or call-hint events if enabled
- contact/person aliases in derived events
- calendar or note references to family calls

Missing-data behavior:

- If call log data is unavailable, answer must say direct call log evidence is unavailable.
- If person identity is not indexed, answer must say family/person matching is unavailable.

Confidence rules:

- High: direct call evidence exists and person alias matches family.
- Medium: notification or calendar evidence suggests a family call.
- Low: only indirect text or schedule hints exist.

## 5. What meetings did I have yesterday?

Required capabilities:

- HAS_TIME
- HAS_CALENDAR

Optional capabilities:

- HAS_TEXT
- HAS_APP_USAGE
- HAS_LOCATION

Expected evidence types:

- calendar events
- meeting summaries
- local notes
- app usage summaries around meeting times
- place visits around meeting times

Missing-data behavior:

- If calendar data is unavailable, answer must say meetings cannot be determined from calendar evidence.
- If notes or app usage are unavailable, answer may list meeting times/titles from calendar evidence only.

Confidence rules:

- High: calendar events exist with complete time/title evidence.
- Medium: calendar evidence is present but sparse or partially missing.
- Low: no calendar evidence exists and only indirect notes/app usage suggest meetings.

## 6. Am I busy next week?

Required capabilities:

- HAS_TIME
- HAS_CALENDAR

Optional capabilities:

- HAS_APP_USAGE
- HAS_TEXT
- HAS_LOCATION

Expected evidence types:

- future calendar events
- busy-time summaries
- recurring schedule summaries
- local notes with future commitments

Missing-data behavior:

- If calendar data is unavailable, answer must say future busyness cannot be determined.
- If future data has not been indexed, answer must say next week is not indexed.

Confidence rules:

- High: future calendar events cover the week with clear busy/free blocks.
- Medium: calendar data exists but recurring events or notes are incomplete.
- Low: only indirect future hints exist.

## 7. Find food photos from last month.

Required capabilities:

- HAS_TIME
- HAS_MEDIA

Optional capabilities:

- HAS_VISUAL_LABEL
- HAS_LOCATION

Expected evidence types:

- photo memory index
- food labels
- photo clusters
- approximate photo time metadata
- approximate location labels if available

Missing-data behavior:

- If photos are unavailable, answer must say media evidence is unavailable.
- If visual labels are unavailable, answer must say food classification is unavailable or low confidence.

Confidence rules:

- High: photo metadata and food labels exist for last month.
- Medium: photo metadata exists but labels are sparse.
- Low: only location/time hints suggest food photos without visual labels.

## 8. Around this time last year, did I go drinking in Seoul, and what was the route?

Required capabilities:

- HAS_TIME
- HAS_LOCATION

Optional capabilities:

- HAS_PAYMENT
- HAS_MEDIA
- HAS_TEXT
- HAS_APP_USAGE
- HAS_TRANSPORT

Expected evidence types:

- place visits in Seoul
- evening or night route reconstruction
- payment notification-derived events
- photo clusters
- transport notification-derived events
- daily summaries around the period

Missing-data behavior:

- If old data was not indexed or app was not installed then, answer must say so.
- If location is unavailable, route reconstruction must be unavailable.
- If payment/media/text/transport evidence is unavailable, answer must list those sources as missing and avoid venue certainty.

Confidence rules:

- High: location sequence plus at least two optional evidence types support drinking context and route.
- Medium: location sequence exists but optional evidence is sparse.
- Low: old indexed data is sparse or drinking context is indirect.
