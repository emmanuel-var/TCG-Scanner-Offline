package com.tcgscanner.offline.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tcgscanner.offline.TcgApp
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.scanner.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads the TensorFlow Lite model into [android.content.Context.getFilesDir]. Resumable (HTTP Range),
 * writes to a .part file and renames only after the size / checksum checks pass, so the app never sees a
 * half-written model. Progress is published through WorkManager's progress data.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as TcgApp).container
        val url = inputData.getString(KEY_URL).orEmpty().ifBlank { ModelConfig.DEFAULT_URL }
        val target = File(applicationContext.filesDir, ModelConfig.FILE_NAME)
        val part = File(applicationContext.filesDir, ModelConfig.FILE_NAME + ".part")

        try {
            var offset = if (part.isFile) part.length() else 0L
            val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()

            val outcome: Result? = container.http.client.newCall(request).execute().use { response ->
                when {
                    response.code == 416 -> { part.delete(); return@use Result.retry() }   // stale partial file
                    response.code == 429 || response.code in 500..599 -> return@use retryOrFail("HTTP ${response.code}")
                    !response.isSuccessful -> return@use Result.failure(workDataOf(KEY_ERROR to "HTTP ${response.code}"))
                }
                val body = response.body ?: return@use retryOrFail("Empty response")
                val append = response.code == 206
                if (!append) offset = 0L
                val length = body.contentLength()
                val total = if (length > 0) offset + length else -1L

                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var written = offset
                        var lastReported = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    setProgress(workDataOf(KEY_PROGRESS to percent / 100f))
                                }
                            }
                        }
                    }
                }
                null
            }
            if (outcome != null) return@withContext outcome

            if (part.length() < ModelConfig.MIN_BYTES) {
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Downloaded file is too small to be a model"))
            }
            if (ModelConfig.SHA256.isNotEmpty() && sha256(part) != ModelConfig.SHA256.lowercase()) {
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Checksum mismatch"))
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            setProgress(workDataOf(KEY_PROGRESS to 1f))
            Result.success()
        } catch (e: CancellationException) {
            throw e // keep the .part file so the next attempt resumes
        } catch (e: IOException) {
            retryOrFail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun retryOrFail(message: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to message))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3
    }
}
