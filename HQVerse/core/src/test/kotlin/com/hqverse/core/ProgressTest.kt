package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private fun snap(cur: Int, total: Int, read: Int, done: Boolean = false, paused: Boolean = false) =
        ProgressSnapshot(cur, total, read, done, paused)

    @Test fun percentualEPaginasRestantes() {
        assertEquals(29, ProgressCalc.percent(35, 120))
        assertEquals(85, ProgressCalc.remainingPages(35, 120))
        assertEquals(0, ProgressCalc.percent(5, 0))
        assertEquals(100, ProgressCalc.percent(500, 120))
    }

    @Test fun abrirUltimaPaginaNaoConclui() {
        // pulou direto para a última página: 1 página única lida
        assertEquals(ReadStatus.IN_PROGRESS, ProgressCalc.status(snap(120, 120, 2)))
    }

    @Test fun leituraRealConclui() {
        assertEquals(ReadStatus.COMPLETED, ProgressCalc.status(snap(120, 120, 110)))
        assertEquals(ReadStatus.COMPLETED, ProgressCalc.status(snap(119, 120, 110))) // tolera 1 página final
    }

    @Test fun regraConfiguravel() {
        val strict = CompletionRule(minReadFraction = 0.99, endTolerance = 0)
        assertEquals(ReadStatus.IN_PROGRESS, ProgressCalc.status(snap(119, 120, 119), strict))
        assertEquals(ReadStatus.COMPLETED, ProgressCalc.status(snap(120, 120, 120), strict))
    }

    @Test fun marcacaoManual() {
        assertEquals(ReadStatus.COMPLETED, ProgressCalc.status(snap(3, 120, 3, done = true)))
        assertEquals(ReadStatus.NOT_STARTED, ProgressCalc.status(snap(1, 120, 0)))
        assertEquals(ReadStatus.PAUSED, ProgressCalc.status(snap(10, 120, 10, paused = true)))
        assertEquals(ReadStatus.IN_PROGRESS, ProgressCalc.status(snap(10, 120, 10)))
    }

    @Test fun etaUsaRitmoPadraoSemHistorico() {
        val pace = EtaEstimator.pace(emptyList(), defaultPpm = 2.0)
        assertEquals(PaceSource.DEFAULT, pace.source)
        val eta = EtaEstimator.estimate(35, 120, pace)!!
        assertEquals(85, eta.remainingPages); assertEquals(29, eta.percent); assertEquals(43, eta.minutes)
    }

    @Test fun etaUsaHistoricoReal() {
        // 2 sessões válidas: 30 páginas em 10 min (3 ppm) e 20 em 10 min -> 50/20 = 2,5 ppm
        val s = listOf(
            SessionRecord(0, 0, 600, 30, 35),
            SessionRecord(0, 0, 600, 20, 25),
            SessionRecord(0, 0, 30, 50, 50), // curta demais: ignorada
            SessionRecord(0, 0, 900, 1, 1), // quase sem páginas novas: ignorada
        )
        val pace = EtaEstimator.pace(s, 1.0)
        assertEquals(PaceSource.HISTORY, pace.source); assertEquals(2, pace.sessionsUsed)
        assertEquals(2.5, pace.pagesPerMinute, 1e-9)
        assertEquals(40, EtaEstimator.estimate(20, 120, pace)!!.minutes) // 100 / 2,5
    }

    @Test fun poucoHistoricoVoltaAoPadrao() {
        val one = listOf(SessionRecord(0, 0, 600, 30, 30))
        assertEquals(PaceSource.DEFAULT, EtaEstimator.pace(one, 1.5).source)
    }

    @Test fun etaSemTotalDePaginas() {
        assertNull(EtaEstimator.estimate(1, 0, Pace(1.0, PaceSource.DEFAULT, 0)))
    }

    @Test fun etaNoFimDaLeituraEZero() {
        assertEquals(0, EtaEstimator.estimate(120, 120, Pace(1.0, PaceSource.DEFAULT, 0))!!.minutes)
    }

    @Test fun sessaoContaTempoEPaginasUnicas() {
        val t = SessionTracker(startMs = 0, alreadyRead = IntRangeSet().also { it.addRange(1, 5) })
        t.onPageViewed(5, 10_000)
        t.onPageViewed(6, 40_000)
        t.onPageViewed(7, 70_000)
        t.onPageViewed(6, 100_000) // revisita
        val s = t.finish(100_000)
        assertEquals(100, s.activeSeconds)
        assertEquals(2, s.newPages) // só 6 e 7 eram novas
        assertEquals(3, s.distinctPages) // 5, 6, 7
        assertEquals(4, s.pageVisits)
    }

    @Test fun mesmaPaginaSeguidaNaoContaVisitaNova() {
        val t = SessionTracker(0, IntRangeSet())
        t.onPageViewed(1, 1000); t.onPageViewed(1, 2000)
        assertEquals(1, t.finish(2000).pageVisits)
    }

    @Test fun pausaLongaNaoViraTempoDeLeitura() {
        val t = SessionTracker(0, IntRangeSet(), idleCapMs = 120_000)
        t.onPageViewed(1, 30_000)
        t.onPageViewed(2, 30_000 + 3_600_000) // 1 hora parado: conta no máximo 120 s
        assertEquals(150, t.finish(30_000 + 3_600_000).activeSeconds)
    }

    @Test fun segundoPlanoNaoContaTempo() {
        val t = SessionTracker(0, IntRangeSet())
        t.onPageViewed(1, 20_000)
        t.pause(20_000) // app foi para segundo plano
        t.resume(500_000)
        t.onPageViewed(2, 530_000)
        val s = t.finish(530_000)
        assertEquals(50, s.activeSeconds) // 20 s + 30 s
        assertTrue(!t.isPaused)
    }

    @Test fun pausaManualIgnoraPaginasEDuracao() {
        val t = SessionTracker(0, IntRangeSet())
        t.onPageViewed(1, 10_000)
        t.pause(10_000)
        t.onPageViewed(2, 50_000)
        assertEquals(1, t.finish(50_000).pageVisits)
    }

    @Test fun recuperacaoAposEncerramentoInesperado() {
        // Checkpoint gravado; processo morre; na reabertura a sessão aberta é fechada com o checkpoint.
        val t = SessionTracker(1_000, IntRangeSet())
        t.onPageViewed(1, 31_000); t.onPageViewed(2, 61_000)
        val checkpoint = t.snapshot() // gravado no banco como "sessão aberta"
        t.onPageViewed(3, 91_000) // perdido: o app morreu antes do próximo checkpoint
        val recovered = checkpoint.toRecord()
        assertEquals(60, recovered.activeSeconds); assertEquals(2, recovered.newPages)
        assertEquals(61_000, recovered.endMs)
        // O progresso (página atual/lidas) vem do banco, atualizado a cada troca de página:
        val reread = IntRangeSet.decode("1-2")!!
        assertEquals(2, reread.count)
    }

    @Test fun conjuntoDePaginasNaoContaRepetidas() {
        val s = IntRangeSet()
        assertTrue(s.add(3)); assertFalse(s.add(3))
        s.add(1); s.add(2); s.add(5)
        assertEquals("1-3,5", s.encode()); assertEquals(4, s.count)
        s.add(4); assertEquals("1-5", s.encode())
    }

    @Test fun conjuntoDecodificaEValida() {
        assertEquals(listOf(1, 2, 3, 7), IntRangeSet.decode("1-3, 7")!!.toList())
        assertNull(IntRangeSet.decode("3-1")); assertNull(IntRangeSet.decode("a")); assertNull(IntRangeSet.decode("1-2-3"))
        assertNull(IntRangeSet.decode("1-9999999"))
        assertTrue(IntRangeSet.decode("")!!.isEmpty)
        assertNotNull(IntRangeSet.decode(null))
    }
}
