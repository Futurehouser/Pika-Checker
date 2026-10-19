package com.futurehouser.pikachecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PikaYellow = Color(0xFFFFCB05)
private val LinkBlue = Color(0xFF4FA3FF)

class MainActivity : ComponentActivity() {

    private lateinit var priceCharting: PriceCharting
    private lateinit var currency: Currency
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        priceCharting = PriceCharting(this)
        currency = Currency(this)
        updater = Updater(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = PikaYellow, onPrimary = Color.Black)) {
                App(priceCharting, currency, updater)
            }
        }
    }
}

@Composable
private fun App(priceCharting: PriceCharting, currency: Currency, updater: Updater) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        currency.refresh()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            Scanner(priceCharting, currency)
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pika Checker needs the camera to read your cards.", color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        VerificationOverlay(priceCharting)
        UpdatePrompt(updater)
    }
}

@Composable
private fun Scanner(priceCharting: PriceCharting, currency: Currency) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { ScanController(scope, priceCharting) }
    var torch by remember { mutableStateOf(false) }

    val camera = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            // Collector numbers are tiny: analyse at 1080p so OCR can read them.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
        }
    }
    LaunchedEffect(torch) { camera.enableTorch(torch) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val executor = ContextCompat.getMainExecutor(ctx)
                camera.setImageAnalysisAnalyzer(
                    executor,
                    MlKitAnalyzer(listOf(recognizer), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                        controller.onText(result.getValue(recognizer))
                    }
                )
                camera.bindToLifecycle(lifecycleOwner)
                view.controller = camera
            }
        },
    )

    // Tick so stale detections fade out when the card leaves the frame.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    var pinnedUntil by remember { mutableLongStateOf(0L) }
    val card = controller.detection
    val visible = card != null && (now - controller.lastSeen < 1500 || now < pinnedUntil)

    if (card != null && visible && now >= pinnedUntil) CardFrame(card)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.roundToPx() }
        val maxH = with(density) { maxHeight.roundToPx() }
        var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
        val lastTarget = remember { arrayOf(IntOffset.Zero) }

        if (card != null && now >= pinnedUntil) {
            val margin = with(density) { 12.dp.roundToPx() }
            val x = (card.nameBox.left).coerceIn(margin, (maxW - bubbleSize.width - margin).coerceAtLeast(margin))
            val above = card.nameBox.top - bubbleSize.height - margin
            val y = (if (above > margin) above else card.nameBox.bottom + margin)
                .coerceIn(margin, (maxH - bubbleSize.height - margin).coerceAtLeast(margin))
            lastTarget[0] = IntOffset(x, y)
        }
        val animated by animateIntOffsetAsState(
            lastTarget[0], spring(stiffness = Spring.StiffnessMediumLow), label = "bubble"
        )

        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            if (card != null) {
                PriceBubble(
                    card = card,
                    lookup = controller.lookups[card.key],
                    currency = currency,
                    onNext = { controller.nextMatch(card.key) },
                    onRetry = { controller.retry(card.key) },
                    modifier = Modifier
                        .offset { animated }
                        .onSizeChanged { bubbleSize = it }
                        // Freeze the bubble while the user is touching it so the link is easy to hit.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                pinnedUntil = System.currentTimeMillis() + 4000
                            }
                        },
                )
            }
        }
    }

    TopBar(
        hint = when {
            card == null || !visible -> "Point the camera at a Pokémon card"
            else -> "${card.name} · ${card.printedNumber}"
        },
        torch = torch,
        onTorch = { torch = !torch },
    )
}

@Composable
private fun CardFrame(card: CardDetection) {
    Canvas(Modifier.fillMaxSize()) {
        val top = card.nameBox.top.toFloat()
        val bottom = card.numberBox.bottom.toFloat()
        val h = (bottom - top) * 1.06f
        val w = h * 0.716f
        val left = minOf(card.nameBox.left.toFloat(), card.numberBox.left.toFloat()) - w * 0.07f
        val t = top - h * 0.035f
        val corner = w * 0.12f
        val stroke = Stroke(width = 4.dp.toPx())
        val pts = listOf(
            Offset(left, t) to Pair(1f, 1f),
            Offset(left + w, t) to Pair(-1f, 1f),
            Offset(left, t + h) to Pair(1f, -1f),
            Offset(left + w, t + h) to Pair(-1f, -1f),
        )
        pts.forEach { (p, d) ->
            drawLine(PikaYellow, p, Offset(p.x + corner * d.first, p.y), stroke.width)
            drawLine(PikaYellow, p, Offset(p.x, p.y + corner * d.second), stroke.width)
        }
        drawRect(
            PikaYellow.copy(alpha = 0.9f),
            topLeft = Offset(card.numberBox.left.toFloat(), card.numberBox.top.toFloat()),
            size = androidx.compose.ui.geometry.Size(card.numberBox.width().toFloat(), card.numberBox.height().toFloat()),
            style = Stroke(2.dp.toPx()),
        )
    }
}

