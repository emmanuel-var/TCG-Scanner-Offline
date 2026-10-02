package com.tcgscanner.offline

import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.scanner.ModelStateResolver.resolve
import com.tcgscanner.offline.scanner.ModelWork
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStateTest {
    @Test fun missingWhenNothingHappenedAndNoFile() {
        assertEquals(ModelState.Missing, resolve(ModelWork.NONE, null, fileReady = false, error = null))
    }

    @Test fun downloadingWhileQueuedOrRunningWithClampedProgress() {
        assertEquals(ModelState.Downloading(null), resolve(ModelWork.QUEUED, null, false, null))
        assertEquals(ModelState.Downloading(0.4f), resolve(ModelWork.RUNNING, 0.4f, false, null))
        assertEquals(ModelState.Downloading(1f), resolve(ModelWork.RUNNING, 3f, false, null))
    }

    @Test fun readyTheMomentWorkSucceedsAndTheFileExists() {
        assertEquals(ModelState.Ready, resolve(ModelWork.SUCCEEDED, 1f, fileReady = true, error = null))
    }

    @Test fun readyWhenTheFileIsAlreadyThereOnAFreshLaunch() {
        assertEquals(ModelState.Ready, resolve(ModelWork.NONE, null, fileReady = true, error = null))
    }

    @Test fun succeededButFileDeletedMeansMissingAgain() {
        assertEquals(ModelState.Missing, resolve(ModelWork.SUCCEEDED, null, fileReady = false, error = null))
    }

    @Test fun failedCarriesTheMessage() {
        assertEquals(ModelState.Failed("HTTP 404"), resolve(ModelWork.FAILED, null, false, "HTTP 404"))
        assertEquals(ModelState.Failed("bad model"), resolve(ModelWork.SUCCEEDED, null, false, "bad model"))
    }

    @Test fun retryingAfterFailureShowsProgressAgain() {
        assertEquals(ModelState.Downloading(null), resolve(ModelWork.QUEUED, null, false, "HTTP 500"))
    }
}
