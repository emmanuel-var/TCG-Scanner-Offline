package com.tcgscanner.offline.scanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.work.ModelDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the optional visual model. [state] is derived from two things only: the file in
 * filesDir and the WorkInfo of the download job. As soon as WorkManager reports SUCCEEDED and the file is on
 * disk, [state] flips to Ready, the interpreter is loaded, and every collector (the scanner screen) updates
 * itself; nothing needs to be restarted.
 */
class ModelRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val embedder: TfliteEmbedder
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    val file = File(appContext.filesDir, ModelConfig.FILE_NAME)

    /** Bumped when the file changes outside of WorkManager (delete, failed validation). */
    private val fileTick = MutableStateFlow(0)
    private val loadError = MutableStateFlow<String?>(null)

    private fun fileReady() = file.isFile && file.length() > 0L

    val state: StateFlow<ModelState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(ModelConfig.WORK_NAME),
        fileTick,
        loadError
    ) { infos, _, loadFailure ->
        val info = infos.lastOrNull()
        val work = when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelWork.QUEUED
            WorkInfo.State.RUNNING -> ModelWork.RUNNING
            WorkInfo.State.SUCCEEDED -> ModelWork.SUCCEEDED
            WorkInfo.State.FAILED -> ModelWork.FAILED
            WorkInfo.State.CANCELLED -> ModelWork.CANCELLED
            null -> ModelWork.NONE
        }
        val progress = info?.progress?.getFloat(ModelDownloadWorker.KEY_PROGRESS, -1f)?.takeIf { it >= 0f }
        val error = loadFailure ?: info?.outputData?.getString(ModelDownloadWorker.KEY_ERROR)
        ModelStateResolver.resolve(work, progress, fileReady(), error)
    }
        .flowOn(Dispatchers.IO)
        .onEach { applyToEmbedder(it) }
        .stateIn(scope, SharingStarted.Eagerly, if (fileReady()) ModelState.Ready else ModelState.Missing)

    /** Loads the interpreter the moment the model becomes Ready; unloads it when the file goes away. */
    private fun applyToEmbedder(state: ModelState) {
        when (state) {
            ModelState.Ready -> if (!embedder.isAvailable) {
                // Present but unusable (corrupt / incompatible): discard it and tell the user.
                file.delete()
                loadError.value = "The downloaded file is not a valid model"
            }
            is ModelState.Missing, is ModelState.Failed -> embedder.unload()
            is ModelState.Downloading -> Unit
        }
    }

    /** Starts the background download. KEEP: pressing the button twice never starts two downloads. */
    fun download() {
        scope.launch {
            loadError.value = null
            val override = settings.current().modelUrl
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(ModelDownloadWorker.KEY_URL to override))
                .build()
            workManager.enqueueUniqueWork(ModelConfig.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun delete() {
        scope.launch(Dispatchers.IO) {
            workManager.cancelUniqueWork(ModelConfig.WORK_NAME)
            embedder.unload()
            file.delete()
            File(file.path + ".part").delete()
            loadError.value = null
            fileTick.value++
        }
    }
}
