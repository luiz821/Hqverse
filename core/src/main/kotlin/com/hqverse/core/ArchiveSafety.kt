package com.hqverse.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

data class ArchiveLimits(
    val maxEntries: Int = 5_000,
    /** Tamanho máximo de UMA página descompactada (bytes). */
    val maxEntryBytes: Long = 48L * 1024 * 1024,
    /** Soma máxima declarada de todas as páginas (bytes). */
    val maxTotalBytes: Long = 3L * 1024 * 1024 * 1024,
    /** Razão máxima descompactado/compactado por entrada. */
    val maxRatio: Long = 200,
)

object ArchiveSafety {
    /** Rejeita nomes que tentam escapar do diretório (zip-slip), absolutos ou com caracteres nulos. */
    fun isSafeName(rawName: String): Boolean {
        if (rawName.isEmpty() || rawName.contains('\u0000')) return false
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        return name.split('/').none { it == ".." }
    }

    /** Valida os tamanhos declarados. Lança [ComicReadException] com TOO_LARGE se excederem. */
    fun checkDeclared(count: Int, sizes: List<Pair<Long, Long>>, limits: ArchiveLimits = ArchiveLimits()) {
        if (count > limits.maxEntries) throw ComicReadException(ReadFailure.TOO_LARGE)
        var total = 0L
        for ((uncompressed, compressed) in sizes) {
            if (uncompressed > limits.maxEntryBytes) throw ComicReadException(ReadFailure.TOO_LARGE)
            if (compressed > 0 && uncompressed / compressed > limits.maxRatio && uncompressed > 1_048_576) {
                throw ComicReadException(ReadFailure.TOO_LARGE)
            }
            total += uncompressed
            if (total > limits.maxTotalBytes) throw ComicReadException(ReadFailure.TOO_LARGE)
        }
    }

    /**
     * Lê o fluxo até o fim, mas aborta se ultrapassar [maxBytes]. Necessário porque o tamanho
     * declarado no cabeçalho pode mentir.
     */
    fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ComicReadException(ReadFailure.TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
