package com.tcgscanner.offline.scanner

/** Static settings of the optional on-device visual model (TensorFlow Lite artwork embedder). */
object ModelConfig {
    /** Lives in Context.filesDir, so it is app-private, survives updates and needs no storage permission. */
    const val FILE_NAME = "card_embedder.tflite"

    /**
     * Public location of the model: attach `card_embedder.tflite` to a GitHub release of this project.
     * Users can override it in Settings, so a moved or renamed release never bricks the feature.
     */
    const val DEFAULT_URL = "https://github.com/emmanuel-var/TCG-Scanner-Offline/releases/download/models-v1/card_embedder.tflite"

    const val APPROX_SIZE_MB = 15

    /** Anything smaller than this is an error page or a truncated download, not a model. */
    const val MIN_BYTES = 1_000_000L

    /** Lower-case hex SHA-256 of the published model. When non-empty the download is verified against it. */
    const val SHA256 = ""

    const val WORK_NAME = "model_download"
}

/** What the scanner UI needs to know about the model. Rendered reactively from a StateFlow. */
sealed interface ModelState {
    data object Missing : ModelState

    /** [progress] is 0..1, or null while the size is unknown or the request is still queued. */
    data class Downloading(val progress: Float?) : ModelState

    data object Ready : ModelState

    data class Failed(val message: String?) : ModelState
}

/** Coarse WorkManager state, decoupled from androidx so the mapping below is unit-testable on the JVM. */
enum class ModelWork { NONE, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

object ModelStateResolver {
    /**
     * @param fileReady the model file exists in filesDir and is non-empty.
     * @param error last known failure (worker output or a model that failed to load), if any.
     */
    fun resolve(work: ModelWork, progress: Float?, fileReady: Boolean, error: String?): ModelState = when {
        work == ModelWork.QUEUED || work == ModelWork.RUNNING -> ModelState.Downloading(progress?.coerceIn(0f, 1f))
        fileReady -> ModelState.Ready
        work == ModelWork.FAILED || error != null -> ModelState.Failed(error)
        else -> ModelState.Missing
    }
}
