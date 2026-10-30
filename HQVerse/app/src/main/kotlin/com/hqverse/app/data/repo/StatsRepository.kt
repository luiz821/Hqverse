package com.hqverse.app.data.repo

import com.hqverse.app.data.db.AppDatabase
import com.hqverse.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class StatsUi(
    val totals: LibraryTotals,
    val pagesRead: Int,
    val totalSeconds: Long,
    val sessions: Int,
    val avgPagesPerSession: Double,
    val avgSecondsPerSession: Double,
    val currentStreak: Int,
    val longestStreak: Int,
    val mostActiveDay: DayOfWeek?,
    val weekly: List<Pair<LocalDate, Int>>,
    val monthly: List<Pair<YearMonth, Int>>,
    val readingDays: Set<LocalDate>,
    val completedHistory: List<Pair<String, Long>>,
    val byFormat: Map<String, Int>,
    val topCollections: List<Pair<String, Int>>,
    val overallPercent: Int,
)

/** Estatísticas só com dados registrados: páginas lidas = páginas ÚNICAS (revisitas não contam de novo). */
class StatsRepository(private val db: AppDatabase) {
    val stats: Flow<StatsUi> = combine(
        db.comics().observeAll(), db.sessions().observeClosed(), db.collections().observeRows(), db.collections().observeAllItems(),
    ) { comics, sessions, cols, items ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val visible = comics.filter { !it.hidden && !it.missing }
        val stat = sessions.map { SessionStat(it.startMs, it.activeSeconds, it.newPages) }
        val days = StatsCalc.readingDays(stat, zone)
        val pagesAvailable = visible.sumOf { it.pageCount.toLong() }
        val unique = visible.sumOf { (IntRangeSet.decode(it.readPages)?.count ?: 0) }
        val statusOf = { c: com.hqverse.app.data.db.ComicEntity -> runCatching { ReadStatus.valueOf(c.status) }.getOrDefault(ReadStatus.NOT_STARTED) }
        val totals = LibraryTotals(
            visible.size, pagesAvailable,
            visible.count { statusOf(it) == ReadStatus.COMPLETED },
            visible.count { statusOf(it) == ReadStatus.IN_PROGRESS || statusOf(it) == ReadStatus.PAUSED },
            visible.count { statusOf(it) == ReadStatus.NOT_STARTED },
        )
        val readByComic = items.groupBy { it.collectionId }
        val comicById = comics.associateBy { it.id }
        StatsUi(
            totals = totals, pagesRead = unique, totalSeconds = sessions.sumOf { it.activeSeconds }, sessions = sessions.size,
            avgPagesPerSession = StatsCalc.averagePagesPerSession(stat), avgSecondsPerSession = StatsCalc.averageSecondsPerSession(stat),
            currentStreak = StatsCalc.currentStreak(days, today), longestStreak = StatsCalc.longestStreak(days),
            mostActiveDay = StatsCalc.mostActiveWeekday(stat, zone),
            weekly = StatsCalc.pagesByWeek(stat, zone, today, 8), monthly = StatsCalc.pagesByMonth(stat, zone, today, 6),
            readingDays = days,
            completedHistory = visible.filter { it.completedAt != null }.sortedByDescending { it.completedAt }.take(30).map { it.title to it.completedAt!! },
            byFormat = visible.groupingBy { runCatching { ComicFormat.valueOf(it.format).label }.getOrDefault(it.format) }.eachCount(),
            topCollections = cols.map { row ->
                row.collection.name to (readByComic[row.collection.id].orEmpty().sumOf { comicById[it.comicId]?.sessionCount ?: 0 })
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5),
            overallPercent = if (pagesAvailable > 0) (unique * 100L / pagesAvailable).toInt().coerceAtMost(100) else 0,
        )
    }
}
