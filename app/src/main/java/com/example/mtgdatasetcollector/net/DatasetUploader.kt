package com.example.mtgdatasetcollector.net

import com.example.mtgdatasetcollector.data.queue.UploadJobEntity
import com.example.mtgdatasetcollector.util.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class UploadResult(
    val ok: Boolean,
    val code: Int? = null,
    val error: String? = null
)

class DatasetUploader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun upload(job: UploadJobEntity): UploadResult {
        val front = File(job.frontPath)
        val back = File(job.backPath)

        if (!front.exists()) return UploadResult(false, error = "front file missing: ${job.frontPath}")
        if (!back.exists()) return UploadResult(false, error = "back file missing: ${job.backPath}")

        val jpeg = "image/jpeg".toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("job_id", job.id.toString())
            .addFormDataPart("grade", job.grade)
            .addFormDataPart("front", front.name, front.asRequestBody(jpeg))
            .addFormDataPart("back", back.name, back.asRequestBody(jpeg))
            .build()

        val reqBuilder = Request.Builder()
            .url(AppConfig.DATASET_UPLOAD_URL)
            .post(body)

        val token = AppConfig.BEARER_TOKEN.trim()
        if (token.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.isSuccessful) return UploadResult(true, code = resp.code)

            val msg = resp.body?.string()?.take(400) ?: ""
            return UploadResult(false, code = resp.code, error = "HTTP ${resp.code} $msg")
        }
    }
}
