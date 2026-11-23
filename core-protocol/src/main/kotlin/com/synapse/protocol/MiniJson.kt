package com.synapse.protocol

/**
 * Zero-dependency, reflection-free RFC-8259 JSON parser and serializer.
 * Designed to keep `core-protocol` and `kernel-engine` ultra-lightweight inside `system_server`.
 */
object MiniJson {

    fun encode(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(sb, value)
        return sb.toString()
    }

    fun parseObject(json: String): Map<String, Any?> {
        val parser = Parser(json)
        val result = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEof()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.pos}")
        }
        @Suppress("UNCHECKED_CAST")
        return result as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected JSON object")
    }

    fun parseArray(json: String): List<Any?> {
        val parser = Parser(json)
        val result = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEof()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.pos}")
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<Any?>
            ?: throw IllegalArgumentException("Expected JSON array")
    }

    fun parseAny(json: String): Any? {
        val parser = Parser(json)
        val result = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEof()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.pos}")
        }
        return result
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value)
            }
            is Float -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value)
            }
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                for (i in value.indices) {
                    if (i > 0) sb.append(',')
                    writeValue(sb, value[i])
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        var pos: Int = 0

        fun isEof(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (isEof()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun parseObj(): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!isEof() && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isEof() || src[pos] != '"') {
                    throw IllegalArgumentException("Expected object key string at $pos")
                }
                val key = parseStr()
                skipWhitespace()
                if (isEof() || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (isEof()) throw IllegalArgumentException("Unterminated JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArr(): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (!isEof() && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (isEof()) throw IllegalArgumentException("Unterminated JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseStr(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string literal")
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("Expected '$expected' at $pos")
            }
            pos += expected.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                val longVal = token.toLong()
                if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) longVal.toInt() else longVal
            }
        }
    }
}

fun Map<String, Any?>.str(key: String, default: String = ""): String =
    (this[key] as? String) ?: default

fun Map<String, Any?>.strOrNull(key: String): String? =
    this[key] as? String

fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? Boolean) ?: default

fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
    (this[key] as? Number)?.toInt() ?: default

fun Map<String, Any?>.long(key: String, default: Long = 0L): Long =
    (this[key] as? Number)?.toLong() ?: default

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.arr(key: String): List<Any?>? =
    this[key] as? List<Any?>
