package com.example.mtgdatasetcollector.data.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UploadJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(job: UploadJobEntity): Long

    @Query("""
        SELECT * FROM upload_jobs
        WHERE status = :status
        ORDER BY createdAtMs ASC
        LIMIT :limit
    """)
    fun listByStatus(status: String, limit: Int): List<UploadJobEntity>

    @Query("SELECT * FROM upload_jobs WHERE id = :id LIMIT 1")
    fun getById(id: Long): UploadJobEntity?

    @Query("""
        UPDATE upload_jobs
        SET status = :status, lastError = :lastError
        WHERE id = :id
    """)
    fun setStatus(id: Long, status: String, lastError: String? = null): Int

    @Query("""
        UPDATE upload_jobs
        SET status = :status, retries = retries + 1, lastError = :err
        WHERE id = :id
    """)
    fun bumpRetries(id: Long, status: String, err: String?): Int

    @Query("DELETE FROM upload_jobs WHERE status = :status AND createdAtMs < :olderThanMs")
    fun deleteOlderThan(status: String, olderThanMs: Long): Int

    @Query("SELECT COUNT(*) FROM upload_jobs WHERE status = :status")
    fun countByStatus(status: String): Int
}
