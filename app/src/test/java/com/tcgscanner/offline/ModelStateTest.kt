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

    @Test fun aggregatesPacksIntoOneState() {
        val agg = com.tcgscanner.offline.scanner.aggregateModelStates(listOf(ModelState.Ready, ModelState.Ready))
        assertEquals(ModelState.Ready, agg)
        assertEquals(ModelState.Missing, com.tcgscanner.offline.scanner.aggregateModelStates(listOf(ModelState.Ready, ModelState.Missing)))
        assertEquals(
            ModelState.Downloading(0.5f),
            com.tcgscanner.offline.scanner.aggregateModelStates(listOf(ModelState.Ready, ModelState.Downloading(0f)))
        )
        assertEquals(
            ModelState.Downloading(null),
            com.tcgscanner.offline.scanner.aggregateModelStates(listOf(ModelState.Downloading(null), ModelState.Ready))
        )
        assertEquals(ModelState.Failed("x"), com.tcgscanner.offline.scanner.aggregateModelStates(listOf(ModelState.Ready, ModelState.Failed("x"))))
    }

    @Test fun packFilesAndUrls() {
        val ocr = com.tcgscanner.offline.scanner.EnginePacks.files(com.tcgscanner.offline.scanner.EnginePack.OCR).map { it.name }
        assertEquals(listOf("ocr_det.onnx", "ocr_rec.onnx", "ocr_dict.txt"), ocr)
        val f = com.tcgscanner.offline.scanner.EnginePacks.files(com.tcgscanner.offline.scanner.EnginePack.DETECTOR).single()
        assertEquals("https://example.com/m/card_detector.tflite", com.tcgscanner.offline.scanner.EnginePacks.urlFor("https://example.com/m", f))
        assertEquals(com.tcgscanner.offline.scanner.EnginePacks.DEFAULT_BASE_URL + "card_detector.tflite", com.tcgscanner.offline.scanner.EnginePacks.urlFor("", f))
    }
}
