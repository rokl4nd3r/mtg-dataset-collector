package com.example.mtgdatasetcollector.net

import com.example.mtgdatasetcollector.data.queue.UploadJobEntity
import com.example.mtgdatasetcollector.util.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class UploadResult(
    val ok: Boolean,
    val code: Int? = null,
    val error: String? = null
)

private fun gradeForBackend(raw: String): String {
    val g = raw.trim().uppercase()
    return when (g) {
        "NM" -> "nm"
        "SP" -> "sp"
        "MP" -> "mp"
        "HP" -> "hp"
        "D", "DAMAGED", "DAMAGE" -> "damaged"   // pasta real no servidor
        else -> g.lowercase()
    }
}

class DatasetUploader(
    private val client: OkHttpClient
) {
    fun upload(job: UploadJobEntity): UploadResult {
        val front = File(job.frontPath)
        val back = File(job.backPath)

        if (!front.exists()) return UploadResult(false, error = "front file missing: ${job.frontPath}")
        if (!back.exists()) return UploadResult(false, error = "back file missing: ${job.backPath}")

        // Alguns campos do entity podem ser String? (nullable). Faz fallback pro grade final.
        val finalGrade = gradeForBackend(job.grade)
        val frontGrade = gradeForBackend(job.frontGrade ?: job.grade)
        val backGrade  = gradeForBackend(job.backGrade  ?: job.grade)

        val metaJson = JSONObject()
            .put("device", AppConfig.DEVICE_NAME)
            .put("seq", job.id)
            // legado (backend antigo pode ler só isso)
            .put("grade", finalGrade)
            // novos campos
            .put("front_grade", frontGrade)
            .put("back_grade", backGrade)
            .put("final_grade", finalGrade)
            .put("ts", Instant.now().toString())
            .toString()

        val jpeg = "image/jpeg".toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meta", metaJson)
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
            val msg = resp.body?.string()?.take(800) ?: ""
            return UploadResult(false, code = resp.code, error = "HTTP ${resp.code} $msg")
        }
    }
}