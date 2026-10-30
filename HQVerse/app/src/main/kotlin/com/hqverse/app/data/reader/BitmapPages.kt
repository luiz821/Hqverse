package com.hqverse.app.data.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hqverse.core.ComicReadException
import com.hqverse.core.PageSource
import com.hqverse.core.ReadFailure
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Fornece páginas já como Bitmap, no tamanho pedido. Chamadas bloqueantes (use Dispatchers.IO). */
interface BitmapPages : Closeable {
    val pageCount: Int

    /** Decodifica/renderiza a página [index] (base 0) com largura aproximada [targetWidth]. */
    fun load(index: Int, targetWidth: Int): Bitmap
}

object BitmapDecoder {
    /** Limite da maior dimensão decodificada, para caber nas texturas da GPU. */
    const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 24_000_000

    fun decode(bytes: ByteArray, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ComicReadException(ReadFailure.CORRUPT)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw ComicReadException(ReadFailure.CORRUPT)
        } catch (e: OutOfMemoryError) {
            throw ComicReadException(ReadFailure.TOO_LARGE, e)
        }
    }
}

/** Páginas vindas de um [PageSource] (CBZ, CBR, pasta): bytes de imagem decodificados sob demanda. */
class ArchiveBitmapPages(private val source: PageSource) : BitmapPages {
    override val pageCount: Int get() = source.pageCount
    override fun load(index: Int, targetWidth: Int): Bitmap = BitmapDecoder.decode(source.readPage(index), targetWidth)
    override fun close() = source.close()
}

/** Páginas de PDF renderizadas com o PdfRenderer do Android. Acesso serializado (uma página por vez). */
class PdfBitmapPages private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
) : BitmapPages {
    private val lock = Any()
    override val pageCount: Int = renderer.pageCount

    override fun load(index: Int, targetWidth: Int): Bitmap = synchronized(lock) {
        if (index !in 0 until pageCount) throw ComicReadException(ReadFailure.NOT_FOUND)
        try {
            renderer.openPage(index).use { page ->
                var w = targetWidth.coerceIn(200, BitmapDecoder.MAX_DIMENSION)
                var h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                if (h > BitmapDecoder.MAX_DIMENSION) {
                    w = (w.toLong() * BitmapDecoder.MAX_DIMENSION / h).toInt().coerceAtLeast(1)
                    h = BitmapDecoder.MAX_DIMENSION
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE) // PDFs têm fundo transparente
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: OutOfMemoryError) {
            throw ComicReadException(ReadFailure.TOO_LARGE, e)
        } catch (e: IllegalStateException) {
            throw ComicReadException(ReadFailure.CORRUPT, e)
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        /** PdfRenderer lança SecurityException para PDF protegido por senha. */
        fun open(pfd: ParcelFileDescriptor): PdfBitmapPages = try {
            val r = PdfRenderer(pfd)
            if (r.pageCount <= 0) { r.close(); pfd.close(); throw ComicReadException(ReadFailure.EMPTY) }
            PdfBitmapPages(r, pfd)
        } catch (e: SecurityException) {
            pfd.close(); throw ComicReadException(ReadFailure.PASSWORD_PROTECTED, e)
        } catch (e: IOException) {
            pfd.close(); throw ComicReadException(ReadFailure.CORRUPT, e)
        } catch (e: IllegalArgumentException) {
            pfd.close(); throw ComicReadException(ReadFailure.CORRUPT, e)
        }

        fun open(file: File): PdfBitmapPages =
            open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }
}
