package com.hqverse.app.data.repo

import android.content.Context
import android.net.Uri
import com.hqverse.app.data.db.*
import com.hqverse.app.data.files.CacheManager
import com.hqverse.app.data.files.Saf
import com.hqverse.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

fun ComicEntity.toRow(tags: Set<String>, collections: Set<Long>): ComicRow {
    val status = runCatching { ReadStatus.valueOf(status) }.getOrDefault(ReadStatus.NOT_STARTED)
    return ComicRow(
        id = id, title = title, series = series, issue = issue, volume = volume, year = year,
        author = author, publisher = publisher, fileName = fileName,
        format = runCatching { ComicFormat.valueOf(format) }.getOrNull(),
        status = status, progressPercent = if (status == ReadStatus.COMPLETED) 100 else ProgressCalc.percent(currentPage, pageCount),
        pageCount = pageCount, addedAt = addedAt, lastReadAt = lastReadAt, favorite = favorite,
        wantToRead = wantToRead, hidden = hidden, missing = missing, hasError = errorCode != null,
        folderId = folderId ?: 0L, tags = tags, collectionIds = collections,
    )
}

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val cache: CacheManager,
) {
    /** Linhas leves para busca/filtros/ordenação, já combinando tags e coleções. */
    val rows: Flow<List<ComicRow>> = combine(
        db.comics().observeAll(), db.tags().observeAll(), db.collections().observeAllItems(),
    ) { comics, tags, items ->
        val tagsBy = tags.groupBy({ it.comicId }, { it.tag })
        val colsBy = items.groupBy({ it.comicId }, { it.collectionId })
        comics.map { it.toRow(tagsBy[it.id].orEmpty().toSet(), colsBy[it.id].orEmpty().toSet()) }
    }

    val comics: Flow<List<ComicEntity>> = db.comics().observeAll()
    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()
    fun comic(id: Long): Flow<ComicEntity?> = db.comics().observe(id)
    fun tagsOf(id: Long): Flow<List<String>> = db.tags().observeForComic(id)
    val allTags: Flow<List<String>> = db.tags().observeAllTags()

    // ---------- pastas ----------

    /** Registra uma pasta já autorizada (permissão persistente tomada pela UI). Evita duplicatas. */
    suspend fun addFolder(treeUri: Uri, displayName: String): Long {
        val uri = treeUri.toString()
        db.folders().getByUri(uri)?.let { return it.id }
        return db.folders().insert(FolderEntity(uri = uri, name = displayName, addedAt = System.currentTimeMillis()))
    }

    suspend fun setFolderSubfolders(folder: FolderEntity, include: Boolean) = db.folders().update(folder.copy(includeSubfolders = include))
    suspend fun setFolderEnabled(folder: FolderEntity, enabled: Boolean) = db.folders().update(folder.copy(enabled = enabled))

    /**
     * Remove a pasta da biblioteca. NUNCA toca nos arquivos originais.
     * [keepReadingData] = true: as HQs ficam ocultas (sem pasta) com progresso, marcadores e histórico,
     * e são readotadas automaticamente se a mesma pasta/arquivos forem adicionados de novo.
     * false: tudo é apagado do banco.
     */
    suspend fun removeFolder(folder: FolderEntity, keepReadingData: Boolean) {
        val comics = db.comics().getByFolder(folder.id)
        if (keepReadingData) db.comics().orphanPrepare(folder.id) else { comics.forEach { cache.deleteCoversOf(it.id) }; db.comics().deleteByFolder(folder.id) }
        db.folders().delete(folder.id) // FK SET NULL preserva os registros mantidos
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    // ---------- HQ ----------

    suspend fun setFavorite(id: Long, v: Boolean) = db.comics().setFavorite(id, v)
    suspend fun setWantToRead(id: Long, v: Boolean) = db.comics().setWantToRead(id, v)
    suspend fun setRating(id: Long, v: Int?) = db.comics().setRating(id, v?.coerceIn(1, 5))
    suspend fun setNotes(id: Long, v: String?) = db.comics().setNotes(id, v?.takeIf { it.isNotBlank() })
    suspend fun setHidden(id: Long, v: Boolean) = db.comics().setHidden(id, v)

    suspend fun editMetadata(id: Long, title: String, series: String?, issue: Int?, volume: Int?, year: Int?, author: String?, publisher: String?) {
        val c = db.comics().get(id) ?: return
        db.comics().update(
            c.copy(
                title = title.trim().ifEmpty { c.title }, series = series?.trim()?.ifEmpty { null }, issue = issue, volume = volume,
                year = year, author = author?.trim()?.ifEmpty { null }, publisher = publisher?.trim()?.ifEmpty { null }, metadataEdited = true,
            ),
        )
    }

    suspend fun addTag(id: Long, tag: String) { tag.trim().takeIf { it.isNotEmpty() }?.let { db.tags().insert(ComicTagEntity(id, it.take(40))) } }
    suspend fun removeTag(id: Long, tag: String) = db.tags().delete(id, tag)

    /** Remove da biblioteca. Se [keepData] = false, apaga também progresso, sessões e marcadores. O arquivo original NUNCA é apagado aqui. */
    suspend fun removeFromLibrary(id: Long, keepData: Boolean) {
        if (keepData) { db.comics().setHidden(id, true) } else { cache.deleteCoversOf(id); db.comics().delete(id) }
    }

    /** Exclusão FÍSICA do arquivo: só chamar após confirmação explícita do usuário. */
    suspend fun deleteFilePermanently(id: Long): Boolean {
        val c = db.comics().get(id) ?: return false
        val ok = Saf.delete(context, Uri.parse(c.uri))
        if (ok) { cache.deleteCoversOf(id); db.comics().delete(id) }
        return ok
    }

    // ---------- coleções ----------

    val collectionRows: Flow<List<CollectionRow>> = db.collections().observeRows()
    fun collection(id: Long) = db.collections().observe(id)
    fun collectionItems(id: Long) = db.collections().observeItems(id)
    fun collectionsOf(comicId: Long) = db.collections().observeItemsForComic(comicId)

    suspend fun createCollection(name: String): Long =
        db.collections().insert(CollectionEntity(name = name.trim().ifEmpty { "Nova coleção" }.take(60), createdAt = System.currentTimeMillis()))
    suspend fun renameCollection(c: CollectionEntity, name: String) = db.collections().update(c.copy(name = name.trim().ifEmpty { c.name }.take(60)))
    suspend fun setExpectedIssues(c: CollectionEntity, spec: String?): Boolean {
        val s = spec?.trim().orEmpty()
        if (s.isNotEmpty() && IntRangeSet.decode(s) == null) return false
        db.collections().update(c.copy(expectedIssues = s.ifEmpty { null })); return true
    }
    suspend fun deleteCollection(id: Long) = db.collections().delete(id)
    suspend fun addToCollection(collectionId: Long, comicId: Long) =
        db.collections().upsertItem(CollectionItemEntity(collectionId, comicId, db.collections().nextPosition(collectionId)))
    suspend fun removeFromCollection(collectionId: Long, comicId: Long) = db.collections().removeItem(collectionId, comicId)
    suspend fun setOwned(collectionId: Long, comicId: Long, owned: Boolean) = db.collections().setOwned(collectionId, comicId, owned)

    /** Move um item na ordem de leitura personalizada. */
    suspend fun moveInCollection(collectionId: Long, orderedComicIds: List<Long>, comicId: Long, delta: Int) {
        val from = orderedComicIds.indexOf(comicId)
        val to = (from + delta).coerceIn(0, orderedComicIds.lastIndex)
        if (from < 0 || from == to) return
        val list = orderedComicIds.toMutableList().also { it.add(to, it.removeAt(from)) }
        list.forEachIndexed { i, id -> db.collections().setPosition(collectionId, id, i) }
    }

    // ---------- marcadores ----------

    fun bookmarks(comicId: Long) = db.bookmarks().observeForComic(comicId)
    suspend fun addBookmark(comicId: Long, page: Int, name: String?, color: String?, note: String?) =
        db.bookmarks().insert(BookmarkEntity(comicId = comicId, page = page, name = name?.trim()?.ifEmpty { null }, createdAt = System.currentTimeMillis(), color = color, note = note?.trim()?.ifEmpty { null }))
    suspend fun updateBookmark(b: BookmarkEntity) = db.bookmarks().update(b)
    suspend fun deleteBookmark(b: BookmarkEntity) = db.bookmarks().delete(b)
}
