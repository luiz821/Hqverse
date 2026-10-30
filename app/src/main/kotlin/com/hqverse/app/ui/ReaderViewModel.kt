package com.hqverse.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hqverse.app.AppContainer
import com.hqverse.app.data.db.BookmarkEntity
import com.hqverse.app.data.reader.PageLoader
import com.hqverse.app.data.repo.ReadingController
import com.hqverse.app.data.settings.AppSettings
import com.hqverse.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Failed(val failure: ReadFailure) : ReaderState
    data class Ready(val loader: PageLoader, val pageCount: Int, val startIndex: Int) : ReaderState
}

class ReaderViewModel(private val c: AppContainer, val comicId: Long, private val startPage: Int = -1) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val title = MutableStateFlow("")
    val bookmarks = c.library.bookmarks(comicId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val eta = MutableStateFlow<Eta?>(null)
    val timerPaused = MutableStateFlow(false)
    val justCompleted = MutableStateFlow(false)
    val currentIndex = MutableStateFlow(0)
    val jump = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    fun requestJump(index: Int) { jump.tryEmit(index) }

    private var controller: ReadingController? = null
    private var loader: PageLoader? = null
    private var pace: Pace? = null
    private var ticker: Job? = null
    private var wasCompleted = false
    private var started = false

    /** [targetWidth] = largura de renderização em pixels (largura da tela). */
    fun open(targetWidth: Int) {
        if (started) return
        started = true
        viewModelScope.launch {
            val comic = c.library.comic(comicId).first()
            if (comic == null) { state.value = ReaderState.Failed(ReadFailure.NOT_FOUND); return@launch }
            title.value = comic.title
            wasCompleted = comic.status == ReadStatus.COMPLETED.name
            try {
                val pages = c.opener.open(comic)
                val l = PageLoader(pages, targetWidth, viewModelScope)
                loader = l
                if (comic.pageCount != l.pageCount) c.progress.setTotalPages(comicId, l.pageCount)
                val s = settings.value
                pace = c.progress.pace(s.defaultPagesPerMinute)
                controller = c.progress.startController(comic, s)
                // Retoma na página exata em que parou (se já concluída, volta ao começo ao reabrir).
                val start = if (startPage > 0) (startPage - 1).coerceIn(0, l.pageCount - 1) else if (comic.status == ReadStatus.COMPLETED.name && comic.currentPage >= l.pageCount) 0 else (comic.currentPage - 1).coerceIn(0, l.pageCount - 1)
                currentIndex.value = start
                state.value = ReaderState.Ready(l, l.pageCount, start)
                onPage(start)
                startTicker()
            } catch (e: ComicReadException) {
                state.value = ReaderState.Failed(e.failure)
            } catch (e: Exception) {
                state.value = ReaderState.Failed(ReadFailure.CORRUPT)
            }
        }
    }

    /** [index] base 0. */
    fun onPage(index: Int) {
        val l = loader ?: return
        currentIndex.value = index
        controller?.onPageViewed(index + 1)
        l.prefetch(listOf(index + 1, index + 2, index - 1))
        l.trimAround(index, 4)
        pace?.let { p -> eta.value = EtaEstimator.estimate(index + 1, l.pageCount, p) }
        if (settings.value.autoSave) viewModelScope.launch { saveNow() }
    }

    private suspend fun saveNow() {
        val ctl = controller ?: return
        ctl.saveProgress()
        if (!wasCompleted) {
            val st = c.library.comic(comicId).first()?.status
            if (st == ReadStatus.COMPLETED.name) { wasCompleted = true; justCompleted.value = true }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(settings.value.saveIntervalSec * 1000L)
                if (settings.value.autoSave) saveNow()
            }
        }
    }

    fun interaction() { controller?.onInteraction() }
    fun dismissCompleted() { justCompleted.value = false }

    fun toggleTimer() {
        val ctl = controller ?: return
        if (ctl.isPaused) ctl.resume() else ctl.pause()
        timerPaused.value = ctl.isPaused
    }

    /** Segundo plano: o tempo para de contar e o progresso é salvo. */
    fun onBackground() {
        controller?.pause()
        viewModelScope.launch { saveNow() }
    }

    fun onForeground() { if (!timerPaused.value) controller?.resume() }

    fun addBookmark(name: String?, color: String?, note: String?) = viewModelScope.launch {
        c.library.addBookmark(comicId, currentIndex.value + 1, name, color, note)
    }
    fun updateBookmark(b: BookmarkEntity) = viewModelScope.launch { c.library.updateBookmark(b) }
    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { c.library.deleteBookmark(b) }

    fun setReaderMode(v: com.hqverse.app.data.settings.ReaderMode) = viewModelScope.launch { c.settings.setReaderMode(v) }
    fun setDirection(v: com.hqverse.app.data.settings.ReadingDirection) = viewModelScope.launch { c.settings.setDirection(v) }
    fun setFit(v: com.hqverse.app.data.settings.FitMode) = viewModelScope.launch { c.settings.setFitMode(v) }
    fun setBrightness(v: Float) = viewModelScope.launch { c.settings.setBrightness(v) }
    fun setOrientation(v: com.hqverse.app.data.settings.OrientationLock) = viewModelScope.launch { c.settings.setOrientation(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { c.settings.setKeepScreenOn(v) }

    /** Sair do leitor: salva e fecha a sessão fora do escopo da tela (que está sendo destruído). */
    override fun onCleared() {
        ticker?.cancel()
        val ctl = controller
        val l = loader
        c.appScope.launch {
            runCatching { ctl?.finish() }
            l?.close()
        }
        super.onCleared()
    }
}
