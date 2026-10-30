package com.hqverse.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Origem de páginas de uma HQ (CBZ, CBR, pasta, etc.). Páginas são lidas sob demanda. */
interface PageSource : Closeable {
    val pageCount: Int
    fun pageName(index: Int): String

    /** Bytes da imagem da página [index] (base 0). Nunca carrega as outras páginas. */
    fun readPage(index: Int): ByteArray
}

/** Leitor de CBZ (ZIP) usando acesso aleatório e limites contra zip-bombs. */
class ZipPageSource private constructor(
    private val zip: ZipFile,
    private val entryNames: List<String>,
    private val limits: ArchiveLimits,
) : PageSource {
    override val pageCount: Int get() = entryNames.size
    override fun pageName(index: Int): String = entryNames[index]

    override fun readPage(index: Int): ByteArray {
        val name = entryNames.getOrNull(index) ?: throw ComicReadException(ReadFailure.NOT_FOUND)
        val entry = zip.getEntry(name) ?: throw ComicReadException(ReadFailure.CORRUPT)
        try {
            zip.getInputStream(entry).use { return ArchiveSafety.readBounded(it, limits.maxEntryBytes) }
        } catch (e: ComicReadException) {
            throw e
        } catch (e: ZipException) {
            val encrypted = e.message?.contains("encrypt", ignoreCase = true) == true
            throw ComicReadException(if (encrypted) ReadFailure.PASSWORD_PROTECTED else ReadFailure.CORRUPT, e)
        } catch (e: IOException) {
            throw ComicReadException(ReadFailure.CORRUPT, e)
        }
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File, limits: ArchiveLimits = ArchiveLimits()): ZipPageSource {
            if (!file.exists()) throw ComicReadException(ReadFailure.NOT_FOUND)
            val zip = try {
                ZipFile(file)
            } catch (e: IOException) {
                // JDK e Android rejeitam ZIP criptografado com mensagens diferentes; inspecionamos
                // o diretório central diretamente em vez de depender do texto da exceção.
                val failure = if (ZipInspector.hasEncryptedEntries(file)) ReadFailure.PASSWORD_PROTECTED else ReadFailure.CORRUPT
                throw ComicReadException(failure, e)
            }
            try {
                val entries = zip.entries().toList().filter { !it.isDirectory && ArchiveSafety.isSafeName(it.name) }
                val pages = PageOrdering.sortPages(entries.map { it.name })
                if (pages.isEmpty()) throw ComicReadException(ReadFailure.EMPTY)
                val byName = entries.associateBy { it.name }
                ArchiveSafety.checkDeclared(
                    pages.size,
                    pages.map { (byName.getValue(it).size.coerceAtLeast(0)) to byName.getValue(it).compressedSize },
                    limits,
                )
                val source = ZipPageSource(zip, pages, limits)
                source.readPage(0) // valida a primeira página (detecta senha e corrupção cedo)
                return source
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
        }
    }
}

/** Lê o diretório central de um ZIP para detectar entradas criptografadas (bit 0 dos flags). */
object ZipInspector {
    fun hasEncryptedEntries(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val tailSize = minOf(len, 22L + 65_535L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize); raf.readFully(tail)
            var eocd = -1
            for (i in tailSize - 22 downTo 0) {
                if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            }
            if (eocd < 0) return false
            val count = u16(tail, eocd + 10)
            var pos = u32(tail, eocd + 16)
            var encrypted = false
            val head = ByteArray(46)
            for (n in 0 until count) {
                if (pos < 0 || pos + 46 > len) break
                raf.seek(pos); raf.readFully(head)
                if (u32(head, 0) != 0x02014b50L) break
                if ((u16(head, 8) and 1) != 0) { encrypted = true; break }
                pos += 46 + u16(head, 28) + u16(head, 30) + u16(head, 32)
            }
            encrypted
        }
    } catch (_: Exception) {
        false
    }

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
}
