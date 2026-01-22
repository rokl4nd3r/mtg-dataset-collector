package com.example.mtgdatasetcollector.data.queue

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_jobs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAtMs"])
    ]
)
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val createdAtMs: Long = System.currentTimeMillis(),

    // PENDING | UPLOADING | UPLOADED | FAILED
    val status: String = STATUS_PENDING,

    // NM/SP/MP/HP/D
    val grade: String,

    val frontPath: String,
    val backPath: String,

    val retries: Int = 0,
    val lastError: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_UPLOADED = "UPLOADED"
        const val STATUS_FAILED = "FAILED"
    }
}
