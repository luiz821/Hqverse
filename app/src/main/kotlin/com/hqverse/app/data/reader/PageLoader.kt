package com.hqverse.app.data.reader

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Carrega páginas sob demanda com cache LRU limitado por memória, pré-carregamento e no
 * máximo 2 decodificações simultâneas. Nunca carrega a HQ inteira.
 */
class PageLoader(
    private val pages: BitmapPages,
    private val targetWidth: Int,
    private val scope: CoroutineScope,
    cacheBytes: Int = (Runtime.getRuntime().maxMemory() / 5).toInt().coerceIn(24 shl 20, 160 shl 20),
) {
    val pageCount: Int get() = pages.pageCount
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes / 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val inFlight = ConcurrentHashMap<Int, Deferred<Bitmap>>()
    private val gate = Semaphore(2)

    fun peek(index: Int): Bitmap? = cache.get(index)

    /**
     * A carga roda no escopo do loader (não no do chamador): se quem pediu cancelar (ex.: rolou
     * a página), quem também espera o mesmo resultado não é afetado.
     */
    suspend fun get(index: Int): Bitmap {
        cache.get(index)?.let { return it }
        val job = inFlight.getOrPut(index) {
            scope.async(Dispatchers.IO) {
                gate.withPermit { pages.load(index, targetWidth) }.also { cache.put(index, it) }
            }.also { d -> d.invokeOnCompletion { inFlight.remove(index) } }
        }
        return job.await()
    }

    /** Pré-carrega em segundo plano; erros são ignorados (aparecem quando a página for aberta). */
    fun prefetch(indices: Iterable<Int>) {
        for (i in indices) {
            if (i !in 0 until pageCount || cache.get(i) != null || inFlight.containsKey(i)) continue
            scope.launch { runCatching { get(i) } }
        }
    }

    /** Libera da memória as páginas distantes da posição atual. */
    fun trimAround(center: Int, keep: Int = 3) {
        for (key in cache.snapshot().keys) if (kotlin.math.abs(key - center) > keep) cache.remove(key)
    }

    fun close() {
        cache.evictAll()
        pages.close()
    }
}
