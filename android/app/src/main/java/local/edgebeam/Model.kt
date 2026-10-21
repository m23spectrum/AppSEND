package local.edgebeam

import android.app.Application
import android.os.Build
import androidx.core.content.edit
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Source(
    val name: String,
    val size: Long,
    val mime: String,
    val kind: String,
    val temporaryFile: File? = null,
    val stream: () -> InputStream,
)

data class Consent(
    val name: String,
    val size: Long,
    val peer: String,
    val code: String,
    val incoming: Boolean,
)

data class Animation(
    val at: Long,
    val incoming: Boolean,
    val name: String,
    val motion: String = "plane",
    val departureShown: Boolean = false,
)

data class Received(
    val file: File,
    val mime: String,
    val text: String?,
    val size: Long = file.length(),
)

data class UiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val autoReceive: Boolean = true,
    val peers: List<Peer> = emptyList(),
    val selected: String? = null,
    val stage: Source? = null,
    val consent: Consent? = null,
    val status: String = "Starting LAN services…",
    val progress: Float = 0f,
    val animation: Animation? = null,
    val received: Received? = null,
    val history: List<Received> = emptyList(),
    val armed: Boolean = false,
    val motion: String = "plane",
    val language: String? = null,
)

class BeamApp : Application() {
    val state = MutableStateFlow(UiState())
    @Volatile var engine: Engine? = null
    val name: String
        get() = Build.MODEL.take(64)

    val id: String by lazy {
        val p = getSharedPreferences("identity", MODE_PRIVATE)
        p.getString("id", null)
            ?: UUID.randomUUID().toString().also { id -> p.edit { putString("id", id) } }
    }

    fun status(message: String) {
        state.update { it.copy(status = message) }
    }

    fun act(block: suspend Engine.() -> Unit) {
        val e = engine
        if (e == null) {
            status("Start LAN availability first")
            return
        }
        e.action(block)
    }
}
