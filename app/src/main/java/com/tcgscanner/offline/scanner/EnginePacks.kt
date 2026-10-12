package com.tcgscanner.offline.scanner

import java.io.File

/** The optional downloadable engines of the scan pipeline. Each is a set of plain files in Context.filesDir. */
enum class EnginePack(val id: String) {
    /** Phase 2: PaddleOCR-Mobile text detector + Latin recogniser + dictionary (ONNX). */
    OCR("ocr"),

    /** Phase 2 for Japanese cards: recogniser + dictionary (shares the text detector of [OCR]). */
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

    const val OCR_DET_FILE = "ocr_det.onnx"
    const val OCR_REC_FILE = "ocr_rec.onnx"
    const val OCR_DICT_FILE = "ocr_dict.txt"
    const val OCR_REC_JA_FILE = "ocr_rec_japan.onnx"
    const val OCR_DICT_JA_FILE = "ocr_dict_japan.txt"
    const val EMBEDDER_FILE = "card_embedder.tflite"

    // SHA-256 of the files published in the models-v1 release (verified on download). If you republish a file,
    // run tools/model/release_checksums.py and update the value here, or the download is rejected as corrupt.
    fun files(pack: EnginePack): List<PackFile> = when (pack) {
        EnginePack.OCR -> listOf(
            PackFile(OCR_DET_FILE, 4.7, 1_000_000, "ab2a50dcd2c340852f2d0fbfa547d5eec79a0d04a774eb0b622d96d0d9d2ceeb"),
            PackFile(OCR_REC_FILE, 10.0, 1_000_000, "40c07c5e431a4c59d7b5a1fefdba2fddb962c939d626c4dbf1d32965ab533431"),
            PackFile(OCR_DICT_FILE, 0.03, 100, "5662df9d2d03f0e8ca0d3b0649d6acbab904b6a14b3d3521463c71c37c668ce3")
        )
        EnginePack.OCR_JA -> listOf(
            PackFile(OCR_REC_JA_FILE, 11.0, 1_000_000, "da59d4dbb6786a92a3823c32b3f48179d18e29903d61462842aad9eb422a77a1"),
            PackFile(OCR_DICT_JA_FILE, 0.1, 100, "1dcfcb41eec90576a945b3084f22ade11ced506e24f14879245b071698f308e8")
        )
        EnginePack.EMBEDDER -> listOf(
            PackFile(EMBEDDER_FILE, 13.0, 1_000_000, "f5a287313dee5600e6cf28e7865b4959a6fb0e95ff11a523a50cf17fa200796c")
        )
    }

    fun approxMb(pack: EnginePack): Int = Math.ceil(files(pack).sumOf { it.approxMb }).toInt()

    fun file(dir: File, name: String) = File(dir, name)

    fun isInstalled(dir: File, pack: EnginePack): Boolean =
        files(pack).all { f -> File(dir, f.name).let { it.isFile && it.length() > 0L } }

    fun urlFor(base: String, file: PackFile): String = base.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/') + "/" + file.name
}
