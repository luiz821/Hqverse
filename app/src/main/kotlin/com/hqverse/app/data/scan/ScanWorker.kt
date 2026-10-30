package com.hqverse.app.data.scan

import android.content.Context
import androidx.work.*
import com.hqverse.app.HQVerseApp
import java.util.concurrent.TimeUnit

/** Varredura persistente: sobrevive ao app ser fechado e é retomada pelo WorkManager. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HQVerseApp).container
        val ids = inputData.getLongArray(KEY_FOLDERS)?.toList()
        return try {
            container.indexer.scan(ids)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            container.indexer.coordinator.finish("Falha na varredura: ${e.message ?: "erro desconhecido"}")
            Result.failure()
        }
    }

    companion object {
        private const val NAME = "library-scan"
        private const val KEY_FOLDERS = "folders"

        fun enqueue(context: Context, folderIds: List<Long>? = null) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_FOLDERS to (folderIds?.toLongArray() ?: LongArray(0))))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
