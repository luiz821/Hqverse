package com.hqverse.app.data.reader

import android.content.ContentResolver
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.hqverse.app.data.files.Saf
import com.hqverse.core.ArchiveLimits
import com.hqverse.core.ArchiveSafety
import com.hqverse.core.ComicReadException
import com.hqverse.core.FormatDetector
import com.hqverse.core.PageOrdering
import com.hqverse.core.PageSource
import com.hqverse.core.ReadFailure
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** CBR (RAR). Usa junrar (pura Java). Entradas são extraídas uma a uma, com limites. */
class RarPageSource private constructor(
    private val archive: Archive,
    private val headersByName: Map<String, FileHeader>,
    private val names: List<String>,
    private val limits: ArchiveLimits,
) : PageSource {
    override val pageCount: Int get() = names.size
    override fun pageName(index: Int): String = names[index]

    @Synchronized
    override fun readPage(index: Int): ByteArray {
        val name = names.getOrNull(index) ?: throw ComicReadException(ReadFailure.NOT_FOUND)
        val header = headersByName.getValue(name)
        val sink = BoundedSink(limits.maxEntryBytes)
        try {
            archive.extractFile(header, sink)
        } catch (e: ComicReadException) {
            throw e
        } catch (e: Exception) {
            throw mapRarFailure(e)
        }
        return sink.toByteArray()
    }

    override fun close() { runCatching { archive.close() } }

    private class BoundedSink(private val max: Long) : OutputStream() {
        private val out = java.io.ByteArrayOutputStream()
        override fun write(b: Int) { check(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { check(len); out.write(b, off, len) }
        private fun check(add: Int) { if (out.size() + add.toLong() > max) throw ComicReadException(ReadFailure.TOO_LARGE) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    companion object {
        fun open(file: File, header: ByteArray, limits: ArchiveLimits = ArchiveLimits()): RarPageSource {
            if (!file.exists()) throw ComicReadException(ReadFailure.NOT_FOUND)
            val archive = try {
                Archive(file)
            } catch (e: Exception) {
                throw mapRarFailure(e, header)
            }
            try {
                val headers = archive.fileHeaders.filter { !it.isDirectory && ArchiveSafety.isSafeName(it.fileName) }
                if (headers.any { it.isEncrypted }) throw ComicReadException(ReadFailure.PASSWORD_PROTECTED)
                val byName = headers.associateBy { it.fileName }
                val pages = PageOrdering.sortPages(headers.map { it.fileName })
                if (pages.isEmpty()) throw ComicReadException(ReadFailure.EMPTY)
                ArchiveSafety.checkDeclared(pages.size, pages.map { byName.getValue(it).fullUnpackSize to 0L }, limits)
                return RarPageSource(archive, byName, pages, limits)
            } catch (e: Throwable) {
                runCatching { archive.close() }
                throw if (e is ComicReadException) e else mapRarFailure(e as? Exception ?: Exception(e), header)
            }
        }

        /**
         * Mapeia exceções do junrar pelo NOME da classe (evita depender de classes específicas da
         * biblioteca). Um RAR5 que a biblioteca não consegue ler recebe mensagem própria.
         */
        fun mapRarFailure(e: Exception, header: ByteArray? = null): ComicReadException {
            val n = e.javaClass.simpleName
            val failure = when {
                n.contains("V5") || (header != null && FormatDetector.isRar5(header)) -> ReadFailure.RAR5_UNSUPPORTED
                n.contains("Encrypted") || n.contains("Password") -> ReadFailure.PASSWORD_PROTECTED
                n.contains("NotRar") -> ReadFailure.UNSUPPORTED
                e is IOException && (e.message?.contains("ENOSPC") == true) -> ReadFailure.OUT_OF_SPACE
                else -> ReadFailure.CORRUPT
            }
            return ComicReadException(failure, e)
        }
    }
}

/** Pasta de imagens autorizada via SAF. Só lista nomes; os bytes são lidos página a página. */
class FolderPageSource private constructor(
    private val resolver: ContentResolver,
    private val uris: List<Uri>,
    private val names: List<String>,
    private val limits: ArchiveLimits,
) : PageSource {
    override val pageCount: Int get() = uris.size
    override fun pageName(index: Int): String = names[index]

    override fun readPage(index: Int): ByteArray {
        val uri = uris.getOrNull(index) ?: throw ComicReadException(ReadFailure.NOT_FOUND)
        return try {
            Saf.open(resolver, uri).use { ArchiveSafety.readBounded(it, limits.maxEntryBytes) }
        } catch (e: IOException) {
            throw ComicReadException(ReadFailure.CORRUPT, e)
        }
    }

    override fun close() = Unit

    companion object {
        fun open(resolver: ContentResolver, folderUri: Uri, limits: ArchiveLimits = ArchiveLimits()): FolderPageSource {
            val images = Saf.listChildren(resolver, folderUri).filter { !it.isDirectory && PageOrdering.isPageEntry(it.name) }
            if (images.isEmpty()) throw ComicReadException(ReadFailure.EMPTY)
            if (images.size > limits.maxEntries) throw ComicReadException(ReadFailure.TOO_LARGE)
            val ordered = images.sortedWith { a, b -> com.hqverse.core.NaturalOrder.compare(a.name, b.name) }
            return FolderPageSource(resolver, ordered.map { it.uri }, ordered.map { it.name }, limits)
        }
    }
}
