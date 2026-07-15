package ai.grayin.connectors

import java.util.Locale

/** Closes provider-authored values before they can enter derived memory. */
internal object ConnectorValuePolicy {
    fun closedText(value: String?, maxUtf8Bytes: Int): String? {
        require(maxUtf8Bytes > 0) { "A connector text bound must be positive." }
        if (value.isNullOrBlank()) return null

        val output = StringBuilder(minOf(value.length, maxUtf8Bytes))
        var usedBytes = 0
        var index = 0
        var pendingSpace = false
        while (index < value.length) {
            val first = value[index]
            val codePoint = when {
                first.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    index += 2
                    Character.toCodePoint(first, value[index - 1])
                }

                first.isSurrogate() -> {
                    index += 1
                    pendingSpace = output.isNotEmpty()
                    continue
                }

                else -> {
                    index += 1
                    first.code
                }
            }
            if (isSeparatorOrUnsafe(codePoint)) {
                pendingSpace = output.isNotEmpty()
                continue
            }

            val encoded = String(Character.toChars(codePoint))
            val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size
            val separatorBytes = if (pendingSpace && output.isNotEmpty()) 1 else 0
            if (usedBytes + separatorBytes + encodedBytes > maxUtf8Bytes) break
            if (separatorBytes == 1) {
                output.append(' ')
                usedBytes += 1
            }
            output.append(encoded)
            usedBytes += encodedBytes
            pendingSpace = false
        }
        return output.toString().trim().takeIf(String::isNotBlank)
    }

    fun closedPackageName(value: String?, maxUtf8Bytes: Int = MAX_PACKAGE_NAME_BYTES): String? {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (normalized.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes) return null
        return normalized.takeIf(PACKAGE_NAME::matches)
    }

    fun normalizedLowercasePackageName(value: String?): String? {
        return closedPackageName(value)?.lowercase(Locale.ROOT)
    }

    private fun isSeparatorOrUnsafe(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isWhitespace(codePoint) ||
            Character.isSpaceChar(codePoint) ||
            Character.isISOControl(codePoint) ||
            type == Character.FORMAT.toInt() ||
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.UNASSIGNED.toInt()
    }

    private const val MAX_PACKAGE_NAME_BYTES = 255
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
}
