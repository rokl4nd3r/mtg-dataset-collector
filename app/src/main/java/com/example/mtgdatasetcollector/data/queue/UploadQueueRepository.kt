package com.example.mtgdatasetcollector.data.queue

class UploadQueueRepository(
    private val dao: UploadJobDao
) {
    // Compatível com o app atual: considera grade como FINAL e replica pros lados
    fun enqueue(grade: String, frontPath: String, backPath: String): Long {
        return enqueue(
            frontGrade = grade,
            backGrade = grade,
            finalGrade = grade,
            frontPath = frontPath,
            backPath = backPath
        )
    }

    // NOVO: enqueue com grades por lado + final (final já vem calculado)
    fun enqueue(frontGrade: String, backGrade: String, finalGrade: String, frontPath: String, backPath: String): Long {
        val job = UploadJobEntity(
            grade = finalGrade,
            frontGrade = frontGrade,
            backGrade = backGrade,
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