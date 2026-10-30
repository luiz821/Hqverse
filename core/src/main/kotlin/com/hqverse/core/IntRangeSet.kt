package com.hqverse.core

/**
 * Conjunto compacto de inteiros positivos, codificado como "1-12,15,20-22".
 * Usado para páginas já lidas (sem repetir contagem) e para sequências esperadas de edições.
 */
class IntRangeSet private constructor(private val ranges: java.util.TreeMap<Int, Int>) {
    constructor() : this(java.util.TreeMap())

    val count: Int get() = ranges.entries.sumOf { it.value - it.key + 1 }
    val isEmpty: Boolean get() = ranges.isEmpty()

    operator fun contains(n: Int): Boolean {
        val e = ranges.floorEntry(n) ?: return false
        return n <= e.value
    }

    /** Adiciona [n]. Retorna true se ele ainda não estava no conjunto. */
    fun add(n: Int): Boolean {
        if (n < 0 || n in this) return false
        var start = n
        var end = n
        ranges.floorEntry(n - 1)?.let { if (it.value >= n - 1) { start = it.key; ranges.remove(it.key) } }
        ranges.ceilingEntry(n + 1)?.let { if (it.key == n + 1) { end = it.value; ranges.remove(it.key) } }
        ranges[start] = end
        return true
    }

    fun addRange(from: Int, to: Int) { for (n in from..to) add(n) }

    fun toList(): List<Int> = ranges.flatMap { (s, e) -> (s..e).toList() }

    fun missingFrom(expected: IntRangeSet): List<Int> = expected.toList().filter { it !in this }

    fun encode(): String = ranges.entries.joinToString(",") { (s, e) -> if (s == e) "$s" else "$s-$e" }

    companion object {
        const val MAX_VALUE = 100_000

        /** Interpreta "1-12,15". Retorna null se o texto for inválido. */
        fun decode(text: String?): IntRangeSet? {
            val set = IntRangeSet()
            if (text.isNullOrBlank()) return set
            for (part in text.split(',')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val bounds = p.split('-').map { it.trim() }
                val a = bounds.getOrNull(0)?.toIntOrNull() ?: return null
                val b = if (bounds.size == 1) a else bounds.getOrNull(1)?.toIntOrNull() ?: return null
                if (bounds.size > 2 || a < 0 || b < a || b > MAX_VALUE) return null
                set.addRange(a, b)
            }
            return set
        }
    }
}
