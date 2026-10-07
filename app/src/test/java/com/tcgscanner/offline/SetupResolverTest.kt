package com.tcgscanner.offline

import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.scanner.SetupItem
import com.tcgscanner.offline.scanner.SetupKind
import com.tcgscanner.offline.scanner.SetupPhase
import com.tcgscanner.offline.scanner.SetupResolver
import com.tcgscanner.offline.scanner.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SetupResolverTest {
    private fun item(kind: SetupKind, status: SetupStatus, p: Float? = null, game: String? = null) = SetupItem(kind, game, status, p)

    @Test fun nothingPendingIsDone() {
        val s = SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.DONE)), wifiOnly = true, onUnmetered = false)
        assertEquals(SetupPhase.DONE, s.phase); assertEquals(1f, s.fraction!!, 0f)
        assertEquals(SetupPhase.DONE, SetupResolver.summarize(emptyList(), true, true).phase)
    }

    @Test fun downloadingShowsAveragedProgress() {
        val s = SetupResolver.summarize(
            listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.DONE), item(SetupKind.ARTWORK_ENGINE, SetupStatus.WORKING, 0.5f), item(SetupKind.CATALOG, SetupStatus.WAITING, game = "mtg")),
            wifiOnly = true, onUnmetered = true
        )
        assertEquals(SetupPhase.WORKING, s.phase)
        assertEquals((1f + 0.5f + 0f) / 3f, s.fraction!!, 0.001f)
        assertEquals(2, s.pending.size)
    }

    @Test fun unknownSizeIsIndeterminate() {
        val s = SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.WORKING, null)), false, true)
        assertNull(s.fraction); assertEquals(SetupPhase.WORKING, s.phase)
    }

    @Test fun wifiOnlyOnMobileDataWaitsForWifi() {
        val s = SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.WORKING, null), item(SetupKind.CATALOG, SetupStatus.WAITING, game = "ygo")), wifiOnly = true, onUnmetered = false)
        assertEquals(SetupPhase.WAITING_WIFI, s.phase)
        // The same situation with the preference off is simply a normal download.
        assertEquals(SetupPhase.WORKING, SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.WORKING, null)), false, false).phase)
    }

    @Test fun failuresSurfaceOnlyOnceNothingIsRunning() {
        val running = SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.FAILED), item(SetupKind.CATALOG, SetupStatus.WORKING, 0.2f, "mtg")), false, true)
        assertEquals(SetupPhase.WORKING, running.phase)
        val finished = SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.FAILED), item(SetupKind.CATALOG, SetupStatus.DONE, game = "mtg")), false, true)
        assertEquals(SetupPhase.FAILED, finished.phase)
        // A failure alone on mobile data with wifi-only: nothing to wait for, report the failure.
        assertEquals(SetupPhase.FAILED, SetupResolver.summarize(listOf(item(SetupKind.SCAN_ENGINE, SetupStatus.FAILED)), true, false).phase)
    }

    @Test fun queuedItemsAreWaiting() {
        assertEquals(SetupPhase.WAITING, SetupResolver.summarize(listOf(item(SetupKind.CATALOG, SetupStatus.WAITING, game = "pokemon")), false, true).phase)
    }

    @Test fun packStatesMapToItems() {
        assertEquals(SetupStatus.DONE, SetupResolver.packItem(SetupKind.SCAN_ENGINE, ModelState.Ready).status)
        assertEquals(0.3f, SetupResolver.packItem(SetupKind.SCAN_ENGINE, ModelState.Downloading(0.3f)).progress)
        assertEquals(SetupStatus.FAILED, SetupResolver.packItem(SetupKind.ARTWORK_ENGINE, ModelState.Failed("x")).status)
        assertEquals(SetupStatus.WAITING, SetupResolver.packItem(SetupKind.ARTWORK_ENGINE, ModelState.Missing).status)
    }

    @Test fun catalogStatusFollowsTheSync() {
        fun st(count: Int, this_: Boolean, running: Boolean, failed: Boolean) = SetupResolver.catalogItem("mtg", count, this_, running, failed, 0.4f)
        assertEquals(SetupStatus.DONE, st(10, false, false, false).status)
        assertEquals(SetupStatus.DONE, st(10, true, true, false).status)       // refreshing an existing catalog is not "setup"
        assertEquals(0.4f, st(0, true, true, false).progress)
        assertEquals(SetupStatus.WAITING, st(0, false, true, false).status)    // another game is syncing
        assertEquals(SetupStatus.FAILED, st(0, false, false, true).status)
        assertEquals(SetupStatus.WAITING, st(0, false, false, false).status)
        assertNotNull(st(0, true, true, false))
    }
}
