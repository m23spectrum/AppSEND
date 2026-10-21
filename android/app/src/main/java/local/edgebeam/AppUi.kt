package local.edgebeam

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

internal val Accent = Color.White
internal val Night = Color.Black
private val Secondary = Color.White.copy(alpha = 0.56f)
private val Line = Color.White.copy(alpha = 0.12f)

data class UiActions(
    val choose: () -> Unit,
    val paste: () -> Unit,
    val send: () -> Unit,
    val throwFile: ((Boolean) -> Unit) -> Unit,
    val grabFile: (String, Source, (Boolean) -> Unit) -> Unit,
    val autoReceive: (Boolean) -> Unit,
    val cancel: () -> Unit,
    val approve: () -> Unit,
    val select: (String) -> Unit,
    val clear: () -> Unit,
    val next: () -> Unit,
    val availability: () -> Unit,
    val open: (Received) -> Unit,
    val share: (Received) -> Unit,
    val copy: (String) -> Unit,
    val language: (String) -> Unit,
    val mode: (String) -> Unit,
)

@Composable
internal fun LogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 32, size.height / 32, pivot = Offset.Zero) {
            val upper =
                Path().apply {
                    moveTo(6f, 12f)
                    lineTo(6f, 5f)
                    lineTo(26f, 5f)
                    lineTo(26f, 12f)
                }
            val lower =
                Path().apply {
                    moveTo(6f, 20f)
                    lineTo(6f, 27f)
                    lineTo(26f, 27f)
                    lineTo(26f, 20f)
                }
            drawPath(
                upper,
                Color.White,
                style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                lower,
                Accent,
                style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawLine(Accent, Offset(12f, 16f), Offset(20f, 16f), 2.4f, StrokeCap.Round)
        }
    }
}

