package com.tcgscanner.offline.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tcgscanner.offline.TcgApp
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.repo.SyncResult
import java.util.concurrent.TimeUnit

/**
 * Catalog + price refresh. Used for the first download of a newly added game, for the daily background
 * refresh, for manual "Sync prices" and after a catalog URL override changes.
 */
class PriceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TcgApp).container
        val requested = inputData.getStringArray(KEY_GAMES)?.mapNotNull { GameId.fromCode(it) }?.toSet().orEmpty()
        val games = if (requested.isNotEmpty()) requested else container.settings.current().activeGames
        if (games.isEmpty()) return Result.success()
        val purge = inputData.getStringArray(KEY_PURGE)?.mapNotNull { GameId.fromCode(it) }?.toSet().orEmpty()
        val results = container.sync.syncAll(games, purge)
        // A game with no source configured is not a failure and must not be retried.
        val attempted = results.filter { it.error != SyncResult.NO_SOURCE }
        val allFailed = attempted.isNotEmpty() && attempted.none { it.success }
        return if (allFailed && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_GAMES = "games"

        /** Games whose catalog URL was just changed by the user: purge their catalog rows before inserting new data. */
        const val KEY_PURGE = "purge"
    }
}

object SyncScheduler {
    private const val DAILY = "daily_price_sync"
    private const val NOW = "sync_now"

    /** Daily background refresh; cancelled when the user turns auto-sync off. */
    fun apply(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(DAILY)
            return
        }
        val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Runs a sync as soon as the constraints allow. Requests are chained (APPEND_OR_REPLACE) so adding two
     * games, or changing a URL while a sync runs, never drops work and never runs two syncs at once.
     * [manual] syncs ignore the Wi-Fi-only preference: the user explicitly asked for it.
     */
    fun enqueueNow(context: Context, games: Collection<GameId>, wifiOnly: Boolean, manual: Boolean, purge: Collection<GameId> = emptyList()) {
        if (games.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<PriceSyncWorker>()
            .setConstraints(constraints(wifiOnly && !manual))
            .setInputData(
                Data.Builder()
                    .putStringArray(PriceSyncWorker.KEY_GAMES, games.map { it.code }.toTypedArray())
                    .putStringArray(PriceSyncWorker.KEY_PURGE, purge.map { it.code }.toTypedArray())
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelNow(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
}
