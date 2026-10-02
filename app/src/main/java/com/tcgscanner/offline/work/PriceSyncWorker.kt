package com.tcgscanner.offline.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tcgscanner.offline.TcgApp
import java.util.concurrent.TimeUnit

/** Daily background refresh of catalogs and prices for every activated game. */
class PriceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TcgApp).container
        val active = container.settings.current().activeGames
        if (active.isEmpty()) return Result.success()
        val results = container.sync.syncAll(active)
        val allFailed = results.isNotEmpty() && results.none { it.success }
        return if (allFailed && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    private const val UNIQUE_NAME = "daily_price_sync"

    fun apply(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
