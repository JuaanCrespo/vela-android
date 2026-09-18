package com.vela.android.lab.data.paper.reconciliation.evidence

/** Small bounded RFC-8259 reader. Numbers remain lexemes; no floating-point coercion. */
internal object StrictEvidenceJson {
    sealed interface Value
    data class Obj(val fields: Map<String, Value>) : Value
    data class Arr(val values: List<Value>) : Value
    data class Str(val text: String) : Value
    data class Num(val text: String) : Value
    data class Bool(val value: Boolean) : Value
    data object Null : Value

    fun parse(body: String): Value = Reader(body).read()

    private class Reader(private val text: String) {
        private var at = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid evidence JSON")
        private fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
        fun read(): Value {
            if (text.length > 2_097_152) invalid()
            val value = value(0)
            space()
            if (at != text.length) invalid()
            return value
        }
        private fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
        private fun value(depth: Int): Value {
            space()
            if (depth > 64 || at >= text.length) invalid()
            return when (text[at]) {
                '{' -> {
                    at++
                    val fields = linkedMapOf<String, Value>()
                    if (!take('}')) {
                        do {
                            space()
                            if (at >= text.length || text[at] != '"') invalid()
                            val key = string()
                            if (fields.containsKey(key) || !take(':')) invalid()
                            fields[key] = value(depth + 1)
                        } while (take(','))
                        if (!take('}')) invalid()
                    }
                    Obj(fields)
                }
                '[' -> {
                    at++
                    val values = arrayListOf<Value>()
                    if (!take(']')) {
                        do { values += value(depth + 1); if (values.size > 20_000) invalid() } while (take(','))
                        if (!take(']')) invalid()
                    }
                    Arr(values)
                }
                '"' -> Str(string())
                't' -> { literal("true"); Bool(true) }
                'f' -> { literal("false"); Bool(false) }
                'n' -> { literal("null"); Null }
                '-', in '0'..'9' -> {
                    val start = at
                    while (at < text.length && text[at] in "-+0123456789.eE") at++
                    val number = text.substring(start, at)
                    if (!number.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) invalid()
                    Num(number)
                }
                else -> invalid()
            }
        }
        private fun literal(word: String) { if (!text.startsWith(word, at)) invalid(); at += word.length }
        private fun string(): String {
            at++
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return out.toString()
                if (c.code < 32) invalid()
                if (c != '\\') out.append(c) else {
                    if (at >= text.length) invalid()
                    when (val escaped = text[at++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) invalid()
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: invalid()
                            out.append(code.toChar()); at += 4
                        }
                        else -> invalid()
                    }
                }
            }
            invalid()
        }
    }
}
