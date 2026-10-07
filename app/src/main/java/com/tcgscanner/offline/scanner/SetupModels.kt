package com.tcgscanner.offline.scanner

/** First-run preparation shown as a small progress strip: engines + the catalogs of the games the user plays. */
enum class SetupKind { SCAN_ENGINE, ARTWORK_ENGINE, CATALOG }

enum class SetupStatus { DONE, WORKING, WAITING, FAILED }

data class SetupItem(
    val kind: SetupKind,
    /** GameId.code for [SetupKind.CATALOG], otherwise null. */
    val gameCode: String? = null,
    val status: SetupStatus,
    /** 0..1 while [SetupStatus.WORKING] and the size is known. */
    val progress: Float? = null
)

enum class SetupPhase {
    /** Everything needed is installed. */
    DONE,
    /** At least one item is downloading. */
    WORKING,
    /** Nothing is moving because the Wi-Fi-only preference is waiting for a Wi-Fi network. */
    WAITING_WIFI,
    /** Queued but not started yet. */
    WAITING,
    /** Finished, with at least one failure and nothing left running. */
    FAILED
}

data class SetupSummary(val items: List<SetupItem>, val phase: SetupPhase, val fraction: Float?) {
    val pending: List<SetupItem> get() = items.filter { it.status != SetupStatus.DONE }

    companion object {
        val Done = SetupSummary(emptyList(), SetupPhase.DONE, 1f)
    }
}

object SetupResolver {
    /**
     * @param wifiOnly the user's Wi-Fi-only preference.
     * @param onUnmetered the device is currently on a Wi-Fi-like (unmetered) network.
     */
    fun summarize(items: List<SetupItem>, wifiOnly: Boolean, onUnmetered: Boolean): SetupSummary {
        val pending = items.filter { it.status != SetupStatus.DONE }
        val phase = when {
            pending.isEmpty() -> SetupPhase.DONE
            pending.any { it.status == SetupStatus.WORKING } && !(wifiOnly && !onUnmetered) -> SetupPhase.WORKING
            pending.any { it.status != SetupStatus.FAILED } && wifiOnly && !onUnmetered -> SetupPhase.WAITING_WIFI
            pending.any { it.status == SetupStatus.WORKING } -> SetupPhase.WORKING
            pending.any { it.status == SetupStatus.WAITING } -> SetupPhase.WAITING
            else -> SetupPhase.FAILED
        }
        val fraction = when {
            items.isEmpty() -> 1f
            pending.any { it.status == SetupStatus.WORKING && it.progress == null } &&
                pending.none { it.progress != null } -> null
            else -> items.map { item ->
                when (item.status) {
                    SetupStatus.DONE, SetupStatus.FAILED -> 1f
                    SetupStatus.WORKING -> item.progress ?: 0f
                    SetupStatus.WAITING -> 0f
                }
            }.average().toFloat().coerceIn(0f, 1f)
        }
        return SetupSummary(items, phase, fraction)
    }

    fun packItem(kind: SetupKind, state: ModelState): SetupItem = when (state) {
        ModelState.Ready -> SetupItem(kind, null, SetupStatus.DONE, 1f)
        is ModelState.Downloading -> SetupItem(kind, null, SetupStatus.WORKING, state.progress)
        is ModelState.Failed -> SetupItem(kind, null, SetupStatus.FAILED)
        ModelState.Missing -> SetupItem(kind, null, SetupStatus.WAITING)
    }

    /**
     * @param cardCount rows of this game's catalog already in Room.
     * @param syncingThis the sync coordinator is working on this game right now.
     * @param syncRunning some sync is running (maybe another game: this one waits its turn).
     * @param failed the last attempt for this game failed.
     */
    fun catalogItem(gameCode: String, cardCount: Int, syncingThis: Boolean, syncRunning: Boolean, failed: Boolean, progress: Float?): SetupItem {
        val status = when {
            cardCount > 0 -> SetupStatus.DONE
            syncingThis -> SetupStatus.WORKING
            failed && !syncRunning -> SetupStatus.FAILED
            else -> SetupStatus.WAITING
        }
        return SetupItem(SetupKind.CATALOG, gameCode, status, if (status == SetupStatus.WORKING) progress else null)
    }
}
