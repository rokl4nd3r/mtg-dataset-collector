package com.example.mtgdatasetcollector.data.queue

class UploadQueueRepository(
    private val dao: UploadJobDao
) {
    fun enqueue(grade: String, frontPath: String, backPath: String): Long {
        val job = UploadJobEntity(
            grade = grade,
            frontPath = frontPath,
            backPath = backPath,
            status = UploadJobEntity.STATUS_PENDING
        )
        return dao.insert(job)
    }

    fun nextPending(limit: Int): List<UploadJobEntity> =
        dao.listByStatus(UploadJobEntity.STATUS_PENDING, limit)

    fun markUploading(id: Long) {
        dao.setStatus(id, UploadJobEntity.STATUS_UPLOADING, null)
    }

    fun markUploaded(id: Long) {
        dao.setStatus(id, UploadJobEntity.STATUS_UPLOADED, null)
    }

    fun retryOrFail(job: UploadJobEntity, err: String, maxRetries: Int) {
        val nextRetries = job.retries + 1
        val status = if (nextRetries < maxRetries) {
            UploadJobEntity.STATUS_PENDING
        } else {
            UploadJobEntity.STATUS_FAILED
        }
        dao.bumpRetries(job.id, status, err)
    }
}
