package com.karal.studio.core.json

/**
 * Small dependency-free JSON reader/writer used by the shared core.
 * It is deliberately strict: malformed input is rejected with [JsonException]
 * instead of being silently repaired.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

class JsonException(message: String) : Exception(message)

fun JsonValue?.string(): String? = (this as? JsonValue.Str)?.value
fun JsonValue?.number(): Double? = (this as? JsonValue.Num)?.value
fun JsonValue?.bool(): Boolean? = (this as? JsonValue.Bool)?.value
fun JsonValue?.array(): List<JsonValue>? = (this as? JsonValue.Arr)?.items
fun JsonValue?.obj(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields
fun JsonValue?.stringOr(default: String): String = string() ?: default
fun JsonValue?.boolOr(default: Boolean): Boolean = bool() ?: default
fun JsonValue?.intOr(default: Int): Int = number()?.toInt() ?: default
fun JsonValue?.longOr(default: Long): Long = number()?.toLong() ?: default
fun JsonValue?.stringList(): List<String> = array().orEmpty().mapNotNull { it.string() }

object Json {
    fun parse(input: String): JsonValue {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected trailing data at offset ${parser.pos()}")
        return value
    }

    fun parseObject(input: String): Map<String, JsonValue> =
        parse(input).obj() ?: throw JsonException("Root value must be a JSON object")

    fun stringify(value: JsonValue): String = buildString { write(value, this) }

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf(*pairs))

    fun obj(fields: Map<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(LinkedHashMap(fields))

    fun str(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)
    fun num(value: Number): JsonValue = JsonValue.Num(value.toDouble())
    fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)
    fun arr(values: Collection<JsonValue>): JsonValue = JsonValue.Arr(values.toList())
    fun strings(values: Collection<String>): JsonValue = JsonValue.Arr(values.map { JsonValue.Str(it) })

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> {
                val d = value.value
                if (d.isNaN() || d.isInfinite()) throw JsonException("Non-finite number cannot be serialized")
                if (d == Math.floor(d) && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) {
                    out.append(d.toLong())
                } else out.append(d)
            }
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.entries.forEachIndexed { i, entry ->
                    if (i > 0) out.append(',')
                    writeString(entry.key, out)
                    out.append(':')
                    write(entry.value, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun pos(): Int = index
        fun atEnd(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (!atEnd() && source[index].isWhitespace()) index++
        }

        private fun expect(c: Char) {
            if (atEnd() || source[index] != c) throw JsonException("Expected '$c' at offset $index")
            index++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of JSON")
            return when (source[index]) {
                '{' -> parseObjectValue()
                '[' -> parseArrayValue()
                '"' -> JsonValue.Str(parseString())
                't' -> { consume("true"); JsonValue.Bool(true) }
                'f' -> { consume("false"); JsonValue.Bool(false) }
                'n' -> { consume("null"); JsonValue.Null }
                '-', in '0'..'9' -> JsonValue.Num(parseNumber())
                else -> throw JsonException("Unexpected character '${source[index]}' at offset $index")
            }
        }

        private fun consume(word: String) {
            if (!source.startsWith(word, index)) throw JsonException("Expected '$word' at offset $index")
            index += word.length
        }

        private fun parseObjectValue(): JsonValue.Obj {
            expect('{')
            val result = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && source[index] == '}') { index++; return JsonValue.Obj(result) }
            while (true) {
                skipWhitespace()
                if (atEnd() || source[index] != '"') throw JsonException("Expected object key at offset $index")
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated object")
                if (source[index] == ',') { index++; continue }
                expect('}')
                return JsonValue.Obj(result)
            }
        }

        private fun parseArrayValue(): JsonValue.Arr {
            expect('[')
            val result = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && source[index] == ']') { index++; return JsonValue.Arr(result) }
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonException("Unterminated array")
                if (source[index] == ',') { index++; continue }
                expect(']')
                return JsonValue.Arr(result)
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (!atEnd()) {
                val c = source[index++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape")
                        val e = source[index++]
                        out.append(
                            when (e) {
                                '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'
                                'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                'u' -> {
                                    if (index + 4 > source.length) throw JsonException("Truncated unicode escape")
                                    val hex = source.substring(index, index + 4)
                                    val code = hex.toIntOrNull(16) ?: throw JsonException("Invalid unicode escape '$hex'")
                                    index += 4
                                    code.toChar()
                                }
                                else -> throw JsonException("Invalid escape '\\$e'")
                            }
                        )
                    }
                    c.code < 0x20 -> throw JsonException("Unescaped control character at offset ${index - 1}")
                    else -> out.append(c)
                }
            }
            throw JsonException("Unterminated string")
        }

        private fun parseNumber(): Double {
            val start = index
            if (!atEnd() && source[index] == '-') index++
            while (!atEnd() && source[index].isDigit()) index++
            if (!atEnd() && source[index] == '.') {
                index++
                while (!atEnd() && source[index].isDigit()) index++
            }
            if (!atEnd() && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (!atEnd() && (source[index] == '+' || source[index] == '-')) index++
                while (!atEnd() && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            return text.toDoubleOrNull() ?: throw JsonException("Invalid number '$text'")
        }
    }
}
