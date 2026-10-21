package local.edgebeam

import android.content.*
import android.net.Uri
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app
        get() = application as BeamApp

    private lateinit var sensors: ApproachSensors
    private val pick =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                app.act { stage(it) }
            }
        }
    private val pending = ArrayDeque<Uri>()
    private var sharedIntentHandled = false

    private fun startAvailability() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, BeamService::class.java))
        } catch (e: Exception) {
            app.status("Cannot start availability: ${e.message}")
        }
    }

    private fun stopAvailability() {
        val service = Intent(this, BeamService::class.java)
        stopService(service)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val preferences = getSharedPreferences("ui", MODE_PRIVATE)
        val savedLanguage =
            preferences.getString("language", null)?.takeIf { it in listOf("ru", "en") }
        val savedMotion =
            preferences.getString("motion", "edge").takeIf { it in listOf("plane", "grab") }
                ?: "edge"
        app.state.update {
            it.copy(
                language = savedLanguage,
                motion = savedMotion,
                armed = savedMotion == "edge",
                autoReceive = preferences.getBoolean("autoReceive", true),
            )
        }
        sensors =
            ApproachSensors(
                this,
                {
                    !app.state.value.busy &&
                        app.state.value.armed &&
                        app.state.value.running &&
                        app.state.value.motion == "edge" &&
                        app.state.value.selected != null
                },
                { app.act { trigger() } },
            )
        setContent {
            val state by app.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.language) {
                if (state.language != null) {
                    if (app.engine == null) startAvailability()
                    if (!sharedIntentHandled) {
                        sharedIntentHandled = true
                        if (savedInstanceState == null) handleShare(intent)
                    }
                }
            }
            AppSendScreen(
                state = state,
                name = app.name,
                queueSize = pending.size,
                actions =
                    UiActions(
                        choose = { pick.launch(arrayOf("*/*")) },
                        paste = {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            val text =
                                clip.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(this@MainActivity)
                                    ?.toString()
                            if (text != null) app.act { stageText(text) }
                            else app.status("Clipboard is empty")
                        },
                        send = { app.act { trigger() } },
                        throwFile = { finished ->
                            val owner = app
                            val transferEngine = owner.engine
                            if (transferEngine == null) {
                                owner.status("Start LAN availability first")
                                finished(false)
                            } else
                                transferEngine.action {
                                    val success =
                                        runCatching {
                                                trigger(departureShown = true)
                                                owner.state.value.status ==
                                                    "Delivered · integrity verified"
                                            }
                                            .getOrElse {
                                                owner.status(it.message ?: "Operation failed")
                                                false
                                            }
                                    kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.NonCancellable +
                                            kotlinx.coroutines.Dispatchers.Main.immediate
                                    ) {
                                        finished(success)
                                    }
                                }
                        },
                        grabFile = { peerId, source, finished ->
                            val owner = app
                            val transferEngine = owner.engine
                            if (transferEngine == null) {
                                owner.status("Start LAN availability first")
                                finished(false)
                            } else
                                transferEngine.action {
                                    val success =
                                        runCatching {
                                                trigger(
                                                    departureShown = true,
                                                    expectedPeerId = peerId,
                                                    expectedSource = source,
                                                )
                                                owner.state.value.status ==
                                                    "Delivered · integrity verified"
                                            }
                                            .getOrElse {
                                                owner.status(it.message ?: "Operation failed")
                                                false
                                            }
                                    kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.NonCancellable +
                                            kotlinx.coroutines.Dispatchers.Main.immediate
                                    ) {
                                        finished(success)
                                    }
                                }
                        },
                        cancel = { app.act { cancel() } },
                        approve = { app.act { approve() } },
                        select = { id -> app.state.update { it.copy(selected = id) } },
                        autoReceive = { value ->
                            preferences.edit { putBoolean("autoReceive", value) }
                            app.state.update { it.copy(autoReceive = value) }
                        },
                        language = { value ->
                            if (value in listOf("ru", "en")) {
                                preferences.edit { putString("language", value) }
                                app.state.update { it.copy(language = value) }
                            }
                        },
                        mode = { value ->
                            if (value in listOf("plane", "edge", "grab")) {
                                preferences.edit { putString("motion", value) }
                                app.state.update {
                                    it.copy(motion = value, armed = value == "edge")
                                }
                            }
                        },
                        clear = { app.act { clearStage() } },
                        next = {
                            if (pending.isNotEmpty()) {
                                val uri = pending.removeFirst()
                                app.act { stage(uri) }
                            }
                        },
                        availability = {
                            if (state.running) stopAvailability() else startAvailability()
                        },
                        open = { received -> export(received, false) },
                        share = { received -> export(received, true) },
                        copy = { text ->
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("AppSEND", text))
                        },
                    ),
                overlay = { state.animation?.let { HandoffOverlay(it) } },
            )
        }
    }

    private fun export(r: Received, share: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", r.file)
        val i =
            if (share) Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, r.mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("AppSEND file", uri)
        try {
            startActivity(
                Intent.createChooser(
                    i,
                    UiText.text(app.state.value.language ?: "en", if (share) "share" else "open"),
                )
            )
        } catch (e: Exception) {
            app.status("No application can open this file: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i?.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val uris =
            if (i?.action == Intent.ACTION_SEND_MULTIPLE)
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(i?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val text = i?.getStringExtra(Intent.EXTRA_TEXT)
        pending.addAll(uris.drop(1))
        lifecycleScope.launch {
            var attempts = 0
            while (app.engine == null && attempts++ < 100) delay(50)
            val uri = uris.firstOrNull()
            if (uri != null) app.act { stage(uri) }
            else if (!text.isNullOrEmpty()) app.act { stageText(text) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (app.state.value.language != null) handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        sensors.start()
    }

    override fun onPause() {
        sensors.stop()
        super.onPause()
    }
}
