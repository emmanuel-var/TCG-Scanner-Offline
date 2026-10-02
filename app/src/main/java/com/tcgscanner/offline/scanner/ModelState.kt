package com.tcgscanner.offline.scanner

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

/** Folds the states of several packs into the one the UI shows ("scan engine" = detector + OCR). */
fun aggregateModelStates(states: List<ModelState>): ModelState = when {
    states.isEmpty() -> ModelState.Ready
    states.any { it is ModelState.Downloading } -> {
        val parts = states.map { st ->
            when (st) {
                is ModelState.Downloading -> st.progress
                ModelState.Ready -> 1f
                else -> 0f
            }
        }
        val known = states.none { it is ModelState.Downloading && it.progress == null }
        ModelState.Downloading(if (known) parts.map { it ?: 0f }.average().toFloat() else null)
    }
    states.all { it == ModelState.Ready } -> ModelState.Ready
    states.any { it is ModelState.Failed } -> states.filterIsInstance<ModelState.Failed>().first()
    else -> ModelState.Missing
}
