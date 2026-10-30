package com.hqverse.core

data class ParsedName(
    val title: String,
    val series: String?,
    val issue: Int?,
    val volume: Int?,
    val year: Int?,
)

/**
 * Interpreta nomes de arquivo de HQs de forma tolerante.
 * Limitação conhecida: um número de 4 dígitos entre 1930 e 2099 é tratado como ano.
 */
object FileNameParser {
    private val EXT = Regex("\\.[A-Za-z0-9]{2,5}$")
    private val BRACKETS = Regex("[\\[({][^\\])}]*[\\])}]")
    private val YEAR = Regex("(?<!\\d)(19[3-9]\\d|20\\d\\d)(?!\\d)")
    private val VOLUME = Regex(
        "(?<![\\p{L}\\d])(?:volume|vol|tomo|v)\\.?\\s*0*(\\d{1,3})(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )
    private val ISSUE_KEYWORD = Regex(
        "(?:(?<![\\p{L}\\d])(?:edi[cç][aã]o|ed|issue|iss|n[ºo°]|no|num|n[uú]mero)|#)\\.?\\s*0*(\\d{1,4})(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_NUMBER = Regex("(?:^|\\s)#?0*(\\d{1,4})\\s*$")
    private val SEPARATORS = charArrayOf(' ', '-', '–', '—', ':', ',', ';', '_', '.')

    fun parse(fileName: String): ParsedName {
        var text = fileName.trim().replace(EXT, "").replace('_', ' ')
        val original = text.replace(BRACKETS, " ").replace(Regex("\\s+"), " ").trim(*SEPARATORS)

        var year: Int? = BRACKETS.findAll(text)
            .mapNotNull { YEAR.find(it.value)?.value?.toInt() }
            .firstOrNull()
        text = text.replace(BRACKETS, " ").replace('.', ' ').collapse()

        var volume: Int? = null
        VOLUME.find(text)?.let {
            volume = it.groupValues[1].toInt()
            text = text.removeRange(it.range).collapse()
        }

        var issue: Int? = null
        ISSUE_KEYWORD.find(text)?.let {
            issue = it.groupValues[1].toInt()
            text = text.removeRange(it.range).collapse()
        }

        if (year == null) {
            YEAR.findAll(text).lastOrNull()?.let {
                val rest = text.removeRange(it.range).collapse().trim(*SEPARATORS)
                if (rest.isNotEmpty()) {
                    year = it.value.toInt()
                    text = rest
                }
            }
        }

        if (issue == null) {
            TRAILING_NUMBER.find(text)?.let {
                val rest = text.removeRange(it.range).collapse().trim(*SEPARATORS)
                if (rest.isNotEmpty()) {
                    issue = it.groupValues[1].toInt()
                    text = rest
                }
            }
        }

        val series = text.collapse().trim(*SEPARATORS)
        if (series.isEmpty() || (issue == null && volume == null && year == null)) {
            return ParsedName(
                title = original.ifEmpty { fileName },
                series = null, issue = null, volume = null, year = null,
            )
        }
        val title = buildString {
            append(series)
            volume?.let { append(" Vol. ").append(it) }
            issue?.let { append(" #").append(it) }
        }
        return ParsedName(title, series, issue, volume, year)
    }

    private fun String.collapse() = replace(Regex("\\s+"), " ").trim()
}
