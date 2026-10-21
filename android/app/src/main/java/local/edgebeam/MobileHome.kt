package local.edgebeam

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

private val Muted = Color.White.copy(alpha = 0.55f)
private val Hairline = Color.White.copy(alpha = 0.12f)

@Composable
internal fun MobileHome(
    state: UiState,
    name: String,
    tab: String,
    onTab: (String) -> Unit,
    settings: () -> Unit,
    actions: UiActions,
    onSend: () -> Unit,
    detail: (Received) -> Unit,
    overlay: @Composable () -> Unit,
) {
    val lang = state.language ?: "en"
    fun t(key: String) = UiText.text(lang, key)
    val busy = state.busy || state.consent != null
    val peer = state.peers.find { it.id == state.selected }
    val edgePulse = rememberEdgePulse(state.animation)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Night,
        contentColor = Color.White,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // Top App Bar
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 22.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogoMark(Modifier.size(24.dp))
                    Text(
                        "AppSEND",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Box(
                            Modifier.size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (state.running) Color(0xff4ade80) else Color.White.copy(alpha = 0.3f))
                        )
                        Text(
                            t(if (state.running) "onlineShort" else "offline"),
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    IconButton(
                        onClick = settings,
                        modifier = Modifier.semantics { contentDescription = t("settings") },
                    ) {
                        Glyph("settings", color = Color.White)
                    }
                }

                // Main Tab Content Area
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInHorizontally { it / 24 }) togetherWith
                            fadeOut(tween(90))
                    },
                    label = "mobile-page",
                ) { page ->
                    LazyColumn(
                        Modifier.fillMaxSize().graphicsLayer {
                            val pulse = edgePulse.floatValue
                            scaleX = 1 - 0.012f * pulse
                            scaleY = scaleX
                        },
                        contentPadding =
                            PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item("heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    t(
                                        when (page) {
                                            "receive" -> "receiveTitle"
                                            "history" -> "history"
                                            else -> "transfer"
                                        }
                                    ),
                                    fontSize = 28.sp,
                                    lineHeight = 34.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    letterSpacing = (-0.6).sp,
                                )
                                Text(
                                    t(
                                        when (page) {
                                            "receive" ->
                                                if (state.autoReceive) "receiveHint"
                                                else "receiveManualHint"
                                            "history" -> "received"
                                            else -> "transferHint"
                                        }
                                    ),
                                    color = Muted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }

                        if (page == "send")
                            item("file") { MobileFilePicker(state, busy, actions, lang) }

                        if (page == "receive")
                            item("identity") {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(15.dp),
                                ) {
                                    Box(
                                        Modifier.size(76.dp)
                                            .border(1.dp, Hairline, RoundedCornerShape(24.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Glyph("phone", Modifier.size(34.dp), Color.White)
                                    }
                                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    OutlinedButton(
                                        onClick = actions.availability,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Hairline),
                                    ) {
                                        Text(
                                            t(if (state.running) "stop" else "start"),
                                            color = Color.White,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }

                        if (page != "history") {
                            item("peers-title") {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        t("devices"),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                    )
                                    Text(
                                        state.peers.size.toString(),
                                        fontSize = 12.sp,
                                        color = Muted,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (state.peers.isEmpty())
                                item("empty") {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color.White.copy(alpha = 0.03f))
                                            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                                            .padding(horizontal = 18.dp, vertical = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(15.dp),
                                    ) {
                                        if (state.running)
                                            CircularProgressIndicator(
                                                Modifier.size(20.dp),
                                                color = Color.White.copy(alpha = 0.7f),
                                                strokeWidth = 2.dp,
                                            )
                                        else Glyph("device", Modifier.size(22.dp), Muted)
                                        Text(
                                            t("empty"),
                                            color = Muted,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                }
                            items(state.peers, key = { "peer-${it.id}" }) { device ->
                                val selected = state.selected == device.id
                                val background by
                                    animateColorAsState(
                                        if (selected) Color.White.copy(alpha = 0.10f)
                                        else Color.White.copy(alpha = 0.035f),
                                        tween(150),
                                        label = "peer-choice",
                                    )
                                Row(
                                    Modifier.fillMaxWidth()
                                        .animateItem()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(background)
                                        .border(
                                            if (selected) 1.5.dp else 1.dp,
                                            if (selected) Color.White.copy(alpha = 0.6f) else Hairline,
                                            RoundedCornerShape(18.dp),
                                        )
                                        .clickable(enabled = !busy) { actions.select(device.id) }
                                        .padding(horizontal = 18.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                                ) {
                                    Glyph(
                                        if (device.platform == "Android") "phone" else "device",
                                        Modifier.size(26.dp),
                                        Color.White,
                                    )
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            device.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                        )
                                        Text(device.platform, color = Muted, fontSize = 12.sp)
                                    }
                                    AnimatedVisibility(
                                        selected,
                                        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.75f),
                                        exit = fadeOut(tween(80)),
                                    ) {
                                        Text("✓", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (page == "send")
                                item("methods") {
                                    MobileMethods(state.motion, !busy, actions.mode, lang)
                                }
                        } else {
                            if (state.history.isEmpty())
                                item("no-history") {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 55.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        Glyph("history", Modifier.size(35.dp), Muted)
                                        Text(t("noFiles"), color = Muted, fontSize = 13.sp)
                                    }
                                }
                            items(state.history, key = { it.file.absolutePath }) { entry ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .animateItem()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                                        .clickable { detail(entry) }
                                        .padding(vertical = 16.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    FileMark(false, Modifier.size(28.dp), Color.White)
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            entry.file.name.replace(Regex("^[a-f0-9]{8}-"), ""),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                        )
                                        Text(
                                            formatSize(entry.size, lang),
                                            fontSize = 12.sp,
                                            color = Muted,
                                        )
                                    }
                                    Text("↗", fontSize = 18.sp, color = Muted)
                                }
                            }
                        }
                    }
                }

                // Pinned Action Area and Bottom Navigation
                if (tab != "history") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val enabled =
                            state.running &&
                                peer != null &&
                                !busy &&
                                ((tab == "receive" && state.stage == null) ||
                                    (tab == "send" && state.stage != null)) &&
                                (tab == "receive" ||
                                    state.motion != "grab" ||
                                    peer.platform != "Android")

                        val buttonText = when {
                            tab == "receive" -> t("receiveAction")
                            peer == null -> t("selectDeviceFirst")
                            state.stage == null -> t("selectFileFirst")
                            state.motion == "grab" -> t("openGrab")
                            state.motion == "plane" -> t("openThrow")
                            else -> t("send") + (peer.name.let { " · $it" })
                        }
                        val buttonIcon = when {
                            tab == "receive" -> "receive"
                            state.motion == "grab" -> "grab"
                            state.motion == "plane" -> "plane"
                            else -> "send"
                        }

                        Button(
                            onClick = if (tab == "receive") actions.send else onSend,
                            enabled = enabled,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                                ),
                        ) {
                            Text(
                                buttonText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Glyph(
                                buttonIcon,
                                Modifier.size(20.dp),
                                if (enabled) Color.Black else Color.White.copy(alpha = 0.35f),
                            )
                        }
                        TransferStatus(state, lang, busy, actions.cancel)
                    }
                    if (tab == "send" && state.motion == "edge")
                        EdgeHold(state.running && peer != null && !busy, t("hold"), actions.send)
                }

                // Sleek Bottom Navigation Bar
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(5.dp)
                ) {
                    listOf("send" to "navSend", "receive" to "navReceive", "history" to "history")
                        .forEach { (value, label) ->
                            val selected = tab == value
                            val fill by
                                animateColorAsState(
                                    if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                    tween(160),
                                    label = "navigation",
                                )
                            Column(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(fill)
                                    .clickable { onTab(value) }
                                    .padding(vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Glyph(value, Modifier.size(20.dp), if (selected) Color.White else Muted)
                                Text(
                                    t(label),
                                    fontSize = 11.sp,
                                    color = if (selected) Color.White else Muted,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                }
            }
            overlay()
        }
    }
}

