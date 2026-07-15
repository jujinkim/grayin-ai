package ai.grayin.core.transfer

internal object StrictJsonPreflight {
    fun validate(text: String) {
        Parser(text).validate()
    }

    private class Parser(
        private val text: String,
    ) {
        private var index = 0
        private var tokenCount = 0

        fun validate() {
            skipWhitespace()
            parseValue(depth = 0)
            skipWhitespace()
            require(index == text.length) { "JSON contains trailing content." }
        }

        private fun parseValue(depth: Int) {
            require(depth <= MAX_DEPTH) { "JSON nesting is too deep." }
            require(++tokenCount <= MAX_TOKENS) { "JSON contains too many tokens." }
            skipWhitespace()
            require(index < text.length) { "JSON value is missing." }
            when (text[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString(capture = false)
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Invalid JSON value.")
            }
        }

        private fun parseObject(depth: Int) {
            expect('{')
            skipWhitespace()
            if (consume('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                skipWhitespace()
                require(index < text.length && text[index] == '"') { "JSON object key is missing." }
                val key = requireNotNull(parseString(capture = true))
                require(keys.add(key)) { "JSON object contains a duplicate key." }
                require(++tokenCount <= MAX_TOKENS) { "JSON contains too many tokens." }
                skipWhitespace()
                expect(':')
                parseValue(depth)
                skipWhitespace()
                if (consume('}')) return
                expect(',')
                skipWhitespace()
                require(index < text.length && text[index] != '}') { "JSON object has a trailing comma." }
            }
        }

        private fun parseArray(depth: Int) {
            expect('[')
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                parseValue(depth)
                skipWhitespace()
                if (consume(']')) return
                expect(',')
                skipWhitespace()
                require(index < text.length && text[index] != ']') { "JSON array has a trailing comma." }
            }
        }

        private fun parseString(capture: Boolean): String? {
            expect('"')
            val captured = if (capture) StringBuilder() else null
            var decodedCharacters = 0
            var expectsLowSurrogate = false
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> {
                        require(!expectsLowSurrogate) { "JSON string contains an unpaired surrogate." }
                        return captured?.toString()
                    }
                    character == '\\' -> {
                        require(index < text.length) { "JSON escape is incomplete." }
                        val escaped = text[index++]
                        val decoded = when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> parseUnicodeEscape()
                            else -> throw IllegalArgumentException("JSON escape is invalid.")
                        }
                        expectsLowSurrogate = validateSurrogate(decoded, expectsLowSurrogate)
                        captured?.append(decoded)
                        decodedCharacters++
                    }

                    character.code < SPACE_CODE -> throw IllegalArgumentException("JSON string contains a control character.")
                    else -> {
                        expectsLowSurrogate = validateSurrogate(character, expectsLowSurrogate)
                        captured?.append(character)
                        decodedCharacters++
                    }
                }
                require(decodedCharacters <= MAX_STRING_CHARACTERS) { "JSON string is too long." }
            }
            throw IllegalArgumentException("JSON string is unterminated.")
        }

        private fun validateSurrogate(character: Char, expectsLowSurrogate: Boolean): Boolean {
            return when {
                expectsLowSurrogate -> {
                    require(character.isLowSurrogate()) { "JSON string contains an unpaired surrogate." }
                    false
                }

                character.isLowSurrogate() -> throw IllegalArgumentException(
                    "JSON string contains an unpaired surrogate.",
                )

                character.isHighSurrogate() -> true
                else -> false
            }
        }

        private fun parseUnicodeEscape(): Char {
            require(index + UNICODE_ESCAPE_DIGITS <= text.length) { "JSON Unicode escape is incomplete." }
            var value = 0
            repeat(UNICODE_ESCAPE_DIGITS) {
                val digit = text[index++].digitToIntOrNull(radix = 16)
                    ?: throw IllegalArgumentException("JSON Unicode escape is invalid.")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseLiteral(literal: String) {
            require(text.regionMatches(index, literal, 0, literal.length)) { "JSON literal is invalid." }
            index += literal.length
        }

        private fun parseNumber() {
            consume('-')
            require(index < text.length) { "JSON number is incomplete." }
            if (consume('0')) {
                require(index >= text.length || text[index] !in '0'..'9') { "JSON number has a leading zero." }
            } else {
                require(consumeDigit(from = '1')) { "JSON number is invalid." }
                while (consumeDigit()) Unit
            }
            if (consume('.')) {
                require(consumeDigit()) { "JSON fraction is incomplete." }
                while (consumeDigit()) Unit
            }
            if (index < text.length && text[index] in setOf('e', 'E')) {
                index++
                if (index < text.length && text[index] in setOf('+', '-')) index++
                require(consumeDigit()) { "JSON exponent is incomplete." }
                while (consumeDigit()) Unit
            }
        }

        private fun consumeDigit(from: Char = '0'): Boolean {
            if (index >= text.length || text[index] !in from..'9') return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in JSON_WHITESPACE) index++
        }

        private fun expect(expected: Char) {
            require(consume(expected)) { "Expected JSON delimiter is missing." }
        }

        private fun consume(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index++
            return true
        }
    }

    private const val MAX_DEPTH = 16
    // A JSON value needs at least one byte plus a delimiter. Keep the parser bound aligned with
    // the authenticated plaintext cap so an otherwise valid maximum-size export remains decodable.
    private const val MAX_TOKENS = TransferBounds.MAX_PLAINTEXT_BYTES / 2
    private const val MAX_STRING_CHARACTERS = 16 * 1024
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val SPACE_CODE = 0x20
    private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
}
