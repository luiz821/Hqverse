package com.hqverse.app.data.repo

import com.hqverse.app.data.db.AppDatabase
import com.hqverse.app.data.db.ComicEntity
import com.hqverse.app.data.db.SessionEntity
import com.hqverse.app.data.settings.AppSettings
import com.hqverse.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Progresso, sessões de leitura, tempo restante e histórico. */
class ProgressRepository(private val db: AppDatabase) {

    fun completionRule(s: AppSettings) = CompletionRule(s.completionPercent / 100.0, s.endTolerancePages)

    fun sessionsOf(comicId: Long): Flow<List<SessionEntity>> = db.sessions().observeForComic(comicId)

    /** Ritmo do usuário (histórico global, ponderado por tempo) ou o padrão configurado. */
    suspend fun pace(defaultPpm: Float): Pace =
        EtaEstimator.pace(db.sessions().getAllClosed().map { SessionRecord(it.startMs, it.endMs, it.activeSeconds, it.newPages, it.pageVisits) }, defaultPpm.toDouble())

    fun paceFlow(defaultPpm: Float): Flow<Pace> = db.sessions().observeClosed().map { list ->
        EtaEstimator.pace(list.map { SessionRecord(it.startMs, it.endMs, it.activeSeconds, it.newPages, it.pageVisits) }, defaultPpm.toDouble())
    }

    /** Fecha sessões que ficaram abertas porque o processo foi morto, usando o último checkpoint. */
    suspend fun recoverOpenSessions() {
        for (s in db.sessions().getOpen()) {
            db.sessions().update(s.copy(open = false))
            db.comics().addReadingTime(s.comicId, s.activeSeconds, 1)
        }
    }

    suspend fun startController(comic: ComicEntity, settings: AppSettings): ReadingController {
        val read = IntRangeSet.decode(comic.readPages) ?: IntRangeSet()
        val now = System.currentTimeMillis()
        val sessionId = db.sessions().insert(SessionEntity(comicId = comic.id, startMs = now, endMs = now, activeSeconds = 0, newPages = 0, pageVisits = 0, open = true))
        return ReadingController(db, comic, read, SessionTracker(now, read, settings.idleCapSec * 1000L), sessionId, completionRule(settings), settings.trackTime)
    }

    suspend fun markCompleted(id: Long) {
        val c = db.comics().get(id) ?: return
        val now = System.currentTimeMillis()
        db.comics().saveProgress(id, c.pageCount.coerceAtLeast(1), c.readPages, ReadStatus.COMPLETED.name, true, false, now, now)
    }

    suspend fun markUnread(id: Long) = db.comics().markUnread(id)
    suspend fun resetProgress(id: Long) { db.comics().resetProgress(id); db.sessions().deleteForComic(id) }

    suspend fun setPage(id: Long, page: Int, rule: CompletionRule) {
        val c = db.comics().get(id) ?: return
        val p = page.coerceIn(1, c.pageCount.coerceAtLeast(1))
        val read = IntRangeSet.decode(c.readPages) ?: IntRangeSet()
        // Definir a página manualmente NÃO marca páginas anteriores como lidas.
        val snap = ProgressSnapshot(p, c.pageCount, read.count, c.manuallyCompleted, c.manuallyPaused)
        db.comics().saveProgress(id, p, c.readPages, ProgressCalc.status(snap, rule).name, c.manuallyCompleted, c.manuallyPaused, System.currentTimeMillis(), c.completedAt)
    }

    suspend fun setPaused(id: Long, paused: Boolean) {
        val c = db.comics().get(id) ?: return
        db.comics().saveProgress(id, c.currentPage, c.readPages, if (paused && c.status == ReadStatus.IN_PROGRESS.name) ReadStatus.PAUSED.name else if (!paused && c.status == ReadStatus.PAUSED.name) ReadStatus.IN_PROGRESS.name else c.status, c.manuallyCompleted, paused, c.lastReadAt ?: System.currentTimeMillis(), c.completedAt)
    }

    /** Corrige o total de páginas (ex.: arquivo com páginas extras). */
    suspend fun setTotalPages(id: Long, total: Int) { if (total in 1..100_000) db.comics().setPageCount(id, total) }
}

/** Controla uma sessão de leitura aberta: salva progresso e checkpoints de sessão. */
class ReadingController(
    private val db: AppDatabase,
    private var comic: ComicEntity,
    private val read: IntRangeSet,
    private val tracker: SessionTracker,
    private val sessionId: Long,
    private val rule: CompletionRule,
    private val trackTime: Boolean,
) {
    private var page = comic.currentPage
    private var closed = false

    val startPage: Int get() = comic.currentPage

    fun onPageViewed(p: Int) { page = p; read.add(p); tracker.onPageViewed(p, System.currentTimeMillis()) }
    fun onInteraction() = tracker.onInteraction(System.currentTimeMillis())
    fun pause() = tracker.pause(System.currentTimeMillis())
    fun resume() = tracker.resume(System.currentTimeMillis())
    val isPaused: Boolean get() = tracker.isPaused

    /** Salva página e leitura únicas. Chamado a cada troca de página (barato) e periodicamente. */
    suspend fun saveProgress() {
        if (closed) return
        val now = System.currentTimeMillis()
        val fresh = db.comics().get(comic.id) ?: return
        val snap = ProgressSnapshot(page, fresh.pageCount, read.count, fresh.manuallyCompleted, fresh.manuallyPaused)
        val status = ProgressCalc.status(snap, rule)
        val completedAt = if (status == ReadStatus.COMPLETED) (fresh.completedAt ?: now) else fresh.completedAt.takeIf { fresh.manuallyCompleted }
        db.comics().saveProgress(comic.id, page, read.encode(), status.name, fresh.manuallyCompleted, fresh.manuallyPaused, now, completedAt)
        checkpoint()
    }

    private suspend fun checkpoint() {
        val s = tracker.snapshot()
        db.sessions().update(SessionEntity(sessionId, comic.id, s.startMs, s.endMs, if (trackTime) s.activeSeconds else 0, s.newPages, s.pageVisits, open = true))
    }

    /** Fecha a sessão. Descarta sessões vazias (abriu e saiu sem ler). */
    suspend fun finish() {
        if (closed) return
        saveProgress()
        closed = true
        val s = tracker.finish(System.currentTimeMillis())
        if (s.pageVisits == 0 || (s.activeSeconds == 0L && s.newPages == 0)) { db.sessions().delete(sessionId); return }
        val seconds = if (trackTime) s.activeSeconds else 0L
        db.sessions().update(SessionEntity(sessionId, comic.id, s.startMs, s.endMs, seconds, s.newPages, s.pageVisits, open = false))
        db.comics().addReadingTime(comic.id, seconds, 1)
    }
}
