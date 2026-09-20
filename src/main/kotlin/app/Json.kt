package app

/**
 * Minimal dependency-free JSON parser / writer.
 * Supported values: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object Json {
    class ParseException(message: String, val position: Int) : RuntimeException("$message at $position")

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> = parse(text) as List<Any?>

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.eof()) throw ParseException("Trailing characters", p.pos)
        return v
    }

    fun write(value: Any?, indent: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, 0, indent)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?, depth: Int, indent: Boolean) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isFinite() && d == d.toLong().toDouble()) sb.append(d.toLong().toString()) else sb.append(d.toString())
            }
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                val entries = v.entries.toList()
                entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    if (indent) sb.append('\n').append("  ".repeat(depth + 1))
                    writeString(sb, e.key.toString())
                    sb.append(if (indent) ": " else ":")
                    writeValue(sb, e.value, depth + 1, indent)
                }
                if (indent) sb.append('\n').append("  ".repeat(depth))
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (indent) sb.append('\n').append("  ".repeat(depth + 1))
                    writeValue(sb, item, depth + 1, indent)
                }
                if (indent) sb.append('\n').append("  ".repeat(depth))
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Cannot serialize ${v::class}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun eof(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (eof()) throw ParseException("Unexpected end", pos)
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (consume('}')) return m
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                m[key] = readValue()
                skipWs()
                when {
                    consume(',') -> {}
                    consume('}') -> return m
                    else -> throw ParseException("Expected , or }", pos)
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (consume(']')) return list
            while (true) {
                list.add(readValue())
                skipWs()
                when {
                    consume(',') -> {}
                    consume(']') -> return list
                    else -> throw ParseException("Expected , or ]", pos)
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw ParseException("Unterminated string", pos)
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw ParseException("Bad escape", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad unicode escape", pos)
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape \\$e", pos)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBoolean(): Boolean {
            if (s.startsWith("true", pos)) { pos += 4; return true }
            if (s.startsWith("false", pos)) { pos += 5; return false }
            throw ParseException("Invalid literal", pos)
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw ParseException("Invalid literal", pos)
        }

        private fun readNumber(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos < s.length && s[pos] == '0') pos++
            else if (pos < s.length && s[pos] in '1'..'9') { pos++; while (pos < s.length && s[pos].isDigit()) pos++ }
            else throw ParseException("Invalid number", start)
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || !s[pos].isDigit()) throw ParseException("Invalid fraction", start)
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || !s[pos].isDigit()) throw ParseException("Invalid exponent", start)
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            return s.substring(start, pos).toDouble()
        }

        private fun expect(c: Char) {
            if (eof() || s[pos] != c) throw ParseException("Expected '$c'", pos)
            pos++
        }

        private fun consume(c: Char): Boolean {
            if (!eof() && s[pos] == c) { pos++; return true }
            return false
        }
    }
}

/** Convenience typed accessors that fail with clear messages for malformed client payloads. */
object J {
    fun obj(v: Any?, field: String): Map<String, Any?> {
        if (v !is Map<*, *>) throw BadRequest("Expected object")
        @Suppress("UNCHECKED_CAST")
        return v[field] as? Map<String, Any?> ?: throw BadRequest("Missing object field '$field'")
    }

    @Suppress("UNCHECKED_CAST")
    fun o(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: throw BadRequest("Expected object")

    fun str(v: Any?, field: String): String =
        (v as? Map<*, *>)?.get(field) as? String ?: throw BadRequest("Missing string field '$field'")

    fun strOpt(v: Any?, field: String): String? = (v as? Map<*, *>)?.get(field) as? String

    fun intOpt(v: Any?, field: String): Int? = when (val x = (v as? Map<*, *>)?.get(field)) {
        null -> null
        is Number -> x.toInt()
        else -> throw BadRequest("Field '$field' must be a number")
    }

    @Suppress("UNCHECKED_CAST")
    fun strList(v: Any?, field: String): List<String> {
        val x = (v as? Map<*, *>)?.get(field) ?: return emptyList()
        if (x !is List<*>) throw BadRequest("Field '$field' must be a list")
        return x.map { it as? String ?: throw BadRequest("Field '$field' must contain strings") }
    }

    @Suppress("UNCHECKED_CAST")
    fun objList(v: Any?, field: String): List<Map<String, Any?>> {
        val x = (v as? Map<*, *>)?.get(field) ?: return emptyList()
        if (x !is List<*>) throw BadRequest("Field '$field' must be a list")
        return x.map { it as? Map<String, Any?> ?: throw BadRequest("Field '$field' must contain objects") }
    }

    fun bool(v: Any?, field: String, default: Boolean = false): Boolean =
        when (val x = (v as? Map<*, *>)?.get(field)) {
            null -> default
            is Boolean -> x
            else -> throw BadRequest("Field '$field' must be boolean")
        }
}

class BadRequest(message: String) : RuntimeException(message)
