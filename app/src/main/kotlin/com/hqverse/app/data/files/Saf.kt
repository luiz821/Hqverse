package com.hqverse.app.data.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hqverse.core.ComicReadException
import com.hqverse.core.ReadFailure
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

data class DocEntry(
    val docId: String,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/** Acesso a arquivos via Storage Access Framework: sem permissão de armazenamento irrestrito. */
object Saf {
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /** URI da árvore (raiz autorizada) a partir de qualquer URI de documento dentro dela. */
    fun treeUriOf(documentUri: Uri): Uri =
        DocumentsContract.buildTreeDocumentUri(documentUri.authority, DocumentsContract.getTreeDocumentId(documentUri))

    fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<DocEntry>()
        try {
            resolver.query(childrenUri, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    out += DocEntry(
                        docId = docId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = c.getString(1) ?: docId.substringAfterLast('/'),
                        mime = c.getString(2) ?: "",
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                }
            }
        } catch (e: SecurityException) {
            throw ComicReadException(ReadFailure.NO_PERMISSION, e)
        } catch (e: IllegalArgumentException) {
            throw ComicReadException(ReadFailure.NOT_FOUND, e)
        }
        return out
    }

    /** Lista os filhos de um documento (pasta) a partir do seu próprio URI. */
    fun listChildren(resolver: ContentResolver, folderDocumentUri: Uri): List<DocEntry> =
        listChildren(resolver, treeUriOf(folderDocumentUri), DocumentsContract.getDocumentId(folderDocumentUri))

    fun open(resolver: ContentResolver, uri: Uri): InputStream = try {
        resolver.openInputStream(uri) ?: throw ComicReadException(ReadFailure.NOT_FOUND)
    } catch (e: FileNotFoundException) {
        throw ComicReadException(ReadFailure.NOT_FOUND, e)
    } catch (e: SecurityException) {
        throw ComicReadException(ReadFailure.NO_PERMISSION, e)
    }

    fun readHeader(resolver: ContentResolver, uri: Uri, size: Int = 16): ByteArray =
        open(resolver, uri).use { input ->
            val buf = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = input.read(buf, n, size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }

    /** Copia o documento para [dest], verificando antes se há espaço livre. */
    fun copyTo(resolver: ContentResolver, uri: Uri, dest: File, expectedSize: Long) {
        val dir = dest.parentFile ?: throw ComicReadException(ReadFailure.OUT_OF_SPACE)
        dir.mkdirs()
        if (expectedSize > 0 && dir.usableSpace < expectedSize + 32L * 1024 * 1024) {
            throw ComicReadException(ReadFailure.OUT_OF_SPACE)
        }
        val tmp = File(dir, dest.name + ".part")
        try {
            open(resolver, uri).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!tmp.renameTo(dest)) throw IOException("não foi possível finalizar a cópia")
        } catch (e: ComicReadException) {
            tmp.delete(); throw e
        } catch (e: IOException) {
            tmp.delete()
            val noSpace = e.message?.contains("ENOSPC", true) == true || e.message?.contains("No space", true) == true
            throw ComicReadException(if (noSpace) ReadFailure.OUT_OF_SPACE else ReadFailure.CORRUPT, e)
        }
    }

    /** Apaga o arquivo físico. Só deve ser chamado após confirmação explícita do usuário. */
    fun delete(context: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: Exception) {
        false
    }

    /** Mantém o acesso à pasta após reiniciar. Tenta leitura+escrita (necessária para excluir arquivos) e cai para só leitura. */
    fun persist(context: Context, treeUri: Uri): Boolean {
        val resolver = context.contentResolver
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(treeUri, read or write); true
        } catch (_: SecurityException) {
            try { resolver.takePersistableUriPermission(treeUri, read); true } catch (_: SecurityException) { false }
        }
    }
}
