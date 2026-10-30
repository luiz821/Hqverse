package com.hqverse.app.data.repo

import android.content.Context
import android.net.Uri
import com.hqverse.app.data.db.*
import com.hqverse.app.data.settings.SettingsRepository
import com.hqverse.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RestorePlan(
    val data: BackupData,
    /** Chaves do backup que já têm HQ correspondente na biblioteca. */
    val matched: Map<String, Long>,
    val unmatched: List<BackupComic>,
    /** Dos casados, quantos já possuem progresso local (seriam sobrescritos). */
    val conflicts: Int,
)

class BackupRepository(private val context: Context, private val db: AppDatabase, private val settings: SettingsRepository) {

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val comics = db.comics().getAll()
        val tags = db.tags().getAll().groupBy({ it.comicId }, { it.tag })
        val sessions = db.sessions().getAllClosed().groupBy { it.comicId }
        val bookmarks = db.bookmarks().getAll().groupBy { it.comicId }
        val keyById = comics.associate { it.id to Fingerprint.of(it.fileName, it.sizeBytes) }
        val data = BackupData(
            version = BackupData.CURRENT_VERSION, createdAt = System.currentTimeMillis(),
            comics = comics.map { c ->
                BackupComic(
                    key = keyById.getValue(c.id), fileName = c.fileName, sizeBytes = c.sizeBytes, title = c.title, series = c.series,
                    issue = c.issue, volume = c.volume, year = c.year, author = c.author, publisher = c.publisher,
                    favorite = c.favorite, wantToRead = c.wantToRead, rating = c.rating, currentPage = c.currentPage,
                    totalPages = c.pageCount, readPages = c.readPages, status = c.status, startedAt = c.startedAt,
                    lastReadAt = c.lastReadAt, completedAt = c.completedAt, activeSeconds = c.activeSeconds, sessionCount = c.sessionCount,
                    notes = c.notes, tags = tags[c.id].orEmpty(),
                    sessions = sessions[c.id].orEmpty().map { BackupSession(it.startMs, it.endMs, it.activeSeconds, it.newPages, it.pageVisits) },
                    bookmarks = bookmarks[c.id].orEmpty().map { BackupBookmark(it.page, it.name, it.createdAt, it.color, it.note) },
                )
            }.distinctBy { it.key },
            collections = run {
                val items = db.collections().getAllItems().groupBy { it.collectionId }
                db.collections().getAll().map { col -> BackupCollection(col.name, col.expectedIssues, items[col.id].orEmpty().sortedBy { it.position }.mapNotNull { keyById[it.comicId] }) }
            },
            settings = settings.exportAll(),
        )
        val text = BackupCodec.encode(data)
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("Não foi possível gravar o arquivo")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        data.comics.size
    }

    /** Lê e valida o arquivo. Não altera nada no banco. */
    suspend fun plan(uri: Uri): RestorePlan = withContext(Dispatchers.IO) {
        val bytes = (context.contentResolver.openInputStream(uri) ?: throw BackupFormatException("Não foi possível abrir o arquivo")).use {
            com.hqverse.core.ArchiveSafety.readBounded(it, 64L * 1024 * 1024)
        }
        val data = BackupCodec.decode(String(bytes, Charsets.UTF_8))
        val local = db.comics().getAll()
        val matched = BackupMatcher.match(data.comics, local.map { BackupMatcher.LocalFile(it.id, it.fileName, it.sizeBytes) })
        val byId = local.associateBy { it.id }
        RestorePlan(data, matched, data.comics.filter { it.key !in matched }, matched.values.count { (byId[it]?.readPages ?: "").isNotEmpty() || (byId[it]?.sessionCount ?: 0) > 0 })
    }

    /**
     * Aplica o backup às HQs casadas. [overwrite] = false mantém o progresso local existente e só
     * preenche o que está vazio. HQs sem arquivo local não são criadas (o usuário seleciona a pasta
     * e restaura de novo). Retorna a quantidade restaurada.
     */
    suspend fun apply(plan: RestorePlan, overwrite: Boolean, restoreSettings: Boolean): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (b in plan.data.comics) {
            val id = plan.matched[b.key] ?: continue
            val c = db.comics().get(id) ?: continue
            val hasLocal = c.readPages.isNotEmpty() || c.sessionCount > 0
            val take = overwrite || !hasLocal
            db.comics().update(
                c.copy(
                    title = if (!c.metadataEdited || overwrite) b.title else c.title, series = b.series ?: c.series, issue = b.issue ?: c.issue,
                    volume = b.volume ?: c.volume, year = b.year ?: c.year, author = b.author ?: c.author, publisher = b.publisher ?: c.publisher,
                    metadataEdited = c.metadataEdited || b.title != c.title,
                    favorite = c.favorite || b.favorite, wantToRead = c.wantToRead || b.wantToRead, rating = c.rating ?: b.rating, notes = c.notes ?: b.notes,
                    currentPage = if (take) b.currentPage.coerceAtLeast(1) else c.currentPage, readPages = if (take) b.readPages else c.readPages,
                    status = if (take) b.status else c.status, startedAt = if (take) b.startedAt else c.startedAt,
                    lastReadAt = if (take) b.lastReadAt else c.lastReadAt, completedAt = if (take) b.completedAt else c.completedAt,
                    activeSeconds = if (take) b.activeSeconds else c.activeSeconds, sessionCount = if (take) b.sessionCount else c.sessionCount,
                    manuallyCompleted = if (take) b.status == ReadStatus.COMPLETED.name else c.manuallyCompleted,
                ),
            )
            b.tags.forEach { db.tags().insert(ComicTagEntity(id, it)) }
            if (take) {
                db.sessions().deleteForComic(id)
                b.sessions.forEach { db.sessions().insert(SessionEntity(comicId = id, startMs = it.startMs, endMs = it.endMs, activeSeconds = it.activeSeconds, newPages = it.newPages, pageVisits = it.pageVisits)) }
            }
            val existing = db.bookmarks().getForComic(id)
            b.bookmarks.filter { nb -> existing.none { it.page == nb.page && it.createdAt == nb.createdAt } }
                .forEach { db.bookmarks().insert(BookmarkEntity(comicId = id, page = it.page, name = it.name, createdAt = it.createdAt, color = it.color, note = it.note)) }
            n++
        }
        val cols = db.collections().getAll().associateBy { it.name.lowercase() }
        for (bc in plan.data.collections) {
            val col = cols[bc.name.lowercase()]
            val colId = col?.id ?: db.collections().insert(CollectionEntity(name = bc.name, expectedIssues = bc.expectedIssues, createdAt = System.currentTimeMillis()))
            bc.comicKeys.mapNotNull { plan.matched[it] }.forEach { cid ->
                db.collections().upsertItem(CollectionItemEntity(colId, cid, db.collections().nextPosition(colId)))
            }
        }
        if (restoreSettings) settings.importAll(plan.data.settings)
        n
    }
}
