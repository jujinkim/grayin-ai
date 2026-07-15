package ai.grayin.core.retrieval

import ai.grayin.core.model.MemoryCapability
import java.time.Instant

interface QueryPlanner {
    fun plan(
        query: String,
        availableCapabilities: Set<MemoryCapability>,
        now: Instant = Instant.now(),
    ): QueryPlan
}

class DefaultQueryPlanner(
    private val timeParser: ApproximateTimeParser = ApproximateTimeParser(),
) : QueryPlanner {
    override fun plan(
        query: String,
        availableCapabilities: Set<MemoryCapability>,
        now: Instant,
    ): QueryPlan {
        val intent = detectIntent(query)
        val timeRange = timeParser.parse(query, now)
        return profileFor(intent, query).resolve(
            query = query,
            intent = intent,
            availableCapabilities = availableCapabilities,
            timeRange = timeRange,
        )
    }

    private fun detectIntent(query: String): QueryIntent {
        val normalized = query.lowercase()

        return when {
            normalized.containsAny("route", "1st", "2nd", "3rd", "경로", "1차", "2차", "3차", "経路", "ルート", "1軒目", "2軒目", "3軒目") &&
                normalized.containsAny("drink", "drinking", "drank", "bar", "seoul", "술", "술집", "서울", "飲み", "酒", "バー", "ソウル") -> {
                QueryIntent.NIGHT_OUT_ROUTE_RECONSTRUCTION
            }

            normalized.containsAny("busy", "available", "free", "바빠", "한가", "시간 돼", "忙しい", "空いて", "暇") &&
                normalized.containsAny("next week", "tomorrow", "next month", "다음 주", "다음주", "내일", "다음 달", "다음달", "来週", "明日", "来月") -> {
                QueryIntent.FUTURE_BUSYNESS_CHECK
            }

            normalized.containsAny("photo", "photos", "picture", "image", "food", "사진", "이미지", "음식", "写真", "画像", "食べ物", "料理") -> {
                QueryIntent.PHOTO_RECALL
            }

            normalized.containsAny("meeting", "meetings", "calendar", "schedule", "회의", "미팅", "캘린더", "일정", "会議", "ミーティング", "カレンダー", "予定") -> {
                QueryIntent.SCHEDULE_RECALL
            }

            normalized.containsAny("payment", "paid", "delivery", "reservation", "transport", "receipt", "결제", "지불", "배달", "배송", "예약", "교통", "영수증", "支払い", "決済", "配達", "配送", "予約", "交通", "領収書") -> {
                QueryIntent.NOTIFICATION_PAYMENT_RECALL
            }

            normalized.containsAny("app usage", "screen time", "used my phone", "apps", "앱 사용", "스크린 타임", "휴대폰", "アプリ使用", "スクリーンタイム", "スマホ") -> {
                QueryIntent.APP_USAGE_RECALL
            }

            normalized.containsAny("where", "went", "go yesterday", "places", "place", "어디", "갔", "장소", "どこ", "行った", "場所") -> {
                QueryIntent.LOCATION_RECALL
            }

            else -> QueryIntent.GENERAL_MEMORY_RECALL
        }
    }

    private fun profileFor(intent: QueryIntent, query: String): IntentCapabilityProfile {
        return when (intent) {
            QueryIntent.LOCATION_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
                optional = setOf(
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_CALENDAR,
                ),
            )

            QueryIntent.SCHEDULE_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
                optional = setOf(MemoryCapability.HAS_TEXT, MemoryCapability.HAS_APP_USAGE),
            )

            QueryIntent.FUTURE_BUSYNESS_CHECK -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
                optional = setOf(MemoryCapability.HAS_APP_USAGE, MemoryCapability.HAS_TEXT),
            )

            QueryIntent.PHOTO_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_MEDIA),
                optional = setOf(MemoryCapability.HAS_VISUAL_LABEL, MemoryCapability.HAS_LOCATION),
            )

            QueryIntent.NOTIFICATION_PAYMENT_RECALL -> notificationProfile(query.lowercase())

            QueryIntent.APP_USAGE_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_APP_USAGE),
            )

            QueryIntent.GENERAL_MEMORY_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME),
                optional = setOf(
                    MemoryCapability.HAS_LOCATION,
                    MemoryCapability.HAS_CALENDAR,
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_APP_USAGE,
                    MemoryCapability.HAS_TEXT,
                    MemoryCapability.HAS_PERSON,
                ),
            )

            QueryIntent.NIGHT_OUT_ROUTE_RECONSTRUCTION -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
                optional = setOf(
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_TEXT,
                    MemoryCapability.HAS_APP_USAGE,
                    MemoryCapability.HAS_TRANSPORT,
                ),
            )
        }
    }

    private fun notificationProfile(normalized: String): IntentCapabilityProfile {
        val requested = when {
            normalized.containsAny("delivery", "배달", "배송", "配達", "配送") -> MemoryCapability.HAS_DELIVERY
            normalized.containsAny("reservation", "예약", "予約") -> MemoryCapability.HAS_RESERVATION
            normalized.containsAny("transport", "교통", "交通") -> MemoryCapability.HAS_TRANSPORT
            else -> MemoryCapability.HAS_PAYMENT
        }
        val notificationCapabilities = setOf(
            MemoryCapability.HAS_PAYMENT,
            MemoryCapability.HAS_DELIVERY,
            MemoryCapability.HAS_RESERVATION,
            MemoryCapability.HAS_TRANSPORT,
        )
        return IntentCapabilityProfile(
            required = setOf(MemoryCapability.HAS_TIME, requested),
            optional = (notificationCapabilities - requested) + MemoryCapability.HAS_TEXT,
        )
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it) }
    }
}
