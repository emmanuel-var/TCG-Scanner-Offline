package com.tcgscanner.offline.scanner

import java.io.File

/** The optional downloadable engines of the scan pipeline. Each is a set of plain files in Context.filesDir. */
enum class EnginePack(val id: String) {
    /** Phase 1: YOLO11n card detector (TFLite). */
    DETECTOR("detector"),

    /** Phase 2: PaddleOCR-Mobile text detector + Latin recogniser + dictionary (ONNX). */
    OCR("ocr"),

    /** Phase 2 for Japanese cards: recogniser + dictionary (shares the detector of [OCR]). */
    OCR_JA("ocr_ja"),

    /** Phase 3: EfficientNet-Lite0 feature extractor for "identify by artwork" (TFLite). */
    EMBEDDER("embedder");

    val workName: String get() = "pack_$id"
}

data class PackFile(
    val name: String,
    val approxMb: Double,
    /** Anything smaller is an error page or a truncated download. */
    val minBytes: Long,
    /** Lower-case hex SHA-256 of the published file; verified when non-empty. */
    val sha256: String = ""
)

object EnginePacks {
    /**
     * Folder of a GitHub release (or any static host) holding the pack files. Files are `<base>/<file name>`.
     * Users can point to another folder in Settings, so a moved release never bricks the feature.
     */
    const val DEFAULT_BASE_URL = "https://github.com/emmanuel-var/TCG-Scanner-Offline/releases/download/models-v1/"

    const val DETECTOR_FILE = "card_detector.tflite"
    const val OCR_DET_FILE = "ocr_det.onnx"
    const val OCR_REC_FILE = "ocr_rec.onnx"
    const val OCR_DICT_FILE = "ocr_dict.txt"
    const val OCR_REC_JA_FILE = "ocr_rec_japan.onnx"
    const val OCR_DICT_JA_FILE = "ocr_dict_japan.txt"
    const val EMBEDDER_FILE = "card_embedder.tflite"

    fun files(pack: EnginePack): List<PackFile> = when (pack) {
        EnginePack.DETECTOR -> listOf(PackFile(DETECTOR_FILE, 6.0, 500_000))
        EnginePack.OCR -> listOf(
            PackFile(OCR_DET_FILE, 4.7, 1_000_000), PackFile(OCR_REC_FILE, 10.0, 1_000_000), PackFile(OCR_DICT_FILE, 0.03, 100)
        )
        EnginePack.OCR_JA -> listOf(PackFile(OCR_REC_JA_FILE, 11.0, 1_000_000), PackFile(OCR_DICT_JA_FILE, 0.1, 100))
        EnginePack.EMBEDDER -> listOf(PackFile(EMBEDDER_FILE, 13.0, 1_000_000))
    }

    fun approxMb(pack: EnginePack): Int = Math.ceil(files(pack).sumOf { it.approxMb }).toInt()

    fun file(dir: File, name: String) = File(dir, name)

    fun isInstalled(dir: File, pack: EnginePack): Boolean =
        files(pack).all { f -> File(dir, f.name).let { it.isFile && it.length() > 0L } }

    fun urlFor(base: String, file: PackFile): String = base.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/') + "/" + file.name
}
