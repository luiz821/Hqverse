package com.hqverse.core

/** Comparador de ordem natural: "p2" vem antes de "p10", sem estourar com números enormes. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si].isDigit()) si++
                var sj = j
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si
                j = sj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

/** Seleciona e ordena as páginas de um arquivo compactado ou pasta. */
object PageOrdering {
    private val pageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun normalize(path: String): String = path.replace('\\', '/').trimStart('/')

    fun isPageEntry(rawPath: String): Boolean {
        val path = normalize(rawPath)
        if (path.isEmpty() || path.endsWith("/")) return false
        val segments = path.split('/')
        if (segments.any { it == "__MACOSX" || it.startsWith(".") }) return false
        val name = segments.last()
        if (name.equals("Thumbs.db", ignoreCase = true)) return false
        return FormatDetector.extensionOf(name) in pageExtensions
    }

    /** Retorna os caminhos das páginas na ordem de leitura, descartando lixo. */
    fun sortPages(paths: List<String>): List<String> =
        paths.filter(::isPageEntry).sortedWith { a, b -> NaturalOrder.compare(normalize(a), normalize(b)) }
}
