package ai.grayin.connectors.usagestats

import java.time.Duration
import java.time.Instant

internal enum class AppUsageTransition {
    FOREGROUND,
    BACKGROUND,
}

internal data class AppUsageTransitionEvent(
    val packageName: String,
    val occurredAt: Instant,
    val transition: AppUsageTransition,
    val activityClassName: String? = null,
)

internal data class ExactAppUsageSession(
    val packageName: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val totalForegroundDuration: Duration,
)

internal object AppUsageEventAggregator {
    fun aggregate(
        events: List<AppUsageTransitionEvent>,
        fromInclusive: Instant,
        untilExclusive: Instant,
    ): List<ExactAppUsageSession> {
        require(fromInclusive.isBefore(untilExclusive)) { "App-usage range must be non-empty." }
        val completedSessions = mutableListOf<ExactAppUsageSession>()
        val states = linkedMapOf<String, MutableUsageState>()
        events.asSequence()
            .filter { event ->
                event.packageName.isNotBlank() &&
                    !event.occurredAt.isBefore(fromInclusive) &&
                    event.occurredAt.isBefore(untilExclusive)
            }
            .sortedWith(
                compareBy<AppUsageTransitionEvent> { it.occurredAt }
                    .thenBy { if (it.transition == AppUsageTransition.FOREGROUND) 0 else 1 },
            )
            .forEach { event ->
                val state = states.getOrPut(event.packageName) {
                    MutableUsageState(event.packageName, completedSessions)
                }
                val activityKey = event.activityClassName?.takeIf(String::isNotBlank) ?: FALLBACK_ACTIVITY_KEY
                when (event.transition) {
                    AppUsageTransition.FOREGROUND -> state.onForeground(activityKey, event.occurredAt)
                    AppUsageTransition.BACKGROUND -> state.onBackground(activityKey, event.occurredAt)
                }
            }

        states.values.forEach(MutableUsageState::discardOpenSession)
        return completedSessions
    }

    private class MutableUsageState(
        private val packageName: String,
        private val completedSessions: MutableList<ExactAppUsageSession>,
    ) {
        private val activeActivities = linkedSetOf<String>()
        private var sessionStartedAt: Instant? = null

        fun onForeground(activityKey: String, occurredAt: Instant) {
            if (!activeActivities.add(activityKey)) return
            if (activeActivities.size == 1) {
                sessionStartedAt = occurredAt
            }
        }

        fun onBackground(activityKey: String, occurredAt: Instant) {
            if (!activeActivities.remove(activityKey) || activeActivities.isNotEmpty()) return
            closeSession(occurredAt)
        }

        fun discardOpenSession() {
            activeActivities.clear()
            sessionStartedAt = null
        }

        private fun closeSession(endedAt: Instant) {
            val startedAt = sessionStartedAt ?: return
            if (endedAt.isAfter(startedAt)) {
                completedSessions += ExactAppUsageSession(
                    packageName = packageName,
                    firstSeenAt = startedAt,
                    lastSeenAt = endedAt,
                    totalForegroundDuration = Duration.between(startedAt, endedAt),
                )
            }
            sessionStartedAt = null
        }
    }

    private const val FALLBACK_ACTIVITY_KEY = "*"
}
