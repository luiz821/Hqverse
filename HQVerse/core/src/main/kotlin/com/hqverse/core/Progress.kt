package com.hqverse.core

enum class ReadStatus { NOT_STARTED, IN_PROGRESS, PAUSED, COMPLETED }

/** Regra de conclusão automática. Configurável nas Configurações. */
data class CompletionRule(
    /** Fração mínima de páginas únicas lidas para considerar a HQ concluída. */
    val minReadFraction: Double = 0.8,
    /** Quantas páginas finais (créditos/anúncios) podem ficar sem abrir. */
    val endTolerance: Int = 1,
)

data class ProgressSnapshot(
    /** Página atual, base 1. */
    val currentPage: Int,
    val totalPages: Int,
    val uniquePagesRead: Int,
    val manuallyCompleted: Boolean = false,
    val manuallyPaused: Boolean = false,
)

object ProgressCalc {
    /** Percentual inteiro (35 de 120 = 29). */
    fun percent(currentPage: Int, totalPages: Int): Int {
        if (totalPages <= 0) return 0
        return (currentPage.coerceIn(0, totalPages) * 100) / totalPages
    }

    fun remainingPages(currentPage: Int, totalPages: Int): Int =
        (totalPages - currentPage).coerceAtLeast(0)

    /** Abrir a última página, sozinho, NÃO conclui a leitura. */
    fun status(s: ProgressSnapshot, rule: CompletionRule = CompletionRule()): ReadStatus {
        if (s.manuallyCompleted) return ReadStatus.COMPLETED
        if (s.totalPages > 0) {
            val reachedEnd = s.currentPage >= s.totalPages - rule.endTolerance
            val readEnough = s.uniquePagesRead.toDouble() / s.totalPages >= rule.minReadFraction
            if (reachedEnd && readEnough) return ReadStatus.COMPLETED
        }
        if (s.uniquePagesRead == 0 && s.currentPage <= 1) return ReadStatus.NOT_STARTED
        if (s.manuallyPaused) return ReadStatus.PAUSED
        return ReadStatus.IN_PROGRESS
    }
}

/** Sessão de leitura já finalizada (como gravada no banco). */
data class SessionRecord(
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    /** Páginas nunca lidas antes, vistas nesta sessão. */
    val newPages: Int,
    /** Total de trocas de página (inclui revisitas). */
    val pageVisits: Int,
)

enum class PaceSource { HISTORY, DEFAULT }

data class Pace(val pagesPerMinute: Double, val source: PaceSource, val sessionsUsed: Int)

data class Eta(
    val currentPage: Int,
    val totalPages: Int,
    val percent: Int,
    val remainingPages: Int,
    /** Estimativa em minutos, arredondada para cima. */
    val minutes: Int,
    val pace: Pace,
)

object EtaEstimator {
    const val MIN_SESSION_SECONDS = 60L
    const val MIN_SESSION_PAGES = 3
    const val MIN_SESSIONS = 2
    const val MIN_TOTAL_SECONDS = 300L
    const val MIN_PPM = 0.1
    const val MAX_PPM = 30.0

    /**
     * Ritmo real do leitor, ponderado por tempo ativo. Sessões curtas demais ou sem páginas
     * novas são ignoradas. Sem dados suficientes, usa [defaultPpm].
     */
    fun pace(sessions: List<SessionRecord>, defaultPpm: Double): Pace {
        val valid = sessions.filter { it.activeSeconds >= MIN_SESSION_SECONDS && it.newPages >= MIN_SESSION_PAGES }
        val seconds = valid.sumOf { it.activeSeconds }
        if (valid.size >= MIN_SESSIONS && seconds >= MIN_TOTAL_SECONDS) {
            val ppm = valid.sumOf { it.newPages } / (seconds / 60.0)
            return Pace(ppm.coerceIn(MIN_PPM, MAX_PPM), PaceSource.HISTORY, valid.size)
        }
        return Pace(defaultPpm.coerceIn(MIN_PPM, MAX_PPM), PaceSource.DEFAULT, 0)
    }

    fun estimate(currentPage: Int, totalPages: Int, pace: Pace): Eta? {
        if (totalPages <= 0) return null
        val remaining = ProgressCalc.remainingPages(currentPage, totalPages)
        val minutes = kotlin.math.ceil(remaining / pace.pagesPerMinute).toInt()
        return Eta(currentPage, totalPages, ProgressCalc.percent(currentPage, totalPages), remaining, minutes, pace)
    }
}

data class SessionSummary(
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val newPages: Int,
    val distinctPages: Int,
    val pageVisits: Int,
) {
    fun toRecord() = SessionRecord(startMs, endMs, activeSeconds, newPages, pageVisits)
}

/**
 * Contabiliza uma sessão. O tempo só conta entre interações, limitado por [idleCapMs] (uma
 * pausa longa sem interação não vira tempo de leitura) e nunca enquanto está pausada ou em
 * segundo plano.
 */
class SessionTracker(
    private val startMs: Long,
    alreadyRead: IntRangeSet,
    private val idleCapMs: Long = 120_000,
) {
    private val knownBefore = IntRangeSet().also { set -> alreadyRead.toList().forEach { set.add(it) } }
    private val seenThisSession = IntRangeSet()
    private var newPages = 0
    private var visits = 0
    private var activeMs = 0L
    private var lastEventMs = startMs
    private var paused = false
    private var lastPage = -1

    val isPaused: Boolean get() = paused

    fun onInteraction(nowMs: Long) {
        if (!paused) activeMs += (nowMs - lastEventMs).coerceIn(0, idleCapMs)
        lastEventMs = nowMs
    }

    fun onPageViewed(page: Int, nowMs: Long) {
        onInteraction(nowMs)
        if (paused || page == lastPage) return
        lastPage = page
        visits++
        seenThisSession.add(page)
        if (knownBefore.add(page)) newPages++
    }

    /** Pausa manual ou aplicativo em segundo plano. */
    fun pause(nowMs: Long) {
        onInteraction(nowMs)
        paused = true
    }

    fun resume(nowMs: Long) {
        paused = false
        lastEventMs = nowMs
    }

    fun finish(nowMs: Long): SessionSummary {
        onInteraction(nowMs)
        return snapshot()
    }

    /**
     * Estado atual sem encerrar a sessão. Gravado periodicamente como checkpoint: se o app for
     * encerrado de forma inesperada, a sessão aberta é fechada na próxima abertura com estes valores.
     */
    fun snapshot(): SessionSummary =
        SessionSummary(startMs, lastEventMs, activeMs / 1000, newPages, seenThisSession.count, visits)
}
