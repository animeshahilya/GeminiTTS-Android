package com.animeshahilya.geminitts.data

/**
 * Compact pure-Kotlin JSON parser and formatter so settings, dictionary rules, and backups run
 * identically in Android Direct Boot and JVM unit tests without extra dependencies.
 */
object JsonCodec {

    private const val MAX_DEPTH = 24

    fun stringify(value: Any?, pretty: Boolean = false): String = buildString {
        writeValue(value, this, pretty, 0)
    }

    fun parseObject(json: String): Map<String, Any?>? {
        val parsed = runCatching { Parser(json).parse() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?>
    }

    fun parseArray(json: String): List<Any?>? {
        val parsed = runCatching { Parser(json).parse() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return parsed as? List<Any?>
    }

    private fun writeValue(value: Any?, out: StringBuilder, pretty: Boolean, depth: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long -> out.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                out.append(if (d.isFinite()) value.toString() else "0")
            }
            is String -> writeString(value, out)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k == null) continue
                    if (!first) out.append(',')
                    first = false
                    if (pretty) appendIndent(out, depth + 1)
                    writeString(k.toString(), out)
                    out.append(if (pretty) ": " else ":")
                    writeValue(v, out, pretty, depth + 1)
                }
                if (pretty) appendIndent(out, depth)
                out.append('}')
            }
            is Iterable<*> -> {
                val iter = value.iterator()
                if (!iter.hasNext()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    if (pretty) appendIndent(out, depth + 1)
                    writeValue(item, out, pretty, depth + 1)
                }
                if (pretty) appendIndent(out, depth)
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun appendIndent(out: StringBuilder, depth: Int) {
        out.append('\n')
        repeat(depth * 2) { out.append(' ') }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u%04x".format(ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWhitespace()
            val v = readValue(0)
            skipWhitespace()
            if (pos != src.length) error("Trailing content at $pos")
            return v
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) error("JSON nesting too deep")
            skipWhitespace()
            if (pos >= src.length) error("Unexpected EOF")
            return when (src[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> error("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') error("Expected string key at $pos")
                val key = readString()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') error("Expected ':' at $pos")
                pos++
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (pos >= src.length) error("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (pos >= src.length) error("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val out = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= src.length) error("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("Short unicode escape")
                                out.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("Invalid escape \\$esc")
                        }
                    }
                    else -> out.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun readBoolean(): Boolean = if (src.startsWith("true", pos)) {
            pos += 4; true
        } else if (src.startsWith("false", pos)) {
            pos += 5; false
        } else {
            error("Invalid boolean at $pos")
        }

        private fun readNull(): Any? {
            if (!src.startsWith("null", pos)) error("Invalid null at $pos")
            pos += 4
            return null
        }

        private fun readNumber(): Number {
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
            val raw = src.substring(start, pos)
            return if (isFloat) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
