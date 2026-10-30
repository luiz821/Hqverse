package com.hqverse.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hqverse.app.AppContainer
import com.hqverse.app.data.db.*
import com.hqverse.app.data.repo.StatsUi
import com.hqverse.app.data.scan.ScanState
import com.hqverse.app.data.scan.ScanWorker
import com.hqverse.app.data.settings.AppSettings
import com.hqverse.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Biblioteca: lista, busca, filtros, ordenação, pastas e varredura. */
class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val filter = MutableStateFlow(LibraryFilter())
    val folders: StateFlow<List<FolderEntity>> = c.library.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scan: StateFlow<ScanState> = c.scanCoordinator.state
    val allTags = c.library.allTags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val collections = c.library.collectionRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val entities = c.library.comics.map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    /** null enquanto o banco ainda carrega (evita piscar a tela de boas-vindas). */
    val rows: StateFlow<List<ComicRow>?> = c.library.rows.map<List<ComicRow>, List<ComicRow>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val visible: StateFlow<List<ComicEntity>> = combine(rows, filter, settings, entities) { r, f, s, e ->
        val sorted = LibraryQuery.sort(LibraryQuery.filter(r.orEmpty(), f), s.sortKey, s.sortDescending)
        sorted.mapNotNull { e[it.id] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val series: StateFlow<List<Pair<SeriesGroup, ComicEntity?>>> = combine(rows, filter, entities) { r, f, e ->
        LibraryQuery.groupBySeries(LibraryQuery.filter(r.orEmpty(), f.copy(series = null))).map { g -> g to g.comics.firstNotNullOfOrNull { e[it.id]?.takeIf { c -> c.coverPath != null } } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueReading: StateFlow<List<ComicEntity>> = combine(rows, entities) { r, e -> LibraryQuery.continueReading(r.orEmpty()).mapNotNull { e[it.id] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: StateFlow<List<ComicEntity>> = combine(rows, entities) { r, e ->
        LibraryQuery.sort(LibraryQuery.filter(r.orEmpty(), LibraryFilter()), SortKey.ADDED, true).take(15).mapNotNull { e[it.id] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites: StateFlow<List<ComicEntity>> = combine(rows, entities) { r, e ->
        LibraryQuery.filter(r.orEmpty(), LibraryFilter(onlyFavorites = true)).take(15).mapNotNull { e[it.id] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Arquivos que não puderam ser lidos ou que sumiram, e HQs ocultas (para gerenciar nas Pastas). */
    val problems: StateFlow<List<ComicEntity>> = c.library.comics.map { l -> l.filter { (it.errorCode != null || it.missing) && !it.hidden } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hiddenList: StateFlow<List<ComicEntity>> = c.library.comics.map { l -> l.filter { it.hidden } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun retry(comic: ComicEntity) = viewModelScope.launch { c.indexer.processOne(comic, comic.customCoverPage) }
    val errorCount: StateFlow<Int> = rows.map { r -> r.orEmpty().count { it.hasError && !it.hidden } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val overallPercent: StateFlow<Int> = combine(rows) { arr ->
        val r = arr[0].orEmpty().filter { !it.hidden && !it.missing }
        if (r.isEmpty()) 0 else r.sumOf { it.progressPercent } / r.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun update(block: (LibraryFilter) -> LibraryFilter) = filter.update(block)
    fun preset(statuses: Set<ReadStatus> = emptySet(), favorites: Boolean = false, wantToRead: Boolean = false, errors: Boolean = false) {
        filter.value = LibraryFilter(statuses = statuses, onlyFavorites = favorites, onlyWantToRead = wantToRead, onlyErrors = errors, showMissing = errors)
    }

    fun setSort(key: SortKey, desc: Boolean) = viewModelScope.launch { c.settings.setSort(key, desc) }
    fun setViewMode(v: com.hqverse.app.data.settings.ViewMode) = viewModelScope.launch { c.settings.setViewMode(v) }

    /** Chamado após o seletor de pastas: guarda a permissão persistente, cadastra e varre. */
    fun onFolderPicked(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching { com.hqverse.app.data.files.Saf.persistRead(context, uri) }
            .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "Pasta"
        val id = c.library.addFolder(uri, name)
        ScanWorker.enqueue(context, listOf(id))
    }

    fun rescanAll(context: Context) = ScanWorker.enqueue(context)
    fun rescan(context: Context, folder: FolderEntity) = ScanWorker.enqueue(context, listOf(folder.id))
    fun pauseScan(paused: Boolean) = c.scanCoordinator.setPaused(paused)
    fun cancelScan(context: Context) { ScanWorker.cancel(context); c.scanCoordinator.finish("Varredura cancelada. O que já foi indexado foi mantido.") }
    fun dismissScanMessage() = c.scanCoordinator.update { it.copy(message = null) }
    fun setSubfolders(f: FolderEntity, v: Boolean) = viewModelScope.launch { c.library.setFolderSubfolders(f, v) }
    fun setFolderEnabled(f: FolderEntity, v: Boolean) = viewModelScope.launch { c.library.setFolderEnabled(f, v) }
    fun removeFolder(f: FolderEntity, keepData: Boolean) = viewModelScope.launch { c.library.removeFolder(f, keepData) }
    fun setIgnored(v: String) = viewModelScope.launch { c.settings.setIgnoredFolderNames(v) }
    fun hide(id: Long, v: Boolean) = viewModelScope.launch { c.library.setHidden(id, v) }

    /** Varredura automática ao abrir o app (incremental: só processa o que mudou). */
    fun autoScanIfEnabled(context: Context) = viewModelScope.launch {
        if (c.settings.current().autoScanOnStart && c.library.folders.first().isNotEmpty()) ScanWorker.enqueue(context)
    }
}

/** Detalhe de uma HQ. */
class DetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val comic = c.library.comic(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val tags = c.library.tagsOf(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = c.library.bookmarks(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = c.progress.sessionsOf(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val collectionIds = c.library.collectionsOf(id).map { l -> l.map { it.collectionId }.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val collections = c.library.collectionRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val folderName: StateFlow<String?> = combine(comic, c.library.folders) { cm, fs -> fs.firstOrNull { it.id == cm?.folderId }?.name }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val eta: StateFlow<Eta?> = combine(comic, c.progress.paceFlow(1.5f), settings) { cm, _, s ->
        cm?.let { EtaEstimator.estimate(it.currentPage, it.pageCount, c.progress.pace(s.defaultPagesPerMinute)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun favorite(v: Boolean) = viewModelScope.launch { c.library.setFavorite(id, v) }
    fun wantToRead(v: Boolean) = viewModelScope.launch { c.library.setWantToRead(id, v) }
    fun rating(v: Int?) = viewModelScope.launch { c.library.setRating(id, v) }
    fun notes(v: String) = viewModelScope.launch { c.library.setNotes(id, v) }
    fun markRead() = viewModelScope.launch { c.progress.markCompleted(id) }
    fun markUnread() = viewModelScope.launch { c.progress.markUnread(id) }
    fun resetProgress() = viewModelScope.launch { c.progress.resetProgress(id) }
    fun setPage(p: Int) = viewModelScope.launch { c.progress.setPage(id, p, c.progress.completionRule(settings.value)) }
    fun setTotal(t: Int) = viewModelScope.launch { c.progress.setTotalPages(id, t) }
    fun setPaused(v: Boolean) = viewModelScope.launch { c.progress.setPaused(id, v) }
    fun addTag(t: String) = viewModelScope.launch { c.library.addTag(id, t) }
    fun removeTag(t: String) = viewModelScope.launch { c.library.removeTag(id, t) }
    fun addBookmark(page: Int, name: String?, color: String?, note: String?) = viewModelScope.launch { c.library.addBookmark(id, page, name, color, note) }
    fun updateBookmark(b: BookmarkEntity) = viewModelScope.launch { c.library.updateBookmark(b) }
    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { c.library.deleteBookmark(b) }
    fun toggleCollection(colId: Long, member: Boolean) = viewModelScope.launch { if (member) c.library.removeFromCollection(colId, id) else c.library.addToCollection(colId, id) }
    fun createCollectionAndAdd(name: String) = viewModelScope.launch { c.library.addToCollection(c.library.createCollection(name), id) }
    fun edit(title: String, series: String?, issue: Int?, volume: Int?, year: Int?, author: String?, publisher: String?) =
        viewModelScope.launch { c.library.editMetadata(id, title, series, issue, volume, year, author, publisher) }
    fun removeFromLibrary(keepData: Boolean) = viewModelScope.launch { c.library.removeFromLibrary(id, keepData) }
    fun deleteFile(onDone: (Boolean) -> Unit) = viewModelScope.launch { onDone(c.library.deleteFilePermanently(id)) }
    fun setCoverPage(page: Int, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val cm = c.library.comic(id).first() ?: return@launch
        onDone(c.indexer.processOne(cm, page - 1))
    }
    fun retry() = viewModelScope.launch { c.library.comic(id).first()?.let { c.indexer.processOne(it, it.customCoverPage) } }
}

/** Coleções. */
class CollectionsViewModel(private val c: AppContainer) : ViewModel() {
    val rows = c.library.collectionRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun create(name: String) = viewModelScope.launch { c.library.createCollection(name) }
    fun delete(id: Long) = viewModelScope.launch { c.library.deleteCollection(id) }
}

data class CollectionDetailUi(
    val collection: CollectionEntity?, val items: List<Pair<CollectionItemEntity, ComicEntity?>>,
    val summary: CollectionCalc.Summary, val missing: List<Int>?,
)

class CollectionDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val ui: StateFlow<CollectionDetailUi> = combine(c.library.collection(id), c.library.collectionItems(id), c.library.comics) { col, items, comics ->
        val by = comics.associateBy { it.id }
        val pairs = items.map { it to by[it.comicId] }
        val owned = pairs.filter { it.first.owned }.mapNotNull { it.second }
        val summary = CollectionCalc.summarize(owned.map { CollectionCalc.Item(it.pageCount, runCatching { ReadStatus.valueOf(it.status) }.getOrDefault(ReadStatus.NOT_STARTED), it.issue) })
        CollectionDetailUi(col, pairs, summary, CollectionCalc.missingIssues(col?.expectedIssues, owned.mapNotNull { it.issue }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectionDetailUi(null, emptyList(), CollectionCalc.Summary(0, 0, 0, 0), null))
    val allComics = c.library.comics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(name: String) = viewModelScope.launch { ui.value.collection?.let { c.library.renameCollection(it, name) } }
    fun setExpected(spec: String?, onInvalid: () -> Unit) = viewModelScope.launch { ui.value.collection?.let { if (!c.library.setExpectedIssues(it, spec)) onInvalid() } }
    fun add(comicId: Long) = viewModelScope.launch { c.library.addToCollection(id, comicId) }
    fun remove(comicId: Long) = viewModelScope.launch { c.library.removeFromCollection(id, comicId) }
    fun move(comicId: Long, delta: Int) = viewModelScope.launch { c.library.moveInCollection(id, ui.value.items.map { it.first.comicId }, comicId, delta) }
    fun setOwned(comicId: Long, owned: Boolean) = viewModelScope.launch { c.library.setOwned(id, comicId, owned) }
}

class StatsViewModel(c: AppContainer) : ViewModel() {
    val stats: StateFlow<StatsUi?> = c.stats.stats.map<StatsUi, StatsUi?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val repo get() = c.settings
    fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
    suspend fun cacheSizeMb(): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.cache.sizeBytes() / (1024 * 1024) }
    fun clearCache(after: () -> Unit) = viewModelScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.cache.clear() }; after() }
    val backup get() = c.backup
}
