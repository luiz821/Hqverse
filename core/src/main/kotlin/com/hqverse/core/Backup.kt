package com.hqverse.core

/** Identifica uma HQ entre instalações: nome do arquivo + tamanho (não depende de URI). */
object Fingerprint {
    fun of(fileName: String, sizeBytes: Long): String = "${fileName.trim().lowercase()}|$sizeBytes"
}

data class BackupSession(
    val startMs: Long, val endMs: Long, val activeSeconds: Long, val newPages: Int, val pageVisits: Int,
)

data class BackupBookmark(
    val page: Int, val name: String?, val createdAt: Long, val color: String?, val note: String?,
)

data class BackupComic(
    val key: String,
    val fileName: String,
    val sizeBytes: Long,
    val title: String,
    val series: String?,
    val issue: Int?,
    val volume: Int?,
    val year: Int?,
    val author: String?,
    val publisher: String?,
    val favorite: Boolean,
    val wantToRead: Boolean,
    val rating: Int?,
    val currentPage: Int,
    val totalPages: Int,
    val readPages: String,
    val status: String,
    val startedAt: Long?,
    val lastReadAt: Long?,
    val completedAt: Long?,
    val activeSeconds: Long,
    val sessionCount: Int,
    val notes: String?,
    val tags: List<String>,
    val sessions: List<BackupSession>,
    val bookmarks: List<BackupBookmark>,
)

data class BackupCollection(
    val name: String,
    val expectedIssues: String?,
    /** Chaves das HQs, na ordem de leitura personalizada. */
    val comicKeys: List<String>,
)

data class BackupData(
    val version: Int,
    val createdAt: Long,
    val comics: List<BackupComic>,
    val collections: List<BackupCollection>,
    val settings: Map<String, String>,
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val FORMAT_ID = "hqverse-backup"
    }
}

class BackupFormatException(message: String) : Exception(message)

/** Codifica e valida o backup JSON. Nunca inclui os arquivos das HQs. */
object BackupCodec {
    fun encode(data: BackupData, pretty: Boolean = true): String {
        val root = linkedMapOf<String, Any?>(
            "format" to BackupData.FORMAT_ID,
            "version" to data.version,
            "createdAt" to data.createdAt,
            "comics" to data.comics.map { c ->
                linkedMapOf<String, Any?>(
                    "key" to c.key, "fileName" to c.fileName, "sizeBytes" to c.sizeBytes,
                    "title" to c.title, "series" to c.series, "issue" to c.issue, "volume" to c.volume,
                    "year" to c.year, "author" to c.author, "publisher" to c.publisher,
                    "favorite" to c.favorite, "wantToRead" to c.wantToRead, "rating" to c.rating,
                    "currentPage" to c.currentPage, "totalPages" to c.totalPages, "readPages" to c.readPages,
                    "status" to c.status, "startedAt" to c.startedAt, "lastReadAt" to c.lastReadAt,
                    "completedAt" to c.completedAt, "activeSeconds" to c.activeSeconds,
                    "sessionCount" to c.sessionCount, "notes" to c.notes, "tags" to c.tags,
                    "sessions" to c.sessions.map {
                        mapOf("startMs" to it.startMs, "endMs" to it.endMs, "activeSeconds" to it.activeSeconds,
                            "newPages" to it.newPages, "pageVisits" to it.pageVisits)
                    },
                    "bookmarks" to c.bookmarks.map {
                        mapOf("page" to it.page, "name" to it.name, "createdAt" to it.createdAt,
                            "color" to it.color, "note" to it.note)
                    },
                )
            },
            "collections" to data.collections.map {
                mapOf("name" to it.name, "expectedIssues" to it.expectedIssues, "comicKeys" to it.comicKeys)
            },
            "settings" to data.settings,
        )
        return MiniJson.stringify(root, pretty)
    }

    fun decode(text: String): BackupData {
        val root = try { MiniJson.parse(text) } catch (e: JsonException) {
            throw BackupFormatException("JSON inválido: ${e.message}")
        }
        val o = root as? Map<*, *> ?: throw BackupFormatException("O backup deve ser um objeto JSON")
        if (o["format"] != BackupData.FORMAT_ID) throw BackupFormatException("Este arquivo não é um backup do HQVerse")
        val version = o.int("version") ?: throw BackupFormatException("Versão ausente")
        if (version < 1 || version > BackupData.CURRENT_VERSION) {
            throw BackupFormatException("Versão de backup não suportada: $version")
        }
        val comics = o.list("comics").map { parseComic(it) }
        val keys = comics.map { it.key }
        if (keys.toSet().size != keys.size) throw BackupFormatException("Backup contém HQs duplicadas")
        val collections = o.list("collections").map { c ->
            val m = c as? Map<*, *> ?: throw BackupFormatException("Coleção inválida")
            val name = m.str("name")?.takeIf { it.isNotBlank() } ?: throw BackupFormatException("Coleção sem nome")
            val spec = m.str("expectedIssues")
            if (spec != null && IntRangeSet.decode(spec) == null) throw BackupFormatException("Sequência inválida na coleção '$name'")
            BackupCollection(name, spec, m.list("comicKeys").map { it as? String ?: throw BackupFormatException("Chave inválida") })
        }
        val settings = (o["settings"] as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to (it.value?.toString() ?: "") } ?: emptyMap()
        return BackupData(version, o.long("createdAt") ?: 0L, comics, collections, settings)
    }

