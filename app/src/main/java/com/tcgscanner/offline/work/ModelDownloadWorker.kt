package com.tcgscanner.offline.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tcgscanner.offline.TcgApp
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.scanner.EnginePack
import com.tcgscanner.offline.scanner.EnginePacks
import com.tcgscanner.offline.scanner.PackFile
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
 * Downloads every file of one [EnginePack] into Context.filesDir. Each file is resumable (HTTP Range), written to
 * `<name>.part` and renamed only after the size / checksum checks pass, so the app never sees a half-written model.
 * Progress (0..1 over the whole pack) is published through WorkManager's progress data.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as TcgApp).container
        val pack = EnginePack.entries.firstOrNull { it.id == inputData.getString(KEY_PACK) }
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Unknown pack"))
        val base = inputData.getString(KEY_BASE_URL).orEmpty()
        val files = EnginePacks.files(pack)
        val dir = applicationContext.filesDir

        try {
            files.forEachIndexed { index, file ->
                val target = File(dir, file.name)
                if (target.isFile && target.length() >= file.minBytes) {
                    setProgress(workDataOf(KEY_PROGRESS to (index + 1f) / files.size))
                    return@forEachIndexed
                }
                val outcome = downloadFile(container.http, EnginePacks.urlFor(base, file), file, target) { fraction ->
                    setProgress(workDataOf(KEY_PROGRESS to (index + fraction) / files.size))
                }
                if (outcome != null) return@withContext outcome
            }
            setProgress(workDataOf(KEY_PROGRESS to 1f))
            Result.success()
        } catch (e: CancellationException) {
            throw e // .part files are kept so the next attempt resumes
        } catch (e: IOException) {
            retryOrFail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** @return null on success, otherwise the Result the worker must return. */
    private suspend fun downloadFile(
        http: Http,
        url: String,
        file: PackFile,
        target: File,
        onProgress: suspend (Float) -> Unit
    ): Result? {
        val part = File(target.path + ".part")
        var offset = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        val outcome: Result? = http.client.newCall(request).execute().use { response ->
            when {
                response.code == 416 -> { part.delete(); return@use Result.retry() }   // stale partial file
                response.code == 429 || response.code in 500..599 -> return@use retryOrFail("HTTP ${response.code} for ${file.name}")
                !response.isSuccessful -> return@use Result.failure(workDataOf(KEY_ERROR to "HTTP ${response.code} for ${file.name}"))
            }
            val body = response.body ?: return@use retryOrFail("Empty response for ${file.name}")
            val append = response.code == 206
            if (!append) offset = 0L
            val length = body.contentLength()
            val total = if (length > 0) offset + length else -1L

            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = offset
                    var lastPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent / 100f) }
                        }
                    }
                }
            }
            null
        }
        if (outcome != null) return outcome

        if (part.length() < file.minBytes) {
            part.delete()
            return Result.failure(workDataOf(KEY_ERROR to "${file.name} is too small to be valid"))
        }
        if (file.sha256.isNotEmpty() && sha256(part) != file.sha256.lowercase()) {
            part.delete()
            return Result.failure(workDataOf(KEY_ERROR to "Checksum mismatch for ${file.name}"))
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return null
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
        const val KEY_PACK = "pack"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3
    }
}
