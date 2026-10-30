package com.hqverse.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Pasta monitorada (raiz autorizada pelo seletor de pastas do sistema). */
@Entity(tableName = "folders", indices = [Index(value = ["uri"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val includeSubfolders: Boolean = true,
    val addedAt: Long,
    val lastScanAt: Long? = null,
    /** false = pasta pausada: não entra nas varreduras automáticas. */
    val enabled: Boolean = true,
)

/**
 * Uma HQ. Os dados de progresso ficam na mesma linha para consultas rápidas
 * ("continuar lendo", filtros). Nunca guarda imagens de páginas: só o caminho da capa em cache.
 */
@Entity(
    tableName = "comics",
    foreignKeys = [ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.SET_NULL)],
    indices = [
        Index(value = ["uri"], unique = true),
        Index("folderId"),
        Index("fingerprint"),
        Index("series"),
        Index("status"),
        Index("lastReadAt"),
    ],
)
data class ComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** null = pasta removida com "preservar dados": o registro fica oculto e é readotado se o arquivo reaparecer. */
    val folderId: Long?,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Nome de [com.hqverse.core.ComicFormat]. */
    val format: String,
    /** "nome|tamanho" em minúsculas; usado para reassociar após backup/movimentação. */
    val fingerprint: String,
    val title: String,
    val series: String? = null,
    val issue: Int? = null,
    val volume: Int? = null,
    val year: Int? = null,
    val author: String? = null,
    val publisher: String? = null,
    /** true se o usuário editou os metadados à mão (a reindexação não os sobrescreve). */
    val metadataEdited: Boolean = false,
    val pageCount: Int = 0,
    val coverPath: String? = null,
    val customCoverPage: Int? = null,
    val addedAt: Long,
    /** Oculta da biblioteca (mantém histórico). */
    val hidden: Boolean = false,
    /** Arquivo não encontrado na última varredura. */
    val missing: Boolean = false,
    /** Nome de [com.hqverse.core.ReadFailure] se o arquivo não pôde ser lido. */
    val errorCode: String? = null,
    val favorite: Boolean = false,
    val wantToRead: Boolean = false,
    val rating: Int? = null,
    val notes: String? = null,
    // ---- progresso ----
    /** Página atual, base 1. */
    val currentPage: Int = 1,
    /** Páginas únicas já vistas, em "1-12,15". */
    val readPages: String = "",
    /** Nome de [com.hqverse.core.ReadStatus]. */
    val status: String = "NOT_STARTED",
    val manuallyCompleted: Boolean = false,
    val manuallyPaused: Boolean = false,
    val startedAt: Long? = null,
    val lastReadAt: Long? = null,
    val completedAt: Long? = null,
    val activeSeconds: Long = 0,
    val sessionCount: Int = 0,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(ComicEntity::class, ["id"], ["comicId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("comicId"), Index("startMs")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val newPages: Int,
    val pageVisits: Int,
    /** true enquanto a sessão está aberta (checkpoint); fechada na próxima abertura se o app morrer. */
    val open: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(ComicEntity::class, ["id"], ["comicId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("comicId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val page: Int,
    val name: String? = null,
    val createdAt: Long,
    val color: String? = null,
    val note: String? = null,
)

@Entity(
    tableName = "comic_tags",
    primaryKeys = ["comicId", "tag"],
    foreignKeys = [ForeignKey(ComicEntity::class, ["id"], ["comicId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tag")],
)
data class ComicTagEntity(val comicId: Long, val tag: String)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Sequência esperada informada pelo usuário ("1-12"); null = não informada. */
    val expectedIssues: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "comicId"],
    foreignKeys = [
        ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ComicEntity::class, ["id"], ["comicId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("comicId")],
)
data class CollectionItemEntity(
    val collectionId: Long,
    val comicId: Long,
    /** Ordem de leitura personalizada. */
    val position: Int,
    /** O usuário possui esta edição (true) ou só a lista como desejo (false). */
    val owned: Boolean = true,
)

data class CollectionRow(@Embedded val collection: CollectionEntity, val itemCount: Int)
