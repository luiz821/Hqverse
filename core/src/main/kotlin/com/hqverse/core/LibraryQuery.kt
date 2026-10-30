package com.hqverse.core

import java.text.Normalizer

enum class SortKey(val label: String) {
    NAME("Nome"), ADDED("Data de adição"), LAST_READ("Última leitura"), PROGRESS("Progresso"),
    ISSUE("Número da edição"), PAGES("Quantidade de páginas"), YEAR("Ano"),
}

/** Visão mínima de uma HQ para busca, filtro e ordenação (independente de banco de dados). */
data class ComicRow(
    val id: Long,
    val title: String,
    val series: String? = null,
    val issue: Int? = null,
    val volume: Int? = null,
    val year: Int? = null,
    val author: String? = null,
    val publisher: String? = null,
    val fileName: String = "",
    val format: ComicFormat? = null,
    val status: ReadStatus = ReadStatus.NOT_STARTED,
    val progressPercent: Int = 0,
    val pageCount: Int = 0,
    val addedAt: Long = 0,
    val lastReadAt: Long? = null,
    val favorite: Boolean = false,
    val wantToRead: Boolean = false,
    val hidden: Boolean = false,
    val missing: Boolean = false,
    val hasError: Boolean = false,
    val folderId: Long = 0,
    val tags: Set<String> = emptySet(),
    val collectionIds: Set<Long> = emptySet(),
)

data class LibraryFilter(
    val query: String = "",
    val formats: Set<ComicFormat> = emptySet(),
    val statuses: Set<ReadStatus> = emptySet(),
    val onlyFavorites: Boolean = false,
    val onlyWantToRead: Boolean = false,
    val series: String? = null,
    val collectionId: Long? = null,
    val folderId: Long? = null,
    val tag: String? = null,
    /** Mostra SOMENTE arquivos com erro de leitura. */
    val onlyErrors: Boolean = false,
    val showHidden: Boolean = false,
    val showMissing: Boolean = false,
)

data class SeriesGroup(
    val name: String,
    val comics: List<ComicRow>,
    val totalPages: Long,
    val completed: Int,
)

object LibraryQuery {
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun haystack(r: ComicRow): String = buildString {
        append(r.title).append(' ')
        r.series?.let { append(it).append(' ') }
        r.issue?.let { append('#').append(it).append(' ').append(it).append(' ') }
        r.volume?.let { append("vol ").append(it).append(' ') }
        r.year?.let { append(it).append(' ') }
        r.author?.let { append(it).append(' ') }
        r.publisher?.let { append(it).append(' ') }
        append(r.fileName).append(' ')
        r.tags.forEach { append(it).append(' ') }
    }.let(::fold)

    /** Todos os termos digitados precisam aparecer (sem diferenciar acentos/maiúsculas). */
    fun matches(r: ComicRow, query: String): Boolean {
        val terms = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val h = haystack(r)
        return terms.all { t ->
            val n = t.removePrefix("#")
            // "#2" significa exatamente a edição 2 (não deve casar com "2016" nem com "#10")
            if (t.length > 1 && t.startsWith("#") && n.all { it.isDigit() }) r.issue == n.toIntOrNull() else h.contains(t)
        }
    }

    fun filter(rows: List<ComicRow>, f: LibraryFilter): List<ComicRow> = rows.filter { r ->
        (f.showHidden || !r.hidden) &&
            (f.showMissing || !r.missing) &&
            (!f.onlyErrors || r.hasError) &&
            (f.formats.isEmpty() || r.format in f.formats) &&
            (f.statuses.isEmpty() || r.status in f.statuses) &&
            (!f.onlyFavorites || r.favorite) &&
            (!f.onlyWantToRead || r.wantToRead) &&
            (f.series == null || r.series.equals(f.series, ignoreCase = true)) &&
            (f.collectionId == null || f.collectionId in r.collectionIds) &&
            (f.folderId == null || r.folderId == f.folderId) &&
            (f.tag == null || r.tags.any { it.equals(f.tag, ignoreCase = true) }) &&
            matches(r, f.query)
    }

    private fun nameCmp(a: ComicRow, b: ComicRow) = NaturalOrder.compare(fold(a.title), fold(b.title))

    /** Itens sem valor (ano, edição, leitura) vão sempre para o fim, em qualquer direção. */
    fun sort(rows: List<ComicRow>, key: SortKey, descending: Boolean): List<ComicRow> {
        fun <T : Comparable<T>> byNullable(sel: (ComicRow) -> T?): Comparator<ComicRow> = Comparator { a, b ->
            val x = sel(a); val y = sel(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> if (descending) y.compareTo(x) else x.compareTo(y)
            }
        }
        fun <T : Comparable<T>> by(sel: (ComicRow) -> T): Comparator<ComicRow> =
            Comparator { a, b -> if (descending) sel(b).compareTo(sel(a)) else sel(a).compareTo(sel(b)) }
        val primary: Comparator<ComicRow> = when (key) {
            SortKey.NAME -> Comparator { a, b -> if (descending) nameCmp(b, a) else nameCmp(a, b) }
            SortKey.ADDED -> by { it.addedAt }
            SortKey.LAST_READ -> byNullable { it.lastReadAt }
            SortKey.PROGRESS -> by { it.progressPercent }
            SortKey.ISSUE -> Comparator { a, b ->
                val s = NaturalOrder.compare(fold(a.series ?: a.title), fold(b.series ?: b.title))
                if (s != 0) (if (descending) -s else s) else byNullable<Int> { it.issue }.compare(a, b)
            }
            SortKey.PAGES -> by { it.pageCount }
            SortKey.YEAR -> byNullable { it.year }
        }
        return rows.sortedWith(primary.thenComparing { a, b -> nameCmp(a, b) }.thenBy { it.id })
    }

    /** Agrupa por série (nome sem diferenciar maiúsculas), ordenando por volume e edição. */
    fun groupBySeries(rows: List<ComicRow>): List<SeriesGroup> =
        rows.filter { !it.series.isNullOrBlank() }
            .groupBy { fold(it.series!!.trim()) }
            .map { (_, list) ->
                val ordered = list.sortedWith(
                    compareBy<ComicRow> { it.volume ?: Int.MAX_VALUE }
                        .thenBy { it.issue ?: Int.MAX_VALUE }
                        .thenComparator { a, b -> nameCmp(a, b) },
                )
                SeriesGroup(
                    name = ordered.first().series!!.trim(),
                    comics = ordered,
                    totalPages = ordered.sumOf { it.pageCount.toLong() },
                    completed = ordered.count { it.status == ReadStatus.COMPLETED },
                )
            }
            .sortedWith { a, b -> NaturalOrder.compare(fold(a.name), fold(b.name)) }

    /** "Continuar lendo": em andamento ou pausadas, mais recentes primeiro. */
    fun continueReading(rows: List<ComicRow>): List<ComicRow> =
        rows.filter { !it.hidden && !it.missing && !it.hasError && (it.status == ReadStatus.IN_PROGRESS || it.status == ReadStatus.PAUSED) }
            .sortedByDescending { it.lastReadAt ?: 0L }
}