@Composable
internal fun FileMark(plane: Boolean, modifier: Modifier = Modifier, color: Color = Accent) {
    Canvas(modifier) {
        scale(size.width / 24, size.height / 24, pivot = Offset.Zero) {
            val p =
                if (plane)
                    Path().apply {
                        moveTo(21f, 3f)
                        lineTo(15f, 21f)
                        lineTo(11f, 13f)
                        lineTo(3f, 9f)
                        close()
                        moveTo(11f, 13f)
                        lineTo(21f, 3f)
                    }
                else
                    Path().apply {
                        moveTo(14f, 3f)
                        lineTo(6f, 3f)
                        lineTo(6f, 21f)
                        lineTo(18f, 21f)
                        lineTo(18f, 7f)
                        close()
                        moveTo(14f, 3f)
                        lineTo(14f, 8f)
                        lineTo(18f, 8f)
                        moveTo(9f, 12f)
                        lineTo(15f, 12f)
                        moveTo(9f, 16f)
                        lineTo(13f, 16f)
                    }
            drawPath(p, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun LanguageChoice(choose: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoMark(Modifier.size(48.dp))
        Spacer(Modifier.height(22.dp))
        Text("AppSEND", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(14.dp))
        Text("Выберите язык / Choose language", fontSize = 13.sp, color = Secondary)
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(
                onClick = { choose("ru") },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.height(48.dp),
            ) {
                Text("Русский", color = Color.White, fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = { choose("en") },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.height(48.dp),
            ) {
                Text("English", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
internal fun Glyph(kind: String, modifier: Modifier = Modifier.size(24.dp), color: Color = Accent) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path =
                Path().apply {
                    when (kind) {
                        "send" -> {
                            moveTo(5f, 19f)
                            lineTo(19f, 5f)
                            moveTo(6f, 5f)
                            lineTo(19f, 5f)
                            lineTo(19f, 18f)
                        }
                        "receive" -> {
                            moveTo(19f, 5f)
                            lineTo(5f, 19f)
                            moveTo(5f, 6f)
                            lineTo(5f, 19f)
                            lineTo(18f, 19f)
                        }
                        "device" -> {
                            moveTo(3f, 4f)
                            lineTo(21f, 4f)
                            lineTo(21f, 17f)
                            lineTo(3f, 17f)
                            close()
                            moveTo(12f, 17f)
                            lineTo(12f, 21f)
                            moveTo(7f, 21f)
                            lineTo(17f, 21f)
                        }
                        "phone" -> {
                            moveTo(7f, 2f)
                            lineTo(17f, 2f)
                            lineTo(17f, 22f)
                            lineTo(7f, 22f)
                            close()
                            moveTo(10f, 5f)
                            lineTo(14f, 5f)
                            moveTo(11f, 19f)
                            lineTo(13f, 19f)
                        }
                        "history" -> {
                            moveTo(12f, 6f)
                            lineTo(12f, 12f)
                            lineTo(16f, 14f)
                        }
                        "settings" -> {
                            moveTo(3f, 7f)
                            lineTo(21f, 7f)
                            moveTo(3f, 17f)
                            lineTo(21f, 17f)
                        }
                        "grab" -> {
                            moveTo(5f, 13f)
                            lineTo(5f, 8f)
                            lineTo(8f, 8f)
                            lineTo(8f, 4f)
                            lineTo(11f, 4f)
                            lineTo(11f, 3f)
                            lineTo(14f, 3f)
                            lineTo(14f, 5f)
                            lineTo(17f, 5f)
                            lineTo(17f, 10f)
                            lineTo(20f, 10f)
                            lineTo(20f, 17f)
                            lineTo(16f, 21f)
                            lineTo(9f, 21f)
                            close()
                        }
                        "edge" -> {
                            moveTo(3f, 4f)
                            lineTo(21f, 4f)
                            moveTo(8f, 9f)
                            lineTo(16f, 9f)
                            lineTo(16f, 22f)
                            lineTo(8f, 22f)
                            close()
                            moveTo(12f, 1f)
                            lineTo(12f, 7f)
                        }
                    }
                }
            drawPath(
                path,
                color,
                style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (kind == "history") drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.6f))
            if (kind == "settings") {
                drawCircle(Night, 3f, Offset(8f, 7f))
                drawCircle(color, 3f, Offset(8f, 7f), style = Stroke(1.6f))
                drawCircle(Night, 3f, Offset(16f, 17f))
                drawCircle(color, 3f, Offset(16f, 17f), style = Stroke(1.6f))
            }
        }
    }
}

@Composable
fun AppSendScreen(
    state: UiState,
    name: String,
    queueSize: Int,
    actions: UiActions,
    overlay: @Composable () -> Unit,
) {
    val lang = state.language ?: "en"
    fun t(key: String) = UiText.text(lang, key)
    var tab by rememberSaveable { mutableStateOf("send") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var throwMenu by rememberSaveable { mutableStateOf(false) }
    var grabMenu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Received?>(null) }
    val font = remember { FontFamily(Font(R.font.inter)) }
    val typography =
        remember(font) {
            Typography().run {
                copy(
                    bodyLarge = bodyLarge.copy(fontFamily = font),
                    bodyMedium = bodyMedium.copy(fontFamily = font),
                    labelLarge = labelLarge.copy(fontFamily = font),
                    titleLarge = titleLarge.copy(fontFamily = font),
                    headlineSmall = headlineSmall.copy(fontFamily = font),
                )
            }
        }
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Accent,
                secondary = Color.White,
                tertiary = Color.White,
                error = Color.White,
                onSecondary = Night,
                onTertiary = Night,
                onError = Night,
                primaryContainer = Color(0xff202020),
                secondaryContainer = Color(0xff202020),
                tertiaryContainer = Color(0xff202020),
                errorContainer = Color(0xff202020),
                onPrimaryContainer = Color.White,
                onSecondaryContainer = Color.White,
                onTertiaryContainer = Color.White,
                onErrorContainer = Color.White,
                surfaceTint = Color.White,
                onPrimary = Night,
                background = Night,
                surface = Night,
                onSurface = Color.White,
                onBackground = Color.White,
                outline = Line,
            ),
        typography = typography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Night,
            contentColor = Color.White,
        ) {
            if (state.language == null) {
                LanguageChoice(actions.language)
                return@Surface
            }
            MobileHome(
                state,
                name,
                tab,
                { tab = it },
                { settings = true },
                actions,
                onSend = {
                    if (state.motion == "grab" && state.stage != null) grabMenu = true
                    else if (state.motion == "plane" && state.stage != null) throwMenu = true
                    else actions.send()
                },
                detail = { detail = it },
                overlay = overlay,
            )
            if (grabMenu && state.stage != null) GrabScreen(state, actions, lang) { grabMenu = false }
            if (throwMenu && state.stage != null)
                ThrowScreen(state, actions, lang) { throwMenu = false }
            if (settings)
                AlertDialog(
                    onDismissRequest = { settings = false },
                    containerColor = Color(0xff181818),
                    shape = RoundedCornerShape(24.dp),
                    title = { Text(t("settings"), color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(t("language"), color = Secondary, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { actions.language("ru") }) {
                                    Text("Русский", color = if (lang == "ru") Accent else Secondary, fontWeight = if (lang == "ru") FontWeight.Bold else FontWeight.Normal)
                                }
                                TextButton(onClick = { actions.language("en") }) {
                                    Text("English", color = if (lang == "en") Accent else Secondary, fontWeight = if (lang == "en") FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(t("autoReceive"), modifier = Modifier.weight(1f), fontSize = 13.sp, color = Color.White)
                                Switch(
                                    checked = state.autoReceive,
                                    onCheckedChange = actions.autoReceive,
                                )
                            }
                            Text(t("autoReceiveHint"), fontSize = 11.sp, color = Secondary)
                            Text(
                                t("sensorHint"),
                                color = Secondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                            if (queueSize > 0)
                                TextButton(onClick = actions.next) { Text(t("next") + " ($queueSize)", color = Accent) }
                            TextButton(onClick = actions.availability) {
                                Text(t(if (state.running) "stop" else "start"), color = Accent)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { settings = false }) { Text(t("close"), color = Accent) } },
                )
            detail?.let { entry ->
                AlertDialog(
                    onDismissRequest = { detail = null },
                    containerColor = Color(0xff181818),
                    shape = RoundedCornerShape(24.dp),
                    title = {
                        Text(
                            entry.file.name.replace(Regex("^[a-f0-9]{8}-"), ""),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(formatSize(entry.size, lang), color = Secondary, fontSize = 13.sp)
                            entry.text?.let {
                                Text(it.take(3000), fontSize = 12.sp, color = Color.White)
                                TextButton(onClick = { actions.copy(it) }) { Text(t("copy"), color = Accent) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { actions.open(entry) }) { Text(t("open"), color = Accent) }
                                TextButton(onClick = { actions.share(entry) }) { Text(t("share"), color = Accent) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { detail = null }) { Text(t("close"), color = Accent) } },
                )
            }
            state.consent?.let { c ->
                AlertDialog(
                    onDismissRequest = actions.cancel,
                    containerColor = Color(0xff181818),
                    shape = RoundedCornerShape(24.dp),
                    icon = { FileMark(false, Modifier.size(38.dp), Color.White) },
                    title = {
                        Text(
                            t(if (c.incoming) "receiveFrom" else "sendTo") + c.peer,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(c.name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(formatSize(c.size, lang), fontSize = 12.sp, color = Secondary)
                            Text(t("compare"), fontSize = 12.sp, color = Secondary)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = actions.approve) {
                            Text(t(if (c.incoming) "confirm" else "send"), color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = actions.cancel) { Text(t("decline"), color = Secondary) }
                    },
                )
            }
        }
    }
}

internal fun formatSize(bytes: Long, lang: String): String {
    val units = if (lang == "ru") listOf("Б", "КБ", "МБ", "ГБ") else listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < 3) {
        value /= 1024
        unit++
    }
    return (if (unit == 0) value.toLong().toString() else "%.1f".format(value)) + " " + units[unit]
}

@Composable
internal fun TransferStatus(state: UiState, lang: String, busy: Boolean, cancel: () -> Unit) {
    val hasActiveTransfer = busy || (state.progress > 0f && state.progress < 1f)
    if (!hasActiveTransfer) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.progress > 0f && state.progress < 1f)
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Accent,
                trackColor = Line,
            )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                UiText.status(lang, state.status),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Secondary,
                modifier = Modifier.weight(1f),
            )
            if (busy)
                TextButton(onClick = cancel) { Text(UiText.text(lang, "cancel"), fontSize = 12.sp, color = Accent) }
        }
    }
}

@Composable
internal fun EdgeHold(enabled: Boolean, label: String, action: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val fill by
        animateFloatAsState(
            if (pressed) 1f else 0f,
            animationSpec = tween(if (pressed) 600 else 180),
            label = "edgeHold",
        )
    Box(
        Modifier.fillMaxWidth()
            .height(50.dp)
            .background(Accent.copy(alpha = 0.035f))
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        }
                    },
                    onLongPress = {
                        if (enabled) {
                            pressed = false
                            action()
                        }
                    },
                )
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = if (enabled) Accent else Secondary)
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)) {
            drawLine(
                Accent.copy(alpha = if (enabled) 0.3f else 0.1f),
                Offset(size.width * 0.25f, 1f),
                Offset(size.width * 0.75f, 1f),
                2f,
            )
            drawLine(
                Accent,
                Offset(size.width * 0.5f - size.width * 0.25f * fill, 1f),
                Offset(size.width * 0.5f + size.width * 0.25f * fill, 1f),
                2f,
            )
        }
    }
}
