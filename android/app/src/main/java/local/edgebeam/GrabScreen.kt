package local.edgebeam

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun GrabScreen(state: UiState, actions: UiActions, lang: String, close: () -> Unit) {
    val source = remember { state.stage } ?: return
    val target = remember { state.selected }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    fun t(key: String) = UiText.text(lang, key)

    fun cameraAllowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var permission by remember { mutableStateOf(cameraAllowed()) }
    var resumed by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }

    var status by remember { mutableStateOf("grabFist") }
    var focus by remember { mutableStateOf<HandFocus?>(null) }
    val controller = remember { arrayOfNulls<GrabCamera>(1) }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(160)) }

    var sent by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var flight by remember { mutableStateOf<ThrowPreview?>(null) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val peer = state.peers.find { it.id == target && it.platform != "Android" }
    val held = status == "grabHeld"
    val scale =
        animateFloatAsState(if (held) 0.92f else 1f, spring(stiffness = 800f), label = "grip")
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)

    LaunchedEffect(Unit) { if (!permission) request.launch(Manifest.permission.CAMERA) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            permission = cameraAllowed()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(held) {
        if (held) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Night,
            contentColor = Color.White,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (result != null) {
                    Column(
                        Modifier.fillMaxSize()
                            .systemBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t("grab"),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            TextButton(onClick = close) {
                                Text(t("close"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }

                        Spacer(Modifier.weight(0.4f))

                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (result == true) {
                                DeliveryCheck(Modifier.size(80.dp))
                                Text(
                                    t("transferSuccess"),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    t("integrityVerified"),
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            } else {
                                DeliveryCross(Modifier.size(80.dp))
                                Text(
                                    UiText.status(lang, state.status).ifEmpty { t("transferCancelled") },
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = Color.White.copy(alpha = 0.1f),
                                thickness = 0.5.dp,
                            )

                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(t("recipientLabel"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                                    Text(
                                        peer?.name ?: t("chooseDevice"),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(t("fileLabel"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                                    Text(
                                        source.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(0.6f))

                        if (result == true) {
                            Button(
                                onClick = close,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Night,
                                ),
                            ) {
                                Text(t("finish"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    onClick = close,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                ) {
                                    Text(t("close"), color = Color.White, fontSize = 14.sp)
                                }
                                Button(
                                    onClick = {
                                        sent = false
                                        result = null
                                        status = "grabFist"
                                        attempt++
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Night,
                                    ),
                                ) {
                                    Text(t("retry"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else if (sent) {
                    Column(
                        Modifier.fillMaxSize()
                            .systemBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t("grab"),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            TextButton(onClick = close) {
                                Text(t("close"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }

                        Spacer(Modifier.weight(0.4f))

                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Accent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                t("grabSending"),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = Accent,
                                trackColor = Color.White.copy(alpha = 0.1f),
                            )
                            Text(
                                (peer?.name ?: t("chooseDevice")),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }

                        Spacer(Modifier.weight(0.6f))

                        TextButton(
                            onClick = actions.cancel,
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            Text(t("cancel"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize()
                            .systemBarsPadding()
                            .padding(horizontal = 24.dp)
                            .graphicsLayer {
                                alpha = entrance.value
                                scaleX = 0.975f + 0.025f * entrance.value
                                scaleY = scaleX
                            }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t("grab"),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            TextButton(onClick = close) {
                                Text(t("close"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }

                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                peer?.name ?: t("grabPcOnly"),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )

                            val ready =
                                permission &&
                                    resumed &&
                                    !sent &&
                                    !state.busy &&
                                    state.running &&
                                    peer != null &&
                                    state.stage === source &&
                                    state.selected == target

                            Box(
                                Modifier.widthIn(max = 320.dp)
                                    .heightIn(max = 340.dp)
                                    .aspectRatio(3f / 4f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(
                                        1.5.dp,
                                        if (held) Accent else Color.White.copy(alpha = 0.2f),
                                        RoundedCornerShape(24.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (ready) {
                                    key(attempt) {
                                        val preview = remember {
                                            PreviewView(context).apply {
                                                implementationMode =
                                                    PreviewView.ImplementationMode.COMPATIBLE
                                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                            }
                                        }
                                        AndroidView(
                                            factory = { preview },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        DisposableEffect(preview, owner) {
                                            val camera =
                                                GrabCamera(
                                                    context,
                                                    owner,
                                                    preview,
                                                    { status = it },
                                                    {
                                                        val current = latestState
                                                        if (
                                                            !sent &&
                                                                current.stage === source &&
                                                                current.selected == target &&
                                                                !current.busy &&
                                                                current.running &&
                                                                current.peers.any {
                                                                    it.id == target &&
                                                                        it.platform != "Android"
                                                                }
                                                        ) {
                                                            sent = true
                                                            flight =
                                                                ThrowPreview(
                                                                    System.nanoTime(),
                                                                    source.name,
                                                                    coordinates[0]
                                                                        ?.takeIf { it.isAttached }
                                                                        ?.boundsInRoot() ?: Rect.Zero,
                                                                    formatSize(source.size, lang),
                                                                    0f,
                                                                    compact = true,
                                                                    initialScale = scale.value,
                                                                )
                                                            latestActions.grabFile(target!!, source) {
                                                                result = it
                                                            }
                                                        }
                                                    },
                                                    { focus = it },
                                                )
                                            controller[0] = camera
                                            camera.start()
                                            onDispose {
                                                controller[0] = null
                                                camera.close()
                                                focus = null
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        t(if (!permission) "grabPermission" else "grabPcOnly"),
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(24.dp),
                                    )
                                }
                                if (ready) GrabViewfinder(held, focus, Modifier.fillMaxSize())
                            }

                            AnimatedContent(
                                targetState = status,
                                transitionSpec = {
                                    (fadeIn(tween(120)) + slideInVertically { it / 3 }) togetherWith
                                        fadeOut(tween(80))
                                },
                                label = "gesture-status",
                            ) { message ->
                                Text(
                                    t(message),
                                    color = if (held) Accent else Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = held,
                                    enter = fadeIn(tween(100)),
                                    exit = fadeOut(tween(70)),
                                ) {
                                    TextButton(onClick = { controller[0]?.resetGrip() }) {
                                        Text(
                                            t("grabReset"),
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }

                            if (!permission) {
                                Button(
                                    onClick = { request.launch(Manifest.permission.CAMERA) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Night,
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(t("grabEnableCamera"), fontWeight = FontWeight.Medium)
                                }
                            }

                            if (status in listOf("grabCameraError", "grabModelError")) {
                                TextButton(
                                    onClick = {
                                        status = "grabFist"
                                        attempt++
                                    }
                                ) {
                                    Text(t("retry"), color = Color.White)
                                }
                            }

                            TransferFileCard(
                                source.name,
                                formatSize(source.size, lang),
                                Modifier.fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = scale.value
                                        scaleY = scale.value
                                        translationY = -8.dp.toPx() * (1 - scale.value) / 0.1f
                                    }
                                    .onGloballyPositioned { coordinates[0] = it },
                            )

                            Text(
                                t("grabInstructions"),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }

                        Text(
                            t("grabPrivacy"),
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.padding(vertical = 14.dp).align(Alignment.CenterHorizontally),
                        )
                    }
                }

                flight?.let { item -> LocalThrowPreview(item) { if (flight == item) flight = null } }
            }
        }
    }
}
