package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameParserTest {
    @Test fun batmanSimples() {
        val p = FileNameParser.parse("Batman 001.cbr")
        assertEquals("Batman", p.series); assertEquals(1, p.issue); assertNull(p.volume); assertNull(p.year)
        assertEquals("Batman #1", p.title)
    }

    @Test fun edicaoPorExtenso() {
        val p = FileNameParser.parse("Homem-Aranha - Edição 15.pdf")
        assertEquals("Homem-Aranha", p.series); assertEquals(15, p.issue)
    }

    @Test fun edicaoMaiusculaESemAcento() {
        assertEquals(7, FileNameParser.parse("SUPERMAN EDIÇÃO 7.cbz").issue)
        assertEquals(7, FileNameParser.parse("Superman Edicao 07.cbz").issue)
    }

    @Test fun volume() {
        val p = FileNameParser.parse("Saga Volume 01.cbz")
        assertEquals("Saga", p.series); assertEquals(1, p.volume); assertNull(p.issue)
        assertEquals("Saga Vol. 1", p.title)
    }

    @Test fun anoEEdicao() {
        val p = FileNameParser.parse("X-Men 2024 003.cbr")
        assertEquals("X-Men", p.series); assertEquals(2024, p.year); assertEquals(3, p.issue)
    }

    @Test fun anoEntreParenteses() {
        val p = FileNameParser.parse("Batman 012 (2016) (Digital).cbz")
        assertEquals("Batman", p.series); assertEquals(12, p.issue); assertEquals(2016, p.year)
    }

    @Test fun cerquilha() {
        assertEquals(42, FileNameParser.parse("Spawn #42.cbr").issue)
    }

    @Test fun sublinhadosEPontos() {
        val p = FileNameParser.parse("The_Walking_Dead_Vol.2_017.cbz")
        assertEquals("The Walking Dead", p.series); assertEquals(2, p.volume); assertEquals(17, p.issue)
    }

    @Test fun apenasAnoNaoViraEdicao() {
        val p = FileNameParser.parse("Watchmen 1986.pdf")
        assertEquals("Watchmen", p.series); assertEquals(1986, p.year); assertNull(p.issue)
    }

    @Test fun semPadraoMantemNome() {
        val p = FileNameParser.parse("Maus.pdf")
        assertEquals("Maus", p.title); assertNull(p.series); assertNull(p.issue)
    }

    @Test fun hifenInternoNaoSeparaNumero() {
        val p = FileNameParser.parse("Catch-22.pdf")
        assertNull(p.issue); assertEquals("Catch-22", p.title)
    }

    @Test fun somenteNumeroNaoFicaVazio() {
        val p = FileNameParser.parse("001.cbz")
        assertEquals("001", p.title); assertNull(p.issue)
    }

    @Test fun nomeVazioNaoQuebra() {
        FileNameParser.parse(".cbz"); FileNameParser.parse("")
    }
}
