package local.edgebeam

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ThrowScreen(state: UiState, actions: UiActions, lang: String, close: () -> Unit) {
    val source = remember { state.stage } ?: return
    val peer = state.peers.find { it.id == state.selected }
    var sent by remember { mutableStateOf(state.busy) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    val recovered = remember { state.busy }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    val bounds = remember { arrayOf(Rect.Zero) }
    var root by remember { mutableStateOf(Offset.Zero) }
    var flight by remember { mutableStateOf<ThrowPreview?>(null) }
    val tracker = remember { VelocityTracker() }
    fun t(key: String) = UiText.text(lang, key)

    LaunchedEffect(state.busy, state.status) {
        if (recovered && sent && !state.busy)
            result = state.status == "Delivered · integrity verified"
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
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { root = it.positionInRoot() }
            ) {
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
                                "AppSEND",
                                fontSize = 20.sp,
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
                                "AppSEND",
                                fontSize = 20.sp,
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
                                t("throwSending"),
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
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("AppSEND", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            TextButton(onClick = close) {
                                Text(t("close"), color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                t("throwTitle"),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                t("throwInstruction"),
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                lineHeight = 20.sp,
                            )
                            Spacer(Modifier.height(44.dp))
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 180.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
                                        .graphicsLayer {
                                            translationX = drag.x
                                            translationY = drag.y
                                            rotationZ = drag.x * 0.025f
                                        }
                                        .pointerInput(state.selected, state.busy, peer?.id) {
                                            if (!state.busy && peer != null)
                                                detectDragGestures(
                                                    onDragStart = {
                                                        drag = Offset.Zero
                                                        origin = bounds[0]
                                                        tracker.resetTracking()
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        drag += amount
                                                        tracker.addPosition(
                                                            change.uptimeMillis,
                                                            change.position,
                                                        )
                                                    },
                                                    onDragCancel = { drag = Offset.Zero },
                                                    onDragEnd = {
                                                        if (
                                                            isFileThrow(
                                                                drag.x,
                                                                drag.y,
                                                                tracker.calculateVelocity().y,
                                                                density,
                                                            )
                                                        ) {
                                                            flight =
                                                                ThrowPreview(
                                                                    System.nanoTime(),
                                                                    source.name,
                                                                    origin.translate(drag - root),
                                                                    detail =
                                                                        formatSize(source.size, lang),
                                                                    rotation = drag.x * 0.025f,
                                                                )
                                                            sent = true
                                                            drag = Offset.Zero
                                                            actions.throwFile { result = it }
                                                        } else drag = Offset.Zero
                                                    },
                                                )
                                        }
                                        .background(Color.White, RoundedCornerShape(24.dp))
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    FileMark(false, Modifier.size(42.dp), Color.Black)
                                    Text(
                                        source.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        formatSize(source.size, lang),
                                        fontSize = 12.sp,
                                        color = Color.Black.copy(alpha = 0.5f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(36.dp))
                            Text(
                                t("throwTo") + (peer?.name ?: t("chooseDevice")),
                                color = Accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Text(
                            "↑",
                            fontSize = 28.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
                        )
                    }
                }
                flight?.let { item -> LocalThrowPreview(item) { if (flight == item) flight = null } }
            }
        }
    }
}
