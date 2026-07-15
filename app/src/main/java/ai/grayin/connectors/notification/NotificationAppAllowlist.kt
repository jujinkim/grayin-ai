package ai.grayin.connectors.notification

import android.content.Context
import java.util.Locale

data class NotificationAllowlistParseResult(
    val allowedPackages: Set<String>,
    val invalidEntries: List<String>,
)

class NotificationAppAllowlist(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()).orEmpty().toSortedSet()
    }

    fun replace(packages: Set<String>) {
        check(prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages.toSet()).commit()) {
            "Could not persist the notification allowlist."
        }
    }

    fun isAllowed(packageName: String): Boolean {
        return normalize(packageName) in load()
    }

    companion object {
        fun parse(rawValue: String): NotificationAllowlistParseResult {
            val entries = rawValue.split(ENTRY_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotEmpty)
            val valid = entries
                .filter(PACKAGE_NAME::matches)
                .map(::normalize)
                .toSortedSet()
            val invalid = entries.filterNot(PACKAGE_NAME::matches).distinct()
            return NotificationAllowlistParseResult(valid, invalid)
        }

        private fun normalize(packageName: String): String {
            return packageName.trim().lowercase(Locale.ROOT)
        }

        private const val PREFS_NAME = "grayin_notification_allowlist"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private val ENTRY_SEPARATOR = Regex("[,;\\s]+")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
