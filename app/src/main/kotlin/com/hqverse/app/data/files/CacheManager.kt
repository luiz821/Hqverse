package com.hqverse.app.data.files

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gerencia o cache em disco: capas (miniaturas) e cópias temporárias de arquivos compactados
 * para acesso aleatório. Tem limite de tamanho e nunca remove um arquivo em uso.
 */
class CacheManager(context: Context) {
    val coversDir: File = File(context.cacheDir, "covers").apply { mkdirs() }
    val archivesDir: File = File(context.cacheDir, "archives").apply { mkdirs() }
    private val inUse = ConcurrentHashMap.newKeySet<String>()

    fun coverFile(comicId: Long, lastModified: Long, page: Int?): File =
        File(coversDir, "c${comicId}_${lastModified}_${page ?: 0}.jpg")

    fun archiveFile(comicId: Long, lastModified: Long, size: Long): File =
        File(archivesDir, "a${comicId}_${lastModified}_$size.bin")

    fun acquire(file: File) { inUse += file.name }
    fun release(file: File) { inUse -= file.name }

    /** Remove a cópia temporária de um arquivo compactado, se não estiver em uso. */
    fun discardArchive(comicId: Long, lastModified: Long, size: Long) {
        val f = archiveFile(comicId, lastModified, size)
        if (f.name !in inUse) f.delete()
    }

    fun deleteCoversOf(comicId: Long) {
        coversDir.listFiles().orEmpty().filter { it.name.startsWith("c${comicId}_") }.forEach { it.delete() }
    }

    fun sizeBytes(): Long = listOf(coversDir, archivesDir).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Remove os arquivos mais antigos até ficar abaixo do limite. */
    fun trimTo(limitBytes: Long) {
        var total = sizeBytes()
        if (total <= limitBytes) return
        val candidates = archivesDir.listFiles().orEmpty().filter { it.name !in inUse }.sortedBy { it.lastModified() } +
            coversDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        for (f in candidates) {
            if (total <= limitBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** Apaga todo o cache que não está em uso. Capas são regeneradas sob demanda. */
    fun clear() {
        archivesDir.listFiles().orEmpty().filter { it.name !in inUse }.forEach { it.delete() }
        coversDir.listFiles().orEmpty().forEach { it.delete() }
    }
}
