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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for ONE engine pack. [state] is derived from two things only: the files in filesDir and
 * the WorkInfo of the download job. The moment WorkManager reports SUCCEEDED and the files are on disk the state
 * flips to Ready, [onInstalled] loads the engine, and every collector (the scanner screen) updates itself; nothing
 * needs a restart.
 *
 * @param onInstalled loads the engine from the freshly installed files; false = the files are unusable (corrupt).
 * @param onRemoved releases the engine when the files go away.
 */
class ModelRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    val pack: EnginePack,
    private val onInstalled: () -> Boolean,
    private val onRemoved: () -> Unit
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val dir: File = appContext.filesDir

    /** Bumped when the files change outside of WorkManager (delete, failed validation). */
    private val fileTick = MutableStateFlow(0)
    private val loadError = MutableStateFlow<String?>(null)

    private fun filesReady() = EnginePacks.isInstalled(dir, pack)

    val state: StateFlow<ModelState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(pack.workName),
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
        ModelStateResolver.resolve(work, progress, filesReady(), error)
    }
        .flowOn(Dispatchers.IO)
        .onEach { applyToEngine(it) }
        .stateIn(scope, SharingStarted.Eagerly, if (filesReady()) ModelState.Ready else ModelState.Missing)

    private var engineLoaded = false

    private fun applyToEngine(state: ModelState) {
        when (state) {
            ModelState.Ready -> if (!engineLoaded) {
                engineLoaded = onInstalled()
                if (!engineLoaded) {
                    // Present but unusable (corrupt / incompatible): discard it and tell the user.
                    deleteFiles()
                    loadError.value = "The downloaded file is not a valid model"
                }
            }
            is ModelState.Missing, is ModelState.Failed -> if (engineLoaded) { onRemoved(); engineLoaded = false }
            is ModelState.Downloading -> Unit
        }
    }

    /** Starts the background download. KEEP: pressing the button twice never starts two downloads. */
    fun download() {
        scope.launch {
            loadError.value = null
            val base = settings.current().modelBaseUrl
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(ModelDownloadWorker.KEY_PACK to pack.id, ModelDownloadWorker.KEY_BASE_URL to base))
                .build()
            workManager.enqueueUniqueWork(pack.workName, ExistingWorkPolicy.KEEP, request)
        }
    }

    fun delete() {
        scope.launch(Dispatchers.IO) {
            workManager.cancelUniqueWork(pack.workName)
            if (engineLoaded) { onRemoved(); engineLoaded = false }
            deleteFiles()
            loadError.value = null
            fileTick.value++
        }
    }

    private fun deleteFiles() {
        EnginePacks.files(pack).forEach { f ->
            File(dir, f.name).delete()
            File(dir, f.name + ".part").delete()
        }
    }
}
