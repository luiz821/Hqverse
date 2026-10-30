package com.hqverse.core

class JsonException(message: String) : Exception(message)

/**
 * JSON mínimo (sem dependências) para o backup. Tipos: Map, List, String, Long, Double,
 * Boolean e null. Com limites de profundidade e tamanho contra arquivos maliciosos.
 */
object MiniJson {
    const val MAX_DEPTH = 32
    const val MAX_CHARS = 64 * 1024 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_CHARS) throw JsonException("Arquivo grande demais")
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Conteúdo extra após o JSON")
        return v
    }

    fun stringify(value: Any?, pretty: Boolean = false): String =
        StringBuilder().also { write(it, value, pretty, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, level: Int) {
        fun nl(l: Int) { if (pretty) { sb.append('\n'); repeat(l) { sb.append("  ") } } }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(level + 1)
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, level + 1)
                }
                if (!first) nl(level)
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(level + 1)
                    write(sb, item, pretty, level + 1)
                }
                if (!first) nl(level)
                sb.append(']')
            }
            else -> throw JsonException("Tipo não suportado: ${v::class.simpleName}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(msg: String): Nothing = throw JsonException("$msg (posição $i)")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Aninhamento profundo demais")
            if (atEnd()) fail("Fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Caractere inesperado '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Valor inválido")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("Chave esperada")
                val k = str()
                skipWs()
                if (atEnd() || s[i] != ':') fail("':' esperado")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("Objeto não terminado")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                fail("',' ou '}' esperado")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l += value(depth + 1)
                skipWs()
                if (atEnd()) fail("Lista não terminada")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                fail("',' ou ']' esperado")
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (atEnd()) fail("Texto não terminado")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Escape incompleto")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("Escape \\u incompleto")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("Escape \\u inválido"))
                                i += 4
                            }
                            else -> fail("Escape inválido '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Caractere de controle em texto")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Número inválido")
        }
    }
}
