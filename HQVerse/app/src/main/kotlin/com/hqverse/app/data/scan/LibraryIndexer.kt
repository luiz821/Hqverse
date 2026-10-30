package com.hqverse.app.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import com.hqverse.app.data.db.AppDatabase
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.data.files.CacheManager
import com.hqverse.app.data.files.DocEntry
import com.hqverse.app.data.files.Saf
import com.hqverse.app.data.reader.ComicOpener
import com.hqverse.app.data.settings.SettingsRepository
import com.hqverse.core.ComicFormat
import com.hqverse.core.ComicReadException
import com.hqverse.core.FileNameParser
import com.hqverse.core.FolderComicRule
import com.hqverse.core.FormatDetector
import com.hqverse.core.Fingerprint
import com.hqverse.core.IndexedFile
import com.hqverse.core.LibraryDiffer
import com.hqverse.core.PageOrdering
import com.hqverse.core.ReadFailure
import com.hqverse.core.ScannedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Indexação incremental: lista a pasta, compara com o banco ([LibraryDiffer]) e só processa
 * (páginas + capa) o que é novo ou mudou. Arquivos movidos/renomeados mantêm o histórico;
 * arquivos que sumiram ficam marcados como "ausentes" (nunca apagados automaticamente).
 */
class LibraryIndexer(
    private val context: Context,
    private val db: AppDatabase,
    private val cache: CacheManager,
    private val settings: SettingsRepository,
    private val opener: ComicOpener,
    val coordinator: ScanCoordinator,
) {
    private val mutex = Mutex()

    private class Listing(val files: List<ScannedFile>, val formats: Map<String, ComicFormat>, val complete: Boolean)

    suspend fun scan(folderIds: List<Long>? = null) = mutex.withLock {
        val all = db.folders().getAll().filter { it.enabled }
        val folders = if (folderIds.isNullOrEmpty()) all else all.filter { it.id in folderIds }
        coordinator.begin(folders.size)
        var added = 0; var moved = 0; var missing = 0; var failed = 0
        val problems = mutableListOf<String>()
        try {
            val s = settings.current()
            val ignored = s.ignoredFolderNames.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            for ((i, folder) in folders.withIndex()) {
                coroutineContext.ensureActive()
                coordinator.update { it.copy(phase = "Procurando em ${folder.name}…", foldersDone = i) }
                val listing = try {
                    withContext(Dispatchers.IO) { list(Uri.parse(folder.uri), folder.includeSubfolders, ignored) }
                } catch (e: ComicReadException) {
                    problems += "${folder.name}: ${e.failure.userMessage}"
                    continue // não marca nada como ausente se a listagem falhou
                }
                val result = apply(folder.id, listing)
                added += result[0]; moved += result[1]; missing += result[2]
                db.folders().update(folder.copy(lastScanAt = System.currentTimeMillis()))
                if (!listing.complete) problems += "${folder.name}: algumas subpastas não puderam ser lidas; ausências não foram atualizadas."
            }
            failed = processPending()
        } catch (e: CancellationException) {
            coordinator.finish("Varredura cancelada. O que já foi indexado foi mantido.")
            throw e
        }
        cache.trimTo(settings.current().cacheLimitMb * 1024L * 1024L)
        val parts = buildList {
            add("$added nova(s)")
            if (moved > 0) add("$moved movida(s)")
            if (missing > 0) add("$missing ausente(s)")
            if (failed > 0) add("$failed com erro")
        }
        coordinator.finish((listOf(parts.joinToString(", ")) + problems).joinToString("\n"))
    }

    // ---------- listagem ----------

    private suspend fun list(root: Uri, includeSubfolders: Boolean, ignored: Set<String>): Listing {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(root)
        val files = ArrayList<ScannedFile>()
        val formats = HashMap<String, ComicFormat>()
        val stack = ArrayDeque<Pair<String, String>>() // docId, nome
        stack.addLast(treeDocId to "")
        var complete = true
        var first = true
        while (stack.isNotEmpty()) {
            val (docId, dirName) = stack.removeLast()
            val children = try {
                Saf.listChildren(resolver, root, docId)
            } catch (e: ComicReadException) {
                if (first) throw e
                complete = false; continue
            }
            first = false
            var imageCount = 0; var imageBytes = 0L; var imageModified = 0L
            for (c in children) {
                if (c.isDirectory) {
                    if (includeSubfolders && c.name.lowercase() !in ignored && !c.name.startsWith(".")) stack.addLast(c.docId to c.name)
                    continue
                }
                val format = FormatDetector.formatFromName(c.name)
                if (format != null) {
                    files += ScannedFile(c.uri.toString(), c.name, c.size, c.lastModified)
                    formats[c.uri.toString()] = format
                } else if (PageOrdering.isPageEntry(c.name)) {
                    imageCount++; imageBytes += c.size; imageModified = maxOf(imageModified, c.lastModified)
                }
            }
            if (FolderComicRule.isComicFolder(imageCount)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(root, docId).toString()
                val name = dirName.ifEmpty { "Pasta" }
                files += ScannedFile(uri, name, imageBytes, imageModified)
                formats[uri] = ComicFormat.FOLDER
            }
            coroutineContext.ensureActive()
            coordinator.awaitIfPaused()
            val found = files.size
            coordinator.update { it.copy(filesFound = found) }
        }
        return Listing(files, formats, complete)
    }

    // ---------- reconciliação com o banco ----------

    /** Retorna [novas, movidas, ausentes]. */
    private suspend fun apply(folderId: Long, listing: Listing): IntArray {
        val now = System.currentTimeMillis()
        val existing = db.comics().getByFolder(folderId)
        val byId = existing.associateBy { it.id }
        val diff = LibraryDiffer.diff(
            listing.files,
            existing.map { IndexedFile(it.id, it.uri, it.fileName, it.sizeBytes, it.lastModified) },
        )
        // Registros órfãos (pasta removida com "preservar dados") são readotados por nome+tamanho únicos.
        val orphans = db.comics().getOrphans().toMutableList()
        var adopted = 0
        for (f in diff.added) {
            val format = listing.formats[f.uri] ?: continue
            val fp = Fingerprint.of(f.name, f.size)
            val match = orphans.filter { it.fingerprint == fp }.singleOrNull()
            if (match != null) {
                orphans.remove(match); adopted++
                db.comics().update(match.copy(folderId = folderId, uri = f.uri, fileName = f.name, sizeBytes = f.size, lastModified = f.lastModified, hidden = false, missing = false))
            } else db.comics().insert(newComic(folderId, f, format, now))
        }
        for ((old, f) in diff.changed) {
            val c = byId.getValue(old.id)
            db.comics().update(
                c.copy(
                    sizeBytes = f.size, lastModified = f.lastModified, fingerprint = Fingerprint.of(f.name, f.size),
                    coverPath = null, pageCount = 0, errorCode = null, missing = false,
                ),
            )
            cache.deleteCoversOf(c.id)
        }
        for ((old, f) in diff.moved) {
            val c = byId.getValue(old.id)
            db.comics().update(
                c.copy(
                    uri = f.uri, fileName = f.name, sizeBytes = f.size, lastModified = f.lastModified,
                    fingerprint = Fingerprint.of(f.name, f.size), missing = false,
                ),
            )
        }
        for (u in diff.unchanged) if (byId.getValue(u.id).missing) db.comics().setMissing(u.id, false)
        var missingCount = 0
        if (listing.complete) {
            for (m in diff.missing) {
                if (!byId.getValue(m.id).missing) { db.comics().setMissing(m.id, true); missingCount++ }
            }
        }
        return intArrayOf(diff.added.size - adopted, diff.moved.size + adopted, missingCount)
    }

    private fun newComic(folderId: Long, f: ScannedFile, format: ComicFormat, now: Long): ComicEntity {
        val parsed = FileNameParser.parse(if (format == ComicFormat.FOLDER) "${f.name}.folder" else f.name)
        return ComicEntity(
            folderId = folderId, uri = f.uri, fileName = f.name, sizeBytes = f.size, lastModified = f.lastModified,
            format = format.name, fingerprint = Fingerprint.of(f.name, f.size),
            title = parsed.title, series = parsed.series, issue = parsed.issue, volume = parsed.volume,
            year = parsed.year, addedAt = now,
        )
    }

    // ---------- páginas + capas ----------

    /** Abre cada HQ nova/alterada, conta as páginas e gera a capa. Retorna quantas falharam. */
    private suspend fun processPending(): Int {
        val pending = db.comics().getNeedingProcessing()
        coordinator.update { it.copy(phase = "Gerando capas…", processed = 0, toProcess = pending.size) }
        var failed = 0
        for ((i, comic) in pending.withIndex()) {
            coroutineContext.ensureActive()
            coordinator.awaitIfPaused()
            coordinator.update { it.copy(processed = i, currentName = comic.title) }
            if (!processOne(comic)) failed++
        }
        coordinator.update { it.copy(processed = pending.size, currentName = null) }
        return failed
    }

    /** Também usado para trocar a capa (página escolhida pelo usuário). */
    suspend fun processOne(comic: ComicEntity, coverPage: Int? = comic.customCoverPage): Boolean = withContext(Dispatchers.IO) {
        try {
            val pages = opener.open(comic)
            try {
                val index = (coverPage ?: 0).coerceIn(0, pages.pageCount - 1)
                val bmp = pages.load(index, COVER_WIDTH)
                val out = cache.coverFile(comic.id, comic.lastModified, coverPage)
                cache.deleteCoversOf(comic.id)
                writeJpeg(bmp, out)
                bmp.recycle()
                db.comics().setPageCount(comic.id, pages.pageCount)
                db.comics().setCover(comic.id, out.absolutePath, coverPage)
                db.comics().setError(comic.id, null)
            } finally {
                pages.close()
                if (comic.format != ComicFormat.PDF.name) cache.discardArchive(comic.id, comic.lastModified, comic.sizeBytes)
            }
            true
        } catch (e: ComicReadException) {
            db.comics().setError(comic.id, e.failure.name)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.comics().setError(comic.id, ReadFailure.CORRUPT.name)
            false
        }
    }

    private fun writeJpeg(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    companion object { const val COVER_WIDTH = 420 }
}