@Composable
private fun PriceBubble(
    card: CardDetection,
    lookup: Lookup?,
    currency: Currency,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .background(Color(0xE6101418), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (lookup) {
            null, Lookup.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = PikaYellow)
                Spacer(Modifier.size(8.dp))
                Text("Checking ${card.name} ${card.printedNumber}…", color = Color.White, fontSize = 14.sp)
            }
            Lookup.NotFound -> {
                Text("${card.name} ${card.printedNumber}", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Not found on PriceCharting", color = Color(0xFFFFB4A9), fontSize = 13.sp)
                Text("Retry", color = LinkBlue, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onRetry))
            }
            is Lookup.Found -> {
                val candidate = lookup.candidates[lookup.index]
                val price = lookup.price
                Text(
                    price?.title ?: candidate.title,
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    price?.set ?: candidate.set,
                    color = Color(0xFFB0B8C4), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("PSA 10  ", color = PikaYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    when {
                        lookup.loadingPrice -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PikaYellow)
                        price == null -> Text("Unavailable", color = Color.White, fontSize = 16.sp)
                        else -> Text(currency.format(price.psa10Usd), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                    }
                }
                if (price?.ungradedUsd != null) {
                    Text("Ungraded ${currency.format(price.ungradedUsd)}", color = Color(0xFFB0B8C4), fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check in Pricecharting here",
                    color = LinkBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        val url = price?.url ?: PriceCharting.toSpanish(candidate.url)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                )
                if (lookup.candidates.size > 1) {
                    Text(
                        "Not this set? Match ${lookup.index + 1}/${lookup.candidates.size} ›",
                        color = Color(0xFFB0B8C4), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onNext),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(hint: String, torch: Boolean, onTorch: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Pika Checker", color = PikaYellow, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("v${BuildConfig.VERSION_NAME} · PSA 10 prices in €", color = Color(0xFFB0B8C4), fontSize = 11.sp)
            }
            TextButton(onClick = onTorch) { Text(if (torch) "Flash on" else "Flash off", color = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Text(
            hint,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 24.dp)
                .background(Color(0x99000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Shows the hidden PriceCharting WebView only when Cloudflare wants a human check. */
@Composable
private fun VerificationOverlay(priceCharting: PriceCharting) {
    val needs by priceCharting.needsVerification.collectAsState()
    Box(if (needs) Modifier.fillMaxSize().background(Color.Black) else Modifier.size(1.dp).alpha(0f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (needs) {
                Text(
                    "PriceCharting wants to confirm you're human. Complete the check below once and scanning will continue.",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                )
            }
            AndroidView(
                factory = { priceCharting.webView.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun UpdatePrompt(updater: Updater) {
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(Unit) { release = updater.checkForUpdate() }

    // Returning from the "install unknown apps" settings screen.
    LaunchedEffect(awaitingPermission) {
        if (!awaitingPermission) return@LaunchedEffect
        while (!updater.canInstall()) delay(500)
        awaitingPermission = false
    }

    val r = release ?: return
    AlertDialog(
        onDismissRequest = { if (progress < 0) release = null },
        title = { Text("Update available: v${r.version}") },
        text = {
            Column {
                Text("You have v${BuildConfig.VERSION_NAME}. Install the new version of Pika Checker?")
                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(r.notes.take(400), fontSize = 12.sp, color = Color(0xFFB0B8C4))
                }
                if (progress >= 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 12.sp) }
                if (awaitingPermission) Text("Allow \"Install unknown apps\" for Pika Checker, then come back.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = progress < 0 || progress >= 1f, onClick = {
                if (!updater.canInstall()) {
                    awaitingPermission = true
                    updater.openInstallPermissionSettings()
                    return@TextButton
                }
                downloaded?.let { updater.install(it); return@TextButton }
                error = null
                progress = 0f
                scope.launch {
                    runCatching { updater.download(r) { p -> progress = p } }
                        .onSuccess { progress = 1f; downloaded = it; updater.install(it) }
                        .onFailure { progress = -1f; error = it.message ?: "Download failed" }
                }
            }) { Text(if (progress >= 1f) "Install" else "Update") }
        },
        dismissButton = {
            TextButton(enabled = progress < 0, onClick = { release = null }) { Text("Later") }
        },
    )
}
