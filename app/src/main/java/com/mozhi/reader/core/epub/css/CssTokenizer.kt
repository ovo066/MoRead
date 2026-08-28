package com.mozhi.reader.core.epub.css

import kotlinx.serialization.Serializable

@Serializable
data class CssToken(
    val type: CssTokenType,
    val text: String = "",
    val number: Float? = null,
    val unit: String? = null
)

@Serializable
enum class CssTokenType {
    IDENT, AT_KEYWORD, NUMBER, DIMENSION, PERCENTAGE, STRING, URL, HASH,
    WHITESPACE, COLON, SEMICOLON, COMMA,
    LEFT_BRACE, RIGHT_BRACE, LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET, RIGHT_BRACKET,
    DELIM, EOF
}

/** CSS Syntax-style tokenizer. It keeps whitespace because selectors and tuples need it. */
object CssTokenizer {
    fun tokenize(css: String): List<CssToken> = Tokenizer(css).run()

    private class Tokenizer(private val source: String) {
        private val result = ArrayList<CssToken>()
        private var index = 0

        fun run(): List<CssToken> {
            while (index < source.length) {
                when {
                    startsComment() -> consumeComment()
                    source[index].isWhitespace() -> consumeWhitespace()
                    source[index] == '"' || source[index] == '\'' -> consumeString(source[index++])
                    source[index] == '@' && startsIdentifier(index + 1) -> {
                        index++
                        result += CssToken(CssTokenType.AT_KEYWORD, consumeName().lowercase())
                    }
                    source[index] == '#' && source.getOrNull(index + 1)?.let(::isName) == true -> {
                        index++
                        result += CssToken(CssTokenType.HASH, consumeName())
                    }
                    startsNumber(index) -> consumeNumber()
                    startsIdentifier(index) -> consumeIdentifierLike()
                    else -> consumePunctuation()
                }
            }
            result += CssToken(CssTokenType.EOF)
            return result
        }

        private fun startsComment() = index + 1 < source.length && source[index] == '/' && source[index + 1] == '*'

        private fun consumeComment() {
            val end = source.indexOf("*/", index + 2)
            index = if (end < 0) source.length else end + 2
        }

        private fun consumeWhitespace() {
            val start = index
            while (index < source.length && source[index].isWhitespace()) index++
            result += CssToken(CssTokenType.WHITESPACE, source.substring(start, index))
        }

        private fun consumeString(quote: Char) {
            val value = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == quote -> break
                    char == '\\' -> value.append(consumeEscape())
                    char == '\n' || char == '\r' -> break
                    else -> value.append(char)
                }
            }
            result += CssToken(CssTokenType.STRING, value.toString())
        }

        private fun consumeNumber() {
            val start = index
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            while (source.getOrNull(index)?.isDigit() == true) index++
            if (source.getOrNull(index) == '.' && source.getOrNull(index + 1)?.isDigit() == true) {
                index++
                while (source.getOrNull(index)?.isDigit() == true) index++
            }
            if (source.getOrNull(index)?.lowercaseChar() == 'e') {
                var exponent = index + 1
                if (source.getOrNull(exponent) == '+' || source.getOrNull(exponent) == '-') exponent++
                if (source.getOrNull(exponent)?.isDigit() == true) {
                    index = exponent + 1
                    while (source.getOrNull(index)?.isDigit() == true) index++
                }
            }
            val raw = source.substring(start, index)
            val number = raw.toFloatOrNull() ?: 0f
            when {
                source.getOrNull(index) == '%' -> {
                    index++
                    result += CssToken(CssTokenType.PERCENTAGE, "$raw%", number, "%")
                }
                startsIdentifier(index) -> {
                    val unit = consumeName()
                    result += CssToken(CssTokenType.DIMENSION, raw + unit, number, unit.lowercase())
                }
                else -> result += CssToken(CssTokenType.NUMBER, raw, number)
            }
        }

        private fun consumeIdentifierLike() {
            val name = consumeName()
            if (name.equals("url", true) && source.getOrNull(index) == '(') {
                index++
                while (source.getOrNull(index)?.isWhitespace() == true) index++
                val quote = source.getOrNull(index).takeIf { it == '"' || it == '\'' }
                if (quote != null) index++
                val value = StringBuilder()
                while (index < source.length) {
                    val char = source[index]
                    if (quote != null && char == quote) {
                        index++
                        while (source.getOrNull(index)?.isWhitespace() == true) index++
                        if (source.getOrNull(index) == ')') index++
                        break
                    }
                    if (quote == null && char == ')') {
                        index++
                        break
                    }
                    index++
                    if (char == '\\') value.append(consumeEscape()) else value.append(char)
                }
                result += CssToken(CssTokenType.URL, value.toString().trim())
            } else {
                result += CssToken(CssTokenType.IDENT, name)
            }
        }

        private fun consumeName(): String {
            val value = StringBuilder()
            while (index < source.length) {
                val char = source[index]
                when {
                    isName(char) -> {
                        value.append(char)
                        index++
                    }
                    char == '\\' -> {
                        index++
                        value.append(consumeEscape())
                    }
                    else -> break
                }
            }
            return value.toString()
        }

        private fun consumeEscape(): String {
            if (index >= source.length) return ""
            if (source[index].isHexDigit()) {
                val start = index
                while (index < source.length && index - start < 6 && source[index].isHexDigit()) index++
                val codePoint = source.substring(start, index).toIntOrNull(16) ?: 0xFFFD
                if (source.getOrNull(index)?.isWhitespace() == true) index++
                return runCatching { String(Character.toChars(codePoint)) }.getOrDefault("�")
            }
            return source[index++].toString()
        }

        private fun consumePunctuation() {
            val char = source[index++]
            val type = when (char) {
                ':' -> CssTokenType.COLON
                ';' -> CssTokenType.SEMICOLON
                ',' -> CssTokenType.COMMA
                '{' -> CssTokenType.LEFT_BRACE
                '}' -> CssTokenType.RIGHT_BRACE
                '(' -> CssTokenType.LEFT_PAREN
                ')' -> CssTokenType.RIGHT_PAREN
                '[' -> CssTokenType.LEFT_BRACKET
                ']' -> CssTokenType.RIGHT_BRACKET
                else -> CssTokenType.DELIM
            }
            result += CssToken(type, char.toString())
        }

        private fun startsIdentifier(at: Int): Boolean {
            val first = source.getOrNull(at) ?: return false
            return isNameStart(first) || first == '\\' ||
                (first == '-' && (source.getOrNull(at + 1)?.let(::isNameStart) == true || source.getOrNull(at + 1) == '-'))
        }

        private fun startsNumber(at: Int): Boolean {
            val first = source.getOrNull(at) ?: return false
            if (first.isDigit()) return true
            if (first == '.') return source.getOrNull(at + 1)?.isDigit() == true
            if (first == '+' || first == '-') {
                val second = source.getOrNull(at + 1)
                return second?.isDigit() == true ||
                    (second == '.' && source.getOrNull(at + 2)?.isDigit() == true)
            }
            return false
        }

        private fun isNameStart(char: Char) = char == '_' || char.code >= 0x80 || char.isLetter()
        private fun isName(char: Char) = isNameStart(char) || char.isDigit() || char == '-'
        private fun Char.isHexDigit() = isDigit() || lowercaseChar() in 'a'..'f'
    }
}
