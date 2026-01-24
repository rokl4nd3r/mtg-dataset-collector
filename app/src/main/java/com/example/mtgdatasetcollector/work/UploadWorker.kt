package com.example.mtgdatasetcollector.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.mtgdatasetcollector.data.buildPinnedClient
import com.example.mtgdatasetcollector.data.queue.AppDatabase
import com.example.mtgdatasetcollector.data.queue.UploadJobEntity
import com.example.mtgdatasetcollector.data.queue.UploadQueueRepository
import com.example.mtgdatasetcollector.net.DatasetUploader
import com.example.mtgdatasetcollector.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.get(applicationContext).uploadJobDao()
            val repo = UploadQueueRepository(dao)
            val uploader = DatasetUploader(buildPinnedClient())

            // Process a batch to avoid running forever.
            val batchLimit = 20
            var processed = 0

            while (processed < batchLimit) {
                val next = repo.nextPending(limit = 1).firstOrNull() ?: break

                repo.markUploading(next.id)

                val res = uploader.upload(next)
                if (res.ok) {
                    repo.markUploaded(next.id)
                    cleanupFiles(next)
                } else {
                    val err = res.error ?: "upload failed"
                    repo.retryOrFail(next, err, AppConfig.MAX_UPLOAD_RETRIES)
                }

                processed++
            }

            val stillPending = dao.countByStatus(UploadJobEntity.STATUS_PENDING)
            if (stillPending > 0) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Log.e(AppConfig.DBG_TAG, "UploadWorker crash", t)
            Result.retry()
        }
    }

    private fun cleanupFiles(job: UploadJobEntity) {
        runCatching { File(job.frontPath).delete() }
        runCatching { File(job.backPath).delete() }
    }
}
