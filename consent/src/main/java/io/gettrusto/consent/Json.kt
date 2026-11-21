package io.gettrusto.consent

/**
 * JSON mínimo, sem dependência: objetos viram `Map<String, Any?>` (ordem preservada), listas
 * `List<Any?>`, números `Long` ou `Double`, e o resto como em JSON. O `org.json` da plataforma
 * não roda nos testes da JVM; este codec roda nos dois lugares, com o mesmo comportamento.
 */
internal object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipSpace()
        if (!parser.atEnd()) throw JsonException("conteúdo depois do valor")
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is Double -> {
                if (value.isNaN() || value.isInfinite()) throw JsonException("número inválido")
                if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) out.append(value.toLong()) else out.append(value)
            }
            is Number -> write(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, key as String)
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(out, item)
                }
                out.append(']')
            }
            else -> throw JsonException("tipo sem representação em JSON: ${value::class.java.simpleName}")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    out.append("\\u").append(String.format("%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var depth = 0

        fun atEnd() = pos >= text.length

        fun skipSpace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        fun value(): Any? {
            skipSpace()
            if (atEnd()) throw JsonException("fim inesperado")
            return when (val c = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw JsonException("caractere inesperado na posição $pos")
            }
        }

        private fun nest() {
            if (++depth > 64) throw JsonException("aninhamento demais")
        }

        private fun obj(): Map<String, Any?> {
            nest()
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                pos++
                depth--
                return map
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw JsonException("chave esperada na posição $pos")
                val key = string()
                skipSpace()
                expect(':')
                map[key] = value()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> {
                        depth--
                        return map
                    }
                    else -> throw JsonException("',' ou '}' esperado na posição ${pos - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            nest()
            pos++
            val list = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                pos++
                depth--
                return list
            }
            while (true) {
                list.add(value())
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> {
                        depth--
                        return list
                    }
                    else -> throw JsonException("',' ou ']' esperado na posição ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("texto sem fim")
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("escape sem fim")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonException("escape \\u incompleto")
                                out.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("escape \\u inválido"))
                                pos += 4
                            }
                            else -> throw JsonException("escape inválido")
                        }
                    }
                    else -> if (c < ' ') throw JsonException("controle dentro do texto") else out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw JsonException("número inválido: $raw")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw JsonException("literal inválido na posição $pos")
            pos += word.length
            return value
        }

        private fun peek(): Char? = if (atEnd()) null else text[pos]

        private fun next(): Char? = if (atEnd()) null else text[pos++]

        private fun expect(c: Char) {
            if (next() != c) throw JsonException("'$c' esperado na posição ${pos - 1}")
        }
    }
}

internal class JsonException(message: String) : Exception(message)

/** Leitura tipada de um objeto JSON, com o erro dizendo qual campo faltou. */
internal class JsonObject(private val map: Map<String, Any?>) {
    fun has(key: String) = map.containsKey(key) && map[key] != null
    fun string(key: String): String = map[key] as? String ?: throw JsonException("campo '$key' ausente ou não texto")
    fun stringOrNull(key: String): String? = map[key] as? String
    fun long(key: String): Long = (map[key] as? Number)?.toLong() ?: throw JsonException("campo '$key' ausente ou não número")
    fun longOrNull(key: String): Long? = (map[key] as? Number)?.toLong()
    fun int(key: String): Int = long(key).toInt()
    fun bool(key: String): Boolean = map[key] as? Boolean ?: throw JsonException("campo '$key' ausente ou não booleano")
    fun obj(key: String): JsonObject = JsonObject(map[key] as? Map<String, Any?> ?: throw JsonException("campo '$key' ausente ou não objeto"))
    fun list(key: String): List<Any?> = map[key] as? List<Any?> ?: throw JsonException("campo '$key' ausente ou não lista")
    fun objects(key: String): List<JsonObject> = list(key).map { JsonObject(it as? Map<String, Any?> ?: throw JsonException("item de '$key' não é objeto")) }
    fun raw(): Map<String, Any?> = map

    companion object {
        fun parse(text: String): JsonObject =
            JsonObject(Json.parse(text) as? Map<String, Any?> ?: throw JsonException("objeto esperado"))
    }
}
