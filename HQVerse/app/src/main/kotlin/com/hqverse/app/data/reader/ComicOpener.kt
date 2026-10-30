package com.hqverse.app.data.reader

import android.content.Context
import android.net.Uri
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.data.files.CacheManager
import com.hqverse.app.data.files.Saf
import com.hqverse.core.ComicFormat
import com.hqverse.core.ComicReadException
import com.hqverse.core.FormatDetector
import com.hqverse.core.PageSource
import com.hqverse.core.ReadFailure
import com.hqverse.core.ZipPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Abre uma HQ no formato correto. O CONTEÚDO real (bytes iniciais) decide o leitor, não a
 * extensão: um ".cbr" que na verdade é ZIP abre como CBZ.
 */
class ComicOpener(
    private val context: Context,
    private val cache: CacheManager,
    private val cacheLimitBytes: suspend () -> Long,
) {
    suspend fun open(comic: ComicEntity): BitmapPages = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = Uri.parse(comic.uri)
        val declared = runCatching { ComicFormat.valueOf(comic.format) }.getOrNull()
        if (declared == ComicFormat.FOLDER) {
            return@withContext ArchiveBitmapPages(FolderPageSource.open(resolver, uri))
        }
        val header = Saf.readHeader(resolver, uri, 16)
        val kind = FormatDetector.detect(header)
        val format = FormatDetector.resolveReadFormat(declared, kind)
            ?: throw ComicReadException(ReadFailure.UNSUPPORTED)
        when (format) {
            ComicFormat.PDF -> openPdf(comic, uri)
            ComicFormat.CBZ -> {
                val file = ensureCopy(comic, uri)
                ArchiveBitmapPages(Releasing(runOrRelease(file) { ZipPageSource.open(file) }, file))
            }
            ComicFormat.CBR -> {
                val file = ensureCopy(comic, uri)
                ArchiveBitmapPages(Releasing(runOrRelease(file) { RarPageSource.open(file, header) }, file))
            }
            ComicFormat.FOLDER -> throw ComicReadException(ReadFailure.UNSUPPORTED)
        }
    }

    private suspend fun openPdf(comic: ComicEntity, uri: Uri): BitmapPages {
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: java.io.FileNotFoundException) {
            throw ComicReadException(ReadFailure.NOT_FOUND, e)
        } catch (e: SecurityException) {
            throw ComicReadException(ReadFailure.NO_PERMISSION, e)
        }
        if (pfd != null) {
            try {
                return PdfBitmapPages.open(pfd)
            } catch (e: ComicReadException) {
                if (e.failure != ReadFailure.CORRUPT) throw e
                // Alguns provedores não oferecem descritor "seekable": tenta de novo com uma cópia local.
            }
        }
        val file = ensureCopy(comic, uri)
        return runOrRelease(file) { PdfBitmapPages.open(file) }
    }

    private suspend fun ensureCopy(comic: ComicEntity, uri: Uri): File {
        val file = cache.archiveFile(comic.id, comic.lastModified, comic.sizeBytes)
        cache.acquire(file)
        if (!file.exists() || file.length() == 0L) {
            try {
                cache.trimTo((cacheLimitBytes() - comic.sizeBytes).coerceAtLeast(0))
                Saf.copyTo(context.contentResolver, uri, file, comic.sizeBytes)
            } catch (e: Throwable) {
                cache.release(file); throw e
            }
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        return file
    }

    private inline fun <T> runOrRelease(file: File, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        cache.release(file)
        if (e is ComicReadException && e.failure == ReadFailure.CORRUPT) file.delete() // cópia ruim: não reaproveitar
        throw e
    }

    private inner class Releasing(private val delegate: PageSource, private val file: File) : PageSource by delegate {
        override fun close() { delegate.close(); cache.release(file) }
    }
}