    private fun parseComic(raw: Any?): BackupComic {
        val m = raw as? Map<*, *> ?: throw BackupFormatException("HQ inválida no backup")
        val fileName = m.str("fileName") ?: throw BackupFormatException("HQ sem nome de arquivo")
        val size = m.long("sizeBytes") ?: throw BackupFormatException("HQ sem tamanho")
        val key = m.str("key") ?: Fingerprint.of(fileName, size)
        val total = (m.int("totalPages") ?: 0).also { if (it < 0 || it > 100_000) throw BackupFormatException("Total de páginas inválido") }
        val current = (m.int("currentPage") ?: 0).also { if (it < 0 || it > 100_000) throw BackupFormatException("Página atual inválida") }
        val readPages = m.str("readPages") ?: ""
        if (IntRangeSet.decode(readPages) == null) throw BackupFormatException("Páginas lidas inválidas em '$fileName'")
        val status = m.str("status") ?: "NOT_STARTED"
        if (ReadStatus.values().none { it.name == status }) throw BackupFormatException("Status inválido em '$fileName'")
        val rating = m.int("rating")
        if (rating != null && rating !in 1..5) throw BackupFormatException("Avaliação inválida em '$fileName'")
        return BackupComic(
            key = key, fileName = fileName, sizeBytes = size,
            title = m.str("title") ?: fileName, series = m.str("series"), issue = m.int("issue"),
            volume = m.int("volume"), year = m.int("year"), author = m.str("author"), publisher = m.str("publisher"),
            favorite = m["favorite"] == true, wantToRead = m["wantToRead"] == true, rating = rating,
            currentPage = current, totalPages = total, readPages = readPages, status = status,
            startedAt = m.long("startedAt"), lastReadAt = m.long("lastReadAt"), completedAt = m.long("completedAt"),
            activeSeconds = (m.long("activeSeconds") ?: 0L).coerceAtLeast(0),
            sessionCount = (m.int("sessionCount") ?: 0).coerceAtLeast(0),
            notes = m.str("notes"),
            tags = m.list("tags").mapNotNull { it as? String },
            sessions = m.list("sessions").map {
                val s = it as? Map<*, *> ?: throw BackupFormatException("Sessão inválida")
                BackupSession(
                    s.long("startMs") ?: throw BackupFormatException("Sessão sem início"),
                    s.long("endMs") ?: 0L, (s.long("activeSeconds") ?: 0L).coerceAtLeast(0),
                    (s.int("newPages") ?: 0).coerceAtLeast(0), (s.int("pageVisits") ?: 0).coerceAtLeast(0),
                )
            },
            bookmarks = m.list("bookmarks").map {
                val b = it as? Map<*, *> ?: throw BackupFormatException("Marcador inválido")
                BackupBookmark(
                    (b.int("page") ?: throw BackupFormatException("Marcador sem página")).also { p -> if (p < 0) throw BackupFormatException("Página de marcador inválida") },
                    b.str("name"), b.long("createdAt") ?: 0L, b.str("color"), b.str("note"),
                )
            },
        )
    }

    private fun Map<*, *>.str(k: String): String? = this[k] as? String
    private fun Map<*, *>.long(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<*, *>.int(k: String): Int? = (this[k] as? Number)?.toLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    private fun Map<*, *>.list(k: String): List<Any?> = (this[k] as? List<*>) ?: emptyList()
}

/** Reassocia registros do backup aos arquivos locais encontrados. */
object BackupMatcher {
    data class LocalFile(val id: Long, val fileName: String, val sizeBytes: Long)

    /** Retorna backupKey -> id local. Só associa quando o nome+tamanho correspondem de forma única. */
    fun match(backup: List<BackupComic>, local: List<LocalFile>): Map<String, Long> {
        val byKey = local.groupBy { Fingerprint.of(it.fileName, it.sizeBytes) }
        val result = LinkedHashMap<String, Long>()
        for (c in backup) {
            val candidates = byKey[Fingerprint.of(c.fileName, c.sizeBytes)] ?: continue
            if (candidates.size == 1) result[c.key] = candidates.single().id
        }
        return result
    }
}