@Composable
private fun MobileFilePicker(state: UiState, busy: Boolean, actions: UiActions, lang: String) {
    fun t(key: String) = UiText.text(lang, key)
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        AnimatedContent(
            targetState = state.stage,
            transitionSpec = {
                (fadeIn(tween(150)) +
                    scaleIn(
                        initialScale = 0.97f,
                        animationSpec = spring(stiffness = 550f),
                    )) togetherWith fadeOut(tween(80))
            },
            label = "staged-sheet",
        ) { source ->
            if (source == null)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.035f))
                        .border(1.dp, Hairline, RoundedCornerShape(22.dp))
                        .clickable(enabled = !busy, onClick = actions.choose)
                        .padding(horizontal = 20.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("+", fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White)
                    Text(t("add"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Text(t("fileHint"), fontSize = 12.sp, color = Muted)
                }
            else
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(22.dp))
                        .padding(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FileMark(false, Modifier.size(34.dp), Color.Black)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            source.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatSize(source.size, lang),
                            fontSize = 12.sp,
                            color = Color.Black.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(
                        onClick = actions.clear,
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = t("clear") },
                    ) {
                        Text("×", fontSize = 24.sp, color = Color.Black.copy(alpha = 0.6f))
                    }
                }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = actions.choose,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Hairline),
            ) {
                Text(
                    t("choose"),
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = actions.paste,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(t("pasteShort"), fontSize = 12.sp, color = Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MobileMethods(mode: String, enabled: Boolean, choose: (String) -> Unit, lang: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(UiText.text(lang, "mode"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("edge" to "phoneEdge", "plane" to "plane", "grab" to "grab").forEach { (value, key) ->
                val selected = mode == value
                val color by
                    animateColorAsState(
                        if (selected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                        tween(160),
                        label = "method",
                    )
                val ink = if (selected) Color.White else Muted
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(color)
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) Color.White.copy(alpha = 0.6f) else Hairline,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable(enabled = enabled) { choose(value) }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (value == "plane") FileMark(true, Modifier.size(20.dp), ink)
                    else Glyph(value, Modifier.size(20.dp), ink)
                    Text(
                        UiText.text(lang, key),
                        fontSize = 12.sp,
                        color = ink,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(tween(130)) togetherWith fadeOut(tween(70)) },
            label = "method-hint",
        ) { value ->
            Text(
                UiText.text(
                    lang,
                    when (value) {
                        "grab" -> "grabHint"
                        "plane" -> "planeHint"
                        else -> "phoneEdgeHint"
                    },
                ),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Muted,
            )
        }
    }
}
