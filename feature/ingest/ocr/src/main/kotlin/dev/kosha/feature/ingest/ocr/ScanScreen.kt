package dev.kosha.feature.ingest.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.io.File

/**
 * Scan tab (spec C4): live camera with a batch toggle. Capture → extraction
 * preview with editable fields → pipeline. Camera permission is requested
 * here, in context, on first open (spec G9).
 */
@Composable
fun ScanScreen(viewModel: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }

    state.current?.let { preview ->
        ExtractionPreview(
            preview = preview,
            onAmountChange = viewModel::editAmount,
            onMerchantChange = viewModel::editMerchant,
            onTypeChange = viewModel::setType,
            onConfirm = viewModel::confirmCurrent,
            onDiscard = viewModel::discardCurrent,
        )
        return
    }

    state.warrantyPrompt?.let { prompt ->
        WarrantyPromptSheet(
            prompt = prompt,
            onSave = viewModel::saveWarranty,
            onDismiss = viewModel::dismissWarranty,
        )
        return
    }

    when {
        !hasCameraPermission -> CameraPermissionGate(
            denied = permissionDenied,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        )

        else -> CameraCapture(
            processing = state.processing,
            batchMode = state.batchMode,
            batchCount = state.batchCount,
            unreadable = state.unreadable,
            captureFailed = state.captureFailed,
            onToggleBatch = viewModel::toggleBatch,
            onCaptureStarted = viewModel::onCaptureStarted,
            onCaptureFailed = viewModel::onCaptureFailed,
            onCaptured = { uri -> viewModel.onCaptured(uri, liveCapture = true) },
        )
    }
}

/**
 * Import tab (spec C4): system Photo Picker — no storage permission ever
 * (spec G9). Imported screenshots score lower on time, so they land in
 * review rather than auto-committing with the wrong date.
 */
@Composable
fun ImportScreen(viewModel: CaptureViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris: List<Uri> ->
        uris.forEach { viewModel.onCaptured(it, liveCapture = false) }
    }

    state.current?.let { preview ->
        ExtractionPreview(
            preview = preview,
            onAmountChange = viewModel::editAmount,
            onMerchantChange = viewModel::editMerchant,
            onTypeChange = viewModel::setType,
            onConfirm = viewModel::confirmCurrent,
            onDiscard = viewModel::discardCurrent,
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.import_body),
            style = KoshaType.InsightSerif,
            color = KoshaColors.OffWhiteMuted,
        )

        // This screen had no failure state at all. When a picked image could
        // not be read, the preview never opened and the picker chip simply
        // reappeared — indistinguishable from the button not working.
        if (state.unreadable) {
            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(R.string.import_unreadable),
                style = KoshaType.Body,
                color = KoshaColors.Amber,
            )
        }

        Spacer(Modifier.height(KoshaSpacing.m))
        if (state.processing) {
            CircularProgressIndicator(color = KoshaColors.AccentTeal)
        } else {
            KoshaChip(
                label = stringResource(
                    if (state.unreadable) R.string.import_pick_again else R.string.import_pick,
                ),
                onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                accent = KoshaColors.AccentTeal,
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(denied: Boolean, onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(KoshaSpacing.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.scan_permission_title),
            style = KoshaType.Title,
            color = KoshaColors.OffWhite,
        )
        Spacer(Modifier.height(KoshaSpacing.xs))
        Text(
            text = if (denied) {
                stringResource(R.string.scan_permission_denied)
            } else {
                stringResource(R.string.scan_permission_body)
            },
            style = KoshaType.Body,
            color = KoshaColors.OffWhiteMuted,
        )
        Spacer(Modifier.height(KoshaSpacing.m))
        if (!denied) {
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.scan_permission_allow), color = KoshaColors.AccentTeal)
            }
        }
    }
}

@Composable
private fun CameraCapture(
    processing: Boolean,
    batchMode: Boolean,
    batchCount: Int,
    unreadable: Boolean,
    captureFailed: Boolean,
    onToggleBatch: () -> Unit,
    onCaptureStarted: () -> Unit,
    onCaptureFailed: () -> Unit,
    onCaptured: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val cameraPreview = CameraPreview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        cameraPreview,
                        imageCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        DisposableEffect(Unit) {
            onDispose {
                runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(KoshaSpacing.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            if (unreadable) {
                Text(
                    text = stringResource(R.string.scan_unreadable),
                    style = KoshaType.Body,
                    color = KoshaColors.Amber,
                )
            }
            if (captureFailed) {
                Text(
                    text = stringResource(R.string.scan_capture_failed),
                    style = KoshaType.Body,
                    color = KoshaColors.Amber,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.s)) {
                KoshaChip(
                    label = if (batchMode) {
                        stringResource(R.string.scan_batch_on, batchCount)
                    } else {
                        stringResource(R.string.scan_batch)
                    },
                    selected = batchMode,
                    onClick = onToggleBatch,
                )
                if (processing) {
                    CircularProgressIndicator(color = KoshaColors.AccentTeal)
                } else {
                    KoshaChip(
                        label = stringResource(R.string.scan_shutter),
                        onClick = {
                            onCaptureStarted()
                            captureTo(context, imageCapture, onCaptured, onCaptureFailed)
                        },
                        accent = KoshaColors.AccentTeal,
                    )
                }
            }
        }
    }
}

private fun captureTo(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (Uri) -> Unit,
    onFailed: () -> Unit,
) {
    // App-private storage: no storage permission, and evidence photos stay
    // inside the app sandbox (spec B4/G9).
    val file = File(context.filesDir, "evidence/${System.currentTimeMillis()}.jpg").apply {
        parentFile?.mkdirs()
    }
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onCaptured(output.savedUri ?: Uri.fromFile(file))
            }

            // Swallowing this meant a failed shutter did nothing whatsoever
            // — no photo, no error, no spinner ending. The tab simply looked
            // dead.
            override fun onError(exception: ImageCaptureException) = onFailed()
        },
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
