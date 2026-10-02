@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.OcrScript
import com.tcgscanner.offline.scanner.MlKitOcrEngine
import com.tcgscanner.offline.scanner.PipelineAnalyzer
import com.tcgscanner.offline.scanner.pipeline.ScanPipeline
import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.ui.components.ModelPromptCard
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.AddCardSheet
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.components.GameScaffold
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val CARD_ASPECT = 63f / 88f

@Composable
fun ScannerScreen(nav: NavController) {
    val vm = appViewModel { ScannerViewModel(it) }
    val context = LocalContext.current
    val game by vm.game.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val catalogCount by vm.catalogCount.collectAsStateWithLifecycle()
    val scanEngine by vm.scanEngineState.collectAsStateWithLifecycle()
    val visualEngine by vm.visualEngineState.collectAsStateWithLifecycle()
    val identifyEnabled by vm.identifyEnabled.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var searching by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    val addedFormat = stringResource(R.string.added_named)
    LaunchedEffect(ui.addedName) {
        ui.addedName?.let {
            snackbar.showSnackbar(String.format(addedFormat, it))
            vm.consumeAdded()
        }
    }
    LaunchedEffect(ui.sheet?.chosen?.card?.id) {
        if (ui.sheet != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    GameScaffold(
        nav, title = stringResource(R.string.tab_scan), snackbarHost = snackbar,
        actions = {
            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_manually)) }
            if (hasTorch && granted) {
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = stringResource(R.string.toggle_flash))
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                game == null -> Unit
                !granted -> EmptyState(
                    Icons.Filled.PhotoCamera, stringResource(R.string.camera_needed_title), stringResource(R.string.camera_needed_message),
                    actionLabel = stringResource(R.string.grant_camera), onAction = { launcher.launch(Manifest.permission.CAMERA) }
                )
                else -> CameraSection(
                    vm = vm, game = game!!, torch = torch, onTorchAvailable = { hasTorch = it },
                    catalogEmpty = catalogCount == 0, ui = ui,
                    scanEngine = scanEngine, visualEngine = visualEngine, identifyEnabled = identifyEnabled
                )
            }
        }
    }

    ui.sheet?.let { sheet ->
        game?.let { g ->
            AddCardSheet(
                game = g, card = sheet.chosen, otherPrints = sheet.others,
                confidenceText = sheet.confidencePercent?.let { stringResource(R.string.match_confidence, it) },
                onPickPrint = { vm.open(it) }, onAdd = vm::add, onDismiss = vm::dismissSheet
            )
        }
    }

    ui.visualCandidates?.let { list ->
        AlertDialog(
            onDismissRequest = vm::dismissVisual,
            title = { Text(stringResource(R.string.artwork_matches)) },
            text = {
                if (list.isEmpty()) {
                    Text(stringResource(R.string.artwork_no_index))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.card.id }) { cw ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.open(cw) },
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                            ) {
                                CardImage(cw.card.imageUrl, cw.card.name, Modifier.size(width = 48.dp, height = 67.dp))
                                Column {
                                    Text(cw.card.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${cw.card.setName} · #${cw.card.number}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissVisual) { Text(stringResource(R.string.close)) } }
        )
    }

    if (searching) {
        ManualSearchDialog(vm, onDismiss = { searching = false; vm.setQuery("") }, onPick = { searching = false; vm.open(it) })
    }
}

@Composable
private fun CameraSection(
    vm: ScannerViewModel,
    game: GameDef,
    torch: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    catalogEmpty: Boolean,
    ui: ScannerUi,
    scanEngine: ModelState,
    visualEngine: ModelState,
    identifyEnabled: Boolean
) {
    val liveGuess = ui.liveGuess
    val busy = ui.artworkBusy
    val guide = remember { AtomicReference(RectF(0.1f, 0.1f, 0.9f, 0.9f)) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Card-shaped window centred in the preview; the analyzer crops exactly this region.
    val rect = remember(viewSize) {
        if (viewSize.width == 0 || viewSize.height == 0) RectF(0.1f, 0.1f, 0.9f, 0.9f) else {
            val vw = viewSize.width.toFloat()
            val vh = viewSize.height.toFloat()
            var w = vw * 0.82f
            var h = w / CARD_ASPECT
            if (h > vh * 0.78f) { h = vh * 0.78f; w = h * CARD_ASPECT }
            val l = (vw - w) / 2f
            val t = (vh - h) / 2f - vh * 0.04f
            RectF(l / vw, t / vh, (l + w) / vw, (t + h) / vh)
        }
    }
    guide.set(rect)

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        CameraPreview(vm = vm, game = game, guide = guide, torch = torch, onTorchAvailable = onTorchAvailable)

        val dim = Color.Black.copy(alpha = 0.55f)
        val line = Color.White
        Canvas(Modifier.fillMaxSize()) {
            val r = Offset(rect.left * size.width, rect.top * size.height)
            val s = GSize((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height)
            val window = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r.x, r.y, r.x + s.width, r.y + s.height, CornerRadius(16.dp.toPx()))) }
            clipPath(window, ClipOp.Difference) { drawRect(dim) }
            drawRoundRect(line, r, s, CornerRadius(16.dp.toPx()), style = Stroke(width = 3.dp.toPx()))
        }

        // Phase 1 feedback: the card outline YOLO found (after corner refinement), drawn over the live preview.
        val outline = ui.outline
        if (outline != null) {
            val accent = Color(0xFF4DD58A)
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(outline.tl.x * size.width, outline.tl.y * size.height)
                    lineTo(outline.tr.x * size.width, outline.tr.y * size.height)
                    lineTo(outline.br.x * size.width, outline.br.y * size.height)
                    lineTo(outline.bl.x * size.width, outline.bl.y * size.height)
                    close()
                }
                drawPath(path, accent.copy(alpha = 0.18f))
                drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Non-intrusive notice: the camera and OCR are already live; this only offers the optional engine.
            // The scan engine (YOLO + PaddleOCR) is offered first; the artwork engine only once that one is in place.
            AnimatedVisibility(
                visible = scanEngine !is ModelState.Ready,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModelPromptCard(scanEngine, onDownload = vm::downloadScanEngine, promptRes = R.string.scan_engine_prompt, downloadingRes = R.string.scan_engine_downloading)
            }
            AnimatedVisibility(
                visible = scanEngine is ModelState.Ready && visualEngine !is ModelState.Ready,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModelPromptCard(visualEngine, onDownload = vm::downloadVisualEngine)
            }
            if (catalogEmpty) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(R.string.scan_catalog_empty), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, textAlign = TextAlign.Center)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.engine.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.5f)) {
                    Text(
                        (if (ui.detected) "YOLO11n" else stringResource(R.string.scan_guide_mode)) + " · " + ui.engine,
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.6f)) {
                Text(
                    when {
                        liveGuess != null -> stringResource(R.string.scan_reading, liveGuess)
                        ui.needsArtwork -> stringResource(R.string.scan_no_number_hint)
                        else -> stringResource(R.string.scan_hint, stringResource(game.nameRes))
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, textAlign = TextAlign.Center
                )
            }
            val artButton: @Composable (@Composable () -> Unit) -> Unit = { content ->
                // Phase 3 is the user's choice; when OCR keeps failing the button is promoted to the primary action.
                if (ui.needsArtwork && identifyEnabled) androidx.compose.material3.Button(onClick = vm::identifyByArtwork, enabled = !busy) { content() }
                else FilledTonalButton(onClick = vm::identifyByArtwork, enabled = identifyEnabled && !busy) { content() }
            }
            artButton {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Image, contentDescription = null)
                Text(stringResource(R.string.identify_by_artwork), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CameraPreview(
    vm: ScannerViewModel,
    game: GameDef,
    guide: AtomicReference<RectF>,
    torch: Boolean,
    onTorchAvailable: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val container = com.tcgscanner.offline.ui.LocalContainer.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner, game.id) {
        val executor = Executors.newSingleThreadExecutor()
        // Fallback OCR bundled with the app: the camera reads text immediately, before any pack is downloaded.
        val mlKit = MlKitOcrEngine(
            when (game.ocrScript) {
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        )
        // Hybrid pipeline: YOLO11n (if installed) -> rectified card -> PaddleOCR (if installed) else ML Kit.
        // The engine is picked per frame, so a pack that finishes downloading takes over without restarting the camera.
        val pipeline = ScanPipeline(container.detector) {
            val paddle = if (game.ocrScript == OcrScript.JAPANESE) container.paddleOcrJa else container.paddleOcr
            if (paddle.isAvailable) paddle else mlKit
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        previewView.doOnLayout {
            providerFuture.addListener({
                if (disposed) return@addListener
                val p = providerFuture.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(
                    executor,
                    PipelineAnalyzer(pipeline, guide, vm.paused, onFrame = vm::onFrame)
                )
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis).apply {
                    previewView.viewPort?.let { setViewPort(it) }
                }.build()
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                onTorchAvailable(camera?.cameraInfo?.hasFlashUnit() == true)
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            disposed = true
            provider?.unbindAll()
            camera = null
            executor.shutdown()
            mlKit.close()
        }
    }

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSearchDialog(vm: ScannerViewModel, onDismiss: () -> Unit, onPick: (com.tcgscanner.offline.data.db.CardWithPrices) -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_manually)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    q, { q = it; vm.setQuery(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_hint)) }
                )
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.card.id }) { cw ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(cw) }.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            CardImage(cw.card.imageUrl, cw.card.name, Modifier.size(width = 40.dp, height = 56.dp))
                            Column {
                                Text(cw.card.name, style = MaterialTheme.typography.titleMedium)
                                Text("${cw.card.setName} · #${cw.card.number}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
