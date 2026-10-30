package com.hqverse.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE uri = :uri")
    suspend fun getByUri(uri: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ComicDao {
    @Query("SELECT * FROM comics")
    fun observeAll(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics")
    suspend fun getAll(): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE id = :id")
    fun observe(id: Long): Flow<ComicEntity?>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun get(id: Long): ComicEntity?

    @Query("SELECT * FROM comics WHERE folderId = :folderId")
    suspend fun getByFolder(folderId: Long): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE folderId IS NULL")
    suspend fun getOrphans(): List<ComicEntity>

    @Query("UPDATE comics SET hidden = 1, missing = 1 WHERE folderId = :folderId")
    suspend fun orphanPrepare(folderId: Long)

    @Query("SELECT * FROM comics WHERE coverPath IS NULL AND errorCode IS NULL AND missing = 0")
    suspend fun getNeedingProcessing(): List<ComicEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(comic: ComicEntity): Long

    @Update
    suspend fun update(comic: ComicEntity)

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM comics WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    @Query("UPDATE comics SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("UPDATE comics SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE comics SET favorite = :value WHERE id = :id")
    suspend fun setFavorite(id: Long, value: Boolean)

    @Query("UPDATE comics SET wantToRead = :value WHERE id = :id")
    suspend fun setWantToRead(id: Long, value: Boolean)

    @Query("UPDATE comics SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int?)

    @Query("UPDATE comics SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String?)

    @Query("UPDATE comics SET pageCount = :pages WHERE id = :id")
    suspend fun setPageCount(id: Long, pages: Int)

    @Query("UPDATE comics SET coverPath = :path, customCoverPage = :page WHERE id = :id")
    suspend fun setCover(id: Long, path: String?, page: Int?)

    @Query("UPDATE comics SET errorCode = :code WHERE id = :id")
    suspend fun setError(id: Long, code: String?)

    @Query(
        """UPDATE comics SET currentPage = :page, readPages = :readPages, status = :status,
           manuallyCompleted = :manuallyCompleted, manuallyPaused = :manuallyPaused,
           lastReadAt = :now, startedAt = COALESCE(startedAt, :now),
           completedAt = :completedAt WHERE id = :id""",
    )
    suspend fun saveProgress(
        id: Long, page: Int, readPages: String, status: String,
        manuallyCompleted: Boolean, manuallyPaused: Boolean, now: Long, completedAt: Long?,
    )

    @Query(
        """UPDATE comics SET currentPage = 1, readPages = '', status = 'NOT_STARTED', manuallyCompleted = 0,
           manuallyPaused = 0, startedAt = NULL, lastReadAt = NULL, completedAt = NULL,
           activeSeconds = 0, sessionCount = 0 WHERE id = :id""",
    )
    suspend fun resetProgress(id: Long)

    /** "Marcar como não lida": zera o progresso mas MANTÉM tempo e histórico de sessões. */
    @Query(
        """UPDATE comics SET currentPage = 1, readPages = '', status = 'NOT_STARTED', manuallyCompleted = 0,
           manuallyPaused = 0, completedAt = NULL WHERE id = :id""",
    )
    suspend fun markUnread(id: Long)

    @Query("UPDATE comics SET activeSeconds = activeSeconds + :seconds, sessionCount = sessionCount + :sessions WHERE id = :id")
    suspend fun addReadingTime(id: Long, seconds: Long, sessions: Int)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE open = 1")
    suspend fun getOpen(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE open = 0 ORDER BY startMs")
    fun observeClosed(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE open = 0 ORDER BY startMs")
    suspend fun getAllClosed(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE comicId = :comicId AND open = 0 ORDER BY startMs DESC")
    fun observeForComic(comicId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE comicId = :comicId AND open = 0 ORDER BY startMs DESC")
    suspend fun getForComic(comicId: Long): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE comicId = :comicId")
    suspend fun deleteForComic(comicId: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE comicId = :comicId ORDER BY page, createdAt")
    fun observeForComic(comicId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY comicId, page")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE comicId = :comicId ORDER BY page, createdAt")
    suspend fun getForComic(comicId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE comicId = :comicId")
    suspend fun deleteForComic(comicId: Long)
}

@Dao
interface TagDao {
    @Query("SELECT tag FROM comic_tags WHERE comicId = :comicId ORDER BY tag COLLATE NOCASE")
    fun observeForComic(comicId: Long): Flow<List<String>>

    @Query("SELECT * FROM comic_tags")
    suspend fun getAll(): List<ComicTagEntity>

    @Query("SELECT * FROM comic_tags")
    fun observeAll(): Flow<List<ComicTagEntity>>

    @Query("SELECT tag FROM comic_tags WHERE comicId = :comicId")
    suspend fun getForComic(comicId: Long): List<String>

    @Query("SELECT DISTINCT tag FROM comic_tags ORDER BY tag COLLATE NOCASE")
    fun observeAllTags(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: ComicTagEntity)

    @Query("DELETE FROM comic_tags WHERE comicId = :comicId AND tag = :tag")
    suspend fun delete(comicId: Long, tag: String)
}

@Dao
interface CollectionDao {
    @Query("SELECT c.*, (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id) AS itemCount FROM collections c ORDER BY c.name COLLATE NOCASE")
    fun observeRows(): Flow<List<CollectionRow>>

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: Long): CollectionEntity?

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Update
    suspend fun update(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY position")
    fun observeItems(collectionId: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items ORDER BY collectionId, position")
    suspend fun getAllItems(): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items WHERE comicId = :comicId")
    fun observeItemsForComic(comicId: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items")
    fun observeAllItems(): Flow<List<CollectionItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: CollectionItemEntity)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND comicId = :comicId")
    suspend fun removeItem(collectionId: Long, comicId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM collection_items WHERE collectionId = :collectionId")
    suspend fun nextPosition(collectionId: Long): Int

    @Query("UPDATE collection_items SET position = :position WHERE collectionId = :collectionId AND comicId = :comicId")
    suspend fun setPosition(collectionId: Long, comicId: Long, position: Int)

    @Query("UPDATE collection_items SET owned = :owned WHERE collectionId = :collectionId AND comicId = :comicId")
    suspend fun setOwned(collectionId: Long, comicId: Long, owned: Boolean)
}
