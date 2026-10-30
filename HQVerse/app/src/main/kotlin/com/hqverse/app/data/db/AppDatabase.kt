package com.hqverse.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        FolderEntity::class, ComicEntity::class, SessionEntity::class, BookmarkEntity::class,
        ComicTagEntity::class, CollectionEntity::class, CollectionItemEntity::class,
    ],
    version = 1,
    // O esquema exportado (app/schemas) é versionado no git: ele é a base dos testes de migração.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun comics(): ComicDao
    abstract fun sessions(): SessionDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun tags(): TagDao
    abstract fun collections(): CollectionDao

    companion object {
        /**
         * Migrações futuras entram aqui (ex.: `Migration(1, 2) { db -> db.execSQL("ALTER TABLE ...") }`).
         * O app NÃO usa fallbackToDestructiveMigration: ao subir `version` sem migração, a
         * compilação/execução falha em vez de apagar os dados de leitura do usuário.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hqverse.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
