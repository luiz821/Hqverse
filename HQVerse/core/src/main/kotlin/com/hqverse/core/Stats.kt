package com.hqverse.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class SessionStat(val startMs: Long, val activeSeconds: Long, val newPages: Int)

data class LibraryTotals(
    val comics: Int,
    val availablePages: Long,
    val completed: Int,
    val inProgress: Int,
    val notStarted: Int,
)

/** Estatísticas calculadas somente a partir de dados registrados pelo aplicativo. */
object StatsCalc {
    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun readingDays(sessions: List<SessionStat>, zone: ZoneId): Set<LocalDate> =
        sessions.filter { it.activeSeconds > 0 }.map { dayOf(it.startMs, zone) }.toSet()

    /** Dias consecutivos terminando hoje; se hoje ainda não leu, conta a partir de ontem. */
    fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in days) today else today.minusDays(1)
        var n = 0
        while (cursor in days) { n++; cursor = cursor.minusDays(1) }
        return n
    }

    fun longestStreak(days: Set<LocalDate>): Int {
        var best = 0
        for (d in days) {
            if (d.minusDays(1) in days) continue
            var len = 1
            var c = d.plusDays(1)
            while (c in days) { len++; c = c.plusDays(1) }
            best = maxOf(best, len)
        }
        return best
    }

    fun mostActiveWeekday(sessions: List<SessionStat>, zone: ZoneId): DayOfWeek? =
        sessions.groupBy { dayOf(it.startMs, zone).dayOfWeek }
            .mapValues { (_, v) -> v.sumOf { it.activeSeconds } }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }?.key

    /** Páginas novas por semana (semana começa na segunda), últimas [weeks] semanas, da mais antiga à atual. */
    fun pagesByWeek(sessions: List<SessionStat>, zone: ZoneId, today: LocalDate, weeks: Int): List<Pair<LocalDate, Int>> {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byWeek = sessions.groupBy { dayOf(it.startMs, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return (weeks - 1 downTo 0).map { i ->
            val monday = thisMonday.minusWeeks(i.toLong())
            monday to (byWeek[monday]?.sumOf { it.newPages } ?: 0)
        }
    }

    fun pagesByMonth(sessions: List<SessionStat>, zone: ZoneId, today: LocalDate, months: Int): List<Pair<YearMonth, Int>> {
        val byMonth = sessions.groupBy { YearMonth.from(dayOf(it.startMs, zone)) }
        val current = YearMonth.from(today)
        return (months - 1 downTo 0).map { i ->
            val ym = current.minusMonths(i.toLong())
            ym to (byMonth[ym]?.sumOf { it.newPages } ?: 0)
        }
    }

    fun averagePagesPerSession(sessions: List<SessionStat>): Double =
        if (sessions.isEmpty()) 0.0 else sessions.sumOf { it.newPages }.toDouble() / sessions.size

    fun averageSecondsPerSession(sessions: List<SessionStat>): Double =
        if (sessions.isEmpty()) 0.0 else sessions.sumOf { it.activeSeconds }.toDouble() / sessions.size

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}

/** Progresso de uma coleção: nunca presume que ela está completa só pelos arquivos encontrados. */
object CollectionCalc {
    data class Item(val pages: Int, val status: ReadStatus, val issue: Int?)
    data class Summary(val issues: Int, val pages: Long, val completed: Int, val percentCompleted: Int)

    fun summarize(items: List<Item>): Summary {
        val done = items.count { it.status == ReadStatus.COMPLETED }
        val pct = if (items.isEmpty()) 0 else done * 100 / items.size
        return Summary(items.size, items.sumOf { it.pages.toLong() }, done, pct)
    }

    /** Números ausentes, SOMENTE se o usuário informou a sequência esperada (ex.: "1-12"). */
    fun missingIssues(expectedSpec: String?, ownedIssues: Collection<Int>): List<Int>? {
        val expected = IntRangeSet.decode(expectedSpec) ?: return null
        if (expected.isEmpty) return null
        val owned = IntRangeSet().also { s -> ownedIssues.forEach { s.add(it) } }
        return owned.missingFrom(expected)
    }
}
