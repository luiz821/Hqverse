package com.hqverse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private fun row(id: Long, title: String, series: String? = null, issue: Int? = null, year: Int? = null, status: ReadStatus = ReadStatus.NOT_STARTED,
                    pages: Int = 20, added: Long = id, last: Long? = null, fav: Boolean = false, fmt: ComicFormat = ComicFormat.CBZ,
                    author: String? = null, hidden: Boolean = false, error: Boolean = false, missing: Boolean = false, tags: Set<String> = emptySet(),
                    collections: Set<Long> = emptySet(), volume: Int? = null) =
        ComicRow(id, title, series, issue, volume, year, author, null, "$title.cbz", fmt, status, 0, pages, added, last, fav, false, hidden, missing, error, 1, tags, collections)

    private val data = listOf(
        row(1, "Batman #10", "Batman", 10, 2016, author = "Tom King", tags = setOf("favorita")),
        row(2, "Batman #2", "Batman", 2, 2016, status = ReadStatus.COMPLETED, last = 500),
        row(3, "Homem-Aranha #1", "Homem-Aranha", 1, null, fav = true, fmt = ComicFormat.PDF, last = 900, status = ReadStatus.IN_PROGRESS),
        row(4, "Saga", pages = 150, hidden = true),
        row(5, "Épico Ação", error = true, fmt = ComicFormat.CBR),
        row(6, "Antiga", missing = true),
    )

    @Test fun buscaIgnoraAcentoECaixaEUsaVariosTermos() {
        assertEquals(listOf(5L), LibraryQuery.filter(data, LibraryFilter(query = "epico acao", showMissing = true)).map { it.id })
        assertEquals(listOf(1L), LibraryQuery.filter(data, LibraryFilter(query = "batman king")).map { it.id })
        assertEquals(listOf(1L), LibraryQuery.filter(data, LibraryFilter(query = "FAVORITA")).map { it.id }) // por tag
        assertEquals(listOf(1L, 2L), LibraryQuery.filter(data, LibraryFilter(query = "2016")).map { it.id }.sorted())
        assertEquals(listOf(2L), LibraryQuery.filter(data, LibraryFilter(query = "batman #2")).map { it.id })
    }

    @Test fun ocultasEAusentesSoAparecemSePedido() {
        val ids = LibraryQuery.filter(data, LibraryFilter()).map { it.id }
        assertFalse(4L in ids); assertFalse(6L in ids)
        assertTrue(4L in LibraryQuery.filter(data, LibraryFilter(showHidden = true)).map { it.id })
    }

    @Test fun filtros() {
        assertEquals(listOf(3L), LibraryQuery.filter(data, LibraryFilter(onlyFavorites = true)).map { it.id })
        assertEquals(listOf(3L), LibraryQuery.filter(data, LibraryFilter(formats = setOf(ComicFormat.PDF))).map { it.id })
        assertEquals(listOf(2L), LibraryQuery.filter(data, LibraryFilter(statuses = setOf(ReadStatus.COMPLETED))).map { it.id })
        assertEquals(listOf(5L), LibraryQuery.filter(data, LibraryFilter(onlyErrors = true)).map { it.id })
        assertEquals(listOf(1L, 2L), LibraryQuery.filter(data, LibraryFilter(series = "batman")).map { it.id }.sorted())
        assertEquals(listOf(1L), LibraryQuery.filter(data, LibraryFilter(tag = "Favorita")).map { it.id })
    }

    @Test fun filtroPorColecao() {
        val d = listOf(row(1, "A", collections = setOf(7)), row(2, "B"))
        assertEquals(listOf(1L), LibraryQuery.filter(d, LibraryFilter(collectionId = 7)).map { it.id })
    }

    @Test fun ordenacaoPorNomeEhNaturalEIgnoraAcento() {
        val names = LibraryQuery.sort(LibraryQuery.filter(data, LibraryFilter()), SortKey.NAME, false).map { it.title }
        assertEquals(listOf("Batman #2", "Batman #10", "Épico Ação", "Homem-Aranha #1"), names)
        assertEquals(names.reversed(), LibraryQuery.sort(LibraryQuery.filter(data, LibraryFilter()), SortKey.NAME, true).map { it.title })
    }

    @Test fun ordenacaoPorEdicaoAgrupaPorSerie() {
        val sorted = LibraryQuery.sort(LibraryQuery.filter(data, LibraryFilter()), SortKey.ISSUE, false).map { it.id }
        assertEquals(listOf(2L, 1L), sorted.filter { it in listOf(1L, 2L) })
    }

    @Test fun semAnoVaiParaOFimNasDuasDirecoes() {
        val base = LibraryQuery.filter(data, LibraryFilter(statuses = emptySet()))
        assertEquals(2016, LibraryQuery.sort(base, SortKey.YEAR, false).first().year)
        assertEquals(2016, LibraryQuery.sort(base, SortKey.YEAR, true).first().year)
        assertEquals(null, LibraryQuery.sort(base, SortKey.YEAR, true).last().year)
    }

    @Test fun ultimaLeituraENuncaLidasPorUltimo() {
        val d = LibraryQuery.sort(LibraryQuery.filter(data, LibraryFilter()), SortKey.LAST_READ, true).map { it.id }
        assertEquals(listOf(3L, 2L), d.take(2))
    }

    @Test fun ordenaPorPaginasEData() {
        val d = LibraryQuery.sort(LibraryQuery.filter(data, LibraryFilter(showHidden = true)), SortKey.PAGES, true)
        assertEquals(4L, d.first().id)
        assertEquals(6L, LibraryQuery.sort(data, SortKey.ADDED, true).first().id)
    }

    @Test fun agrupamentoPorSerieOrdenaEdicoes() {
        val g = LibraryQuery.groupBySeries(data)
        assertEquals(listOf("Batman", "Homem-Aranha"), g.map { it.name })
        assertEquals(listOf(2L, 1L), g.first().comics.map { it.id })
        assertEquals(1, g.first().completed)
    }

    @Test fun serieComVolumesOrdenaPorVolumeAntes() {
        val d = listOf(row(1, "S v2 #1", "S", 1, volume = 2), row(2, "S v1 #5", "S", 5, volume = 1))
        assertEquals(listOf(2L, 1L), LibraryQuery.groupBySeries(d).single().comics.map { it.id })
    }

    @Test fun continuarLendoSoMostraEmAndamentoRecentesPrimeiro() {
        val d = listOf(
            row(1, "A", status = ReadStatus.IN_PROGRESS, last = 10), row(2, "B", status = ReadStatus.PAUSED, last = 20),
            row(3, "C", status = ReadStatus.COMPLETED, last = 30), row(4, "D", status = ReadStatus.IN_PROGRESS, last = 40, error = true),
        )
        assertEquals(listOf(2L, 1L), LibraryQuery.continueReading(d).map { it.id })
    }

    @Test fun bibliotecaGrandeBuscaRapida() {
        val big = (1..40_000).map { row(it.toLong(), "Titulo $it", "Serie ${it % 500}", it % 90) }
        val t0 = System.nanoTime()
        val r = LibraryQuery.sort(LibraryQuery.filter(big, LibraryFilter(query = "serie 42 titulo")), SortKey.NAME, false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.isNotEmpty()); assertTrue("demorou $ms ms", ms < 3000)
    }
}
