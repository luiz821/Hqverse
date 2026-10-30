package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class LibraryAndStatsTest {
    private fun sf(uri: String, name: String = uri, size: Long = 100, mod: Long = 1) = ScannedFile(uri, name, size, mod)
    private fun ix(id: Long, uri: String, name: String = uri, size: Long = 100, mod: Long = 1) = IndexedFile(id, uri, name, size, mod)

    @Test fun indexacaoIncrementalNaoReprocessaInalterados() {
        val d = LibraryDiffer.diff(
            scanned = listOf(sf("a"), sf("b", mod = 2), sf("c")),
            existing = listOf(ix(1, "a"), ix(2, "b"), ix(3, "gone", size = 7777)),
        )
        assertEquals(listOf("c"), d.added.map { it.uri })
        assertEquals(listOf(1L), d.unchanged.map { it.id })
        assertEquals(listOf(2L), d.changed.map { it.first.id })
        assertEquals(listOf(3L), d.missing.map { it.id })
        assertTrue(d.moved.isEmpty())
    }

    @Test fun arquivoMovidoPreservaRegistro() {
        val d = LibraryDiffer.diff(
            scanned = listOf(sf("nova/Batman 001.cbr", "Batman 001.cbr", size = 500)),
            existing = listOf(ix(9, "velha/Batman 001.cbr", "Batman 001.cbr", size = 500)),
        )
        assertEquals(1, d.moved.size); assertEquals(9L, d.moved.single().first.id)
        assertTrue(d.added.isEmpty()); assertTrue(d.missing.isEmpty())
    }

    @Test fun arquivoRenomeadoPorTamanhoEData() {
        val d = LibraryDiffer.diff(
            scanned = listOf(sf("x", "Batman #1.cbr", size = 321, mod = 555)),
            existing = listOf(ix(4, "y", "Batman 001.cbr", size = 321, mod = 555)),
        )
        assertEquals(4L, d.moved.single().first.id)
    }

    @Test fun associacaoAmbiguaNaoTransfereProgresso() {
        // dois arquivos sumidos e dois novos, todos com mesmo tamanho/nome: não associa.
        val d = LibraryDiffer.diff(
            scanned = listOf(sf("p1/a.cbz", "a.cbz", 10), sf("p2/a.cbz", "a.cbz", 10)),
            existing = listOf(ix(1, "q1/a.cbz", "a.cbz", 10), ix(2, "q2/a.cbz", "a.cbz", 10)),
        )
        assertTrue(d.moved.isEmpty()); assertEquals(2, d.added.size); assertEquals(2, d.missing.size)
    }

    @Test fun semDuplicacaoNaVarredura() {
        val d = LibraryDiffer.diff(listOf(sf("a"), sf("a")), emptyList())
        assertEquals(1, d.added.size)
    }

    @Test fun pastaVazia() {
        val d = LibraryDiffer.diff(emptyList(), emptyList())
        assertTrue(d.added.isEmpty() && d.missing.isEmpty() && d.unchanged.isEmpty())
    }

    @Test fun bibliotecaGrandeNaoEQuadratica() {
        val n = 30_000
        val existing = (0 until n).map { ix(it.toLong(), "u$it", "n$it", size = it.toLong(), mod = it.toLong() + 1) }
        val scanned = (0 until n).map { sf("u$it", "n$it", size = it.toLong(), mod = it.toLong() + 1) } +
            (n until n + 500).map { sf("u$it", "n$it", size = it.toLong() + 1_000_000, mod = 9) }
        val t0 = System.nanoTime()
        val d = LibraryDiffer.diff(scanned, existing)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n, d.unchanged.size); assertEquals(500, d.added.size)
        assertTrue("demorou $ms ms", ms < 3_000)
    }

    @Test fun sequenciaDeLeituraEEstatisticas() {
        val zone = ZoneId.of("UTC")
        fun ms(date: String) = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val s = listOf(
            SessionStat(ms("2026-10-20"), 600, 10), SessionStat(ms("2026-10-21"), 300, 5),
            SessionStat(ms("2026-10-26"), 900, 20), SessionStat(ms("2026-10-27"), 600, 8),
            SessionStat(ms("2026-10-27"), 0, 0), // sem tempo ativo: não conta como dia de leitura
        )
        val days = StatsCalc.readingDays(s, zone)
        val today = LocalDate.parse("2026-10-27")
        assertEquals(2, StatsCalc.currentStreak(days, today))
        assertEquals(2, StatsCalc.currentStreak(days, today.plusDays(1))) // hoje ainda sem leitura: conta de ontem
        assertEquals(0, StatsCalc.currentStreak(days, today.plusDays(3)))
        assertEquals(2, StatsCalc.longestStreak(days))
        // terça (20/10 e 27/10: 1200 s) supera segunda (26/10: 900 s)
        assertEquals(DayOfWeek.TUESDAY, StatsCalc.mostActiveWeekday(s, zone))
        val weeks = StatsCalc.pagesByWeek(s, zone, today, 2)
        assertEquals(listOf(15, 28), weeks.map { it.second })
        val months = StatsCalc.pagesByMonth(s, zone, today, 2)
        assertEquals(YearMonth.of(2026, 10), months.last().first); assertEquals(43, months.last().second)
        assertEquals(43.0 / 5, StatsCalc.averagePagesPerSession(s), 1e-9)
    }

    @Test fun estatisticasSemDados() {
        assertEquals(0.0, StatsCalc.averagePagesPerSession(emptyList()), 0.0)
        assertNull(StatsCalc.mostActiveWeekday(emptyList(), ZoneId.of("UTC")))
        assertEquals(0, StatsCalc.currentStreak(emptySet(), LocalDate.now()))
    }

    @Test fun colecaoNaoPresumeCompletude() {
        assertNull(CollectionCalc.missingIssues(null, listOf(1, 2, 4))) // sem sequência informada
        assertNull(CollectionCalc.missingIssues("abc", listOf(1)))
        assertEquals(listOf(3, 5), CollectionCalc.missingIssues("1-6", listOf(1, 2, 4, 6)))
        val sum = CollectionCalc.summarize(
            listOf(
                CollectionCalc.Item(30, ReadStatus.COMPLETED, 1),
                CollectionCalc.Item(20, ReadStatus.IN_PROGRESS, 2),
            ),
        )
        assertEquals(2, sum.issues); assertEquals(50L, sum.pages); assertEquals(50, sum.percentCompleted)
    }
}
