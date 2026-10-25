package local.edgebeam

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.websocket.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val CHUNK = 262144

private fun msg(type: String, vararg fields: Pair<String, Any>): JSONObject =
    JSONObject().put("type", type).apply { fields.forEach { put(it.first, it.second) } }

private fun now() = System.currentTimeMillis()

class Engine(private val app: BeamApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        HttpClient(ClientCIO) {
            install(ClientWebSockets) {
                maxFrameSize = 65536
                pingInterval = 15000
            }
        }
    private lateinit var server: ApplicationEngine
    private var discovery: Discovery? = null
    private var port = 0
    internal val listeningPort
        get() = port

    private val gate = Mutex()
    private val outgoing = Mutex()
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    @Volatile private var active: Session? = null
    private val receivedFolder = File(app.filesDir, "received").apply { mkdirs() }
    private val wake =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppSEND:transfer")
            .apply { setReferenceCounted(false) }

    private inner class Session(val ws: WebSocketSession, val host: String) {
        val me = Crypto.identity()
        val nonce = Crypto.nonce()
        var remoteCommit: String? = null
        var key: ByteArray? = null
        var code = ""
        var name = ""
        var remotePort = 0
        var offer: JSONObject? = null
        var source: Source? = null
        var incoming = false
        var approved = false
        var accepted = false
        var token: String? = null
        var syncing = false
        var pingAt = 0L
        var start = 0L
        var begun = false
        var sending = false
        var uploading = false
        var last = now()
        val created = now()
        var outbound = false
        var departureShown = false
        var failed = false
        var connection: HttpURLConnection? = null
        var stream: Closeable? = null
        var tx = 0L
        var rx = 0L
        val write = Mutex()
        val samples = mutableListOf<Pair<Long, Double>>()

        suspend fun send(m: JSONObject) =
            write.withLock {
                ws.send(
                    Frame.Text(
                        Crypto.b64(
                            Crypto.seal(
                                key!!,
                                ByteBuffer.allocate(8).putLong(tx++).array() +
                                    m.toString().toByteArray(),
                            )
                        )
                    )
                )
            }
    }

    fun action(block: suspend Engine.() -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                app.status(e.message ?: "Operation failed")
            }
        }
    }

    suspend fun start() {
        try {
            val history =
                withContext(Dispatchers.IO) {
                    receivedFolder
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && !it.name.endsWith(".part") }
                        .sortedByDescending { it.lastModified() }
                        .take(100)
                        .map {
                            Received(
                                it,
                                java.net.URLConnection.guessContentTypeFromName(it.name)
                                    ?: "application/octet-stream",
                                null,
                            )
                        }
                }
            app.state.update { it.copy(history = history) }
            server =
                embeddedServer(CIO, host = "0.0.0.0", port = 0) {
                        install(io.ktor.server.websocket.WebSockets) {
                            maxFrameSize = 65536
                            pingPeriod = java.time.Duration.ofSeconds(15)
                            timeout = java.time.Duration.ofSeconds(30)
                        }
                        routing {
                            webSocket("/control") { session(this, call.request.local.remoteHost) }
                            post("/upload/{id}") { receiveUpload(call) }
                        }
                    }
                    .start(false)
            port = server.resolvedConnectors().first().port
            discovery =
                Discovery(
                    app,
                    scope,
                    app.id,
                    app.name,
                    port,
                    { p ->
                        app.state.update {
                            it.copy(
                                peers = p,
                                selected =
                                    it.selected?.takeIf { id -> p.any { peer -> peer.id == id } }
                                        ?: p.singleOrNull()?.id,
                            )
                        }
                    },
                    app::status,
                )
            app.state.update { it.copy(running = true, status = "Ready · LAN discovery active") }
        } catch (e: Exception) {
            app.status("Cannot start: ${e.message}")
            throw e
        }
    }

    suspend fun stage(uri: Uri) {
        check(active == null) { "Finish or cancel the active transfer first" }
        var name = "Shared file"
        var size = -1L
        app.contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            ?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        val mime = (app.contentResolver.getType(uri) ?: "application/octet-stream").take(128)
        val source =
            if (size >= 0) {
                Source(name.take(256), size, mime, "file") {
                    app.contentResolver.openInputStream(uri) ?: error("Cannot open shared file")
                }
            } else {
                app.status("Preparing provider stream with unknown length…")
                val file = File(app.cacheDir, "stage-${UUID.randomUUID()}")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { out ->
                            val bytes = ByteArray(CHUNK)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                out.write(bytes, 0, count)
                            }
                        }
                    } ?: error("Cannot read provider")
                    Source(name.take(256), file.length(), mime, "file", temporaryFile = file) {
                        file.inputStream()
                    }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
        require(source.size <= 9007199254740991L) { "File exceeds protocol integer range" }
        app.state.value.stage?.temporaryFile?.delete()
        app.state.update {
            it.copy(stage = source, progress = 0f, status = "Staged · select a nearby device")
        }
    }

    fun stageText(text: String) {
        check(active == null) { "Finish or cancel the active transfer first" }
        val bytes = text.toByteArray()
        require(bytes.size in 1..1048576) { "Text must be 1 byte to 1 MiB" }
        val link = text.matches(Regex("https?://\\S+"))
        val source =
            Source(
                if (link) "Shared link.txt" else "Clipboard.txt",
                bytes.size.toLong(),
                "text/plain",
                if (link) "link" else "text",
            ) {
                bytes.inputStream()
            }
        app.state.value.stage?.temporaryFile?.delete()
        app.state.update { it.copy(stage = source, progress = 0f, status = "Text staged") }
    }

    fun clearStage() {
        check(active == null) { "Finish or cancel the active transfer first" }
        app.state.value.stage?.temporaryFile?.delete()
        app.state.update {
            it.copy(stage = null, status = "Ready to request the selected peer's staged item")
        }
    }

    suspend fun trigger(
        departureShown: Boolean = false,
        expectedPeerId: String? = null,
        expectedSource: Source? = null,
    ) {
        check(outgoing.tryLock()) { "A transfer is already active" }
        try {
            check(active == null) { "A transfer is already active" }
            val state = app.state.value
            if (expectedPeerId != null) {
                check(state.selected == expectedPeerId && state.stage === expectedSource) {
                    "Selected file or device changed"
                }
                check(state.peers.any { it.id == expectedPeerId && it.platform != "Android" }) {
                    "Select a Windows PC for camera transfer"
                }
            }
            val peer =
                state.peers.find { it.id == state.selected } ?: error("Select a discovered device")
            app.state.update { it.copy(busy = true, progress = 0f, status = "Connecting…") }
            client.webSocket(host = peer.host, port = peer.port, path = "/control") {
                session(this, peer.host, true, departureShown)
            }
        } finally {
            outgoing.unlock()
            if (active == null) app.state.update { it.copy(busy = false) }
        }
    }

    private suspend fun session(
        ws: WebSocketSession,
        host: String,
        outbound: Boolean = false,
        departureShown: Boolean = false,
    ) {
        if (sessions.size >= 8) {
            ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Busy"))
            return
        }
        val s = Session(ws, host)
        s.outbound = outbound
        s.departureShown = departureShown
        sessions.add(s)
        val handshakeTimeout =
            scope.launch {
                delay(10000)
                if (s.key == null) fail(s, "Handshake timed out")
            }
        val timeout =
            scope.launch {
                while (isActive) {
                    delay(15000)
                    if (now() - s.last > 120000 || (s.start == 0L && now() - s.created > 120000)) {
                        fail(s, "Session timed out")
                        break
                    }
                }
            }
        try {
            ws.send(
                Frame.Text(
                    msg(
                            "hello",
                            "v" to 1,
                            "commit" to Crypto.commit(s.me.public.encoded, s.nonce),
                            "name" to app.name,
                            "port" to port,
                        )
                        .toString()
                )
            )
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                s.last = now()
                val raw = frame.readText()
                require(raw.length <= 65536) { "Control message too large" }
                if (s.key == null) {
                    val m = JSONObject(raw)
                    if (s.remoteCommit == null) {
                        require(
                            m.getString("type") == "hello" &&
                                m.getInt("v") == 1 &&
                                m.getString("commit").matches(Regex("[0-9a-f]{64}")) &&
                                m.getInt("port") in 1024..65535
                        ) {
                            "Invalid handshake"
                        }
                        s.remoteCommit = m.getString("commit")
                        s.name = m.getString("name").take(64)
                        s.remotePort = m.getInt("port")
                        ws.send(
                            Frame.Text(
                                msg(
                                        "reveal",
                                        "pub" to Crypto.b64(s.me.public.encoded),
                                        "nonce" to Crypto.b64(s.nonce),
                                    )
                                    .toString()
                            )
                        )
                        continue
                    }
                    require(
                        m.getString("type") == "reveal" &&
                            m.getString("pub").length <= 256 &&
                            Crypto.un64(m.getString("nonce")).size == 32 &&
                            Crypto.commit(
                                Crypto.un64(m.getString("pub")),
                                Crypto.un64(m.getString("nonce")),
                            ) == s.remoteCommit
                    ) {
                        "Handshake commitment mismatch"
                    }
                    s.key = Crypto.derive(s.me, m.getString("pub"))
                    s.code = Crypto.sas(s.key!!)
                    if (outbound) {
                        if (app.state.value.stage != null) offer(s)
                        else s.send(msg("knock", "motion" to app.state.value.motion))
                    }
                } else {
                    val plain = Crypto.open(s.key!!, Crypto.un64(raw))
                    require(plain.size >= 8 && ByteBuffer.wrap(plain).long == s.rx++) {
                        "Replayed or out of order control message"
                    }
                    message(s, JSONObject(String(plain, 8, plain.size - 8, Charsets.UTF_8)))
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) fail(s, e.message ?: "Peer disconnected")
        } finally {
            timeout.cancel()
            handshakeTimeout.cancel()
            sessions.remove(s)
            s.connection?.disconnect()
            runCatching { s.stream?.close() }
            gate.withLock {
                if (active === s) {
                    active = null
                    app.state.update { it.copy(consent = null, busy = false) }
                    releaseWake()
                }
            }
            runCatching { ws.close() }
        }
    }

    private suspend fun offer(s: Session, motion: String = app.state.value.motion) {
        gate.withLock {
            check(active == null) { "Busy" }
            active = s
        }
        val source = app.state.value.stage ?: error("Nothing staged")
        s.source = source
        s.offer =
            msg(
                "offer",
                "motion" to if (motion in listOf("plane", "grab", "normal")) "plane" else "edge",
                "senderPlatform" to "Android",
                "id" to UUID.randomUUID().toString(),
                "name" to source.name,
                "size" to source.size,
                "mime" to source.mime,
                "kind" to source.kind,
            )
        s.send(s.offer!!)
        app.state.update {
            it.copy(
                consent = null,
                busy = true,
                progress = 0f,
                status = "Waiting for receiver to accept…",
            )
        }
        approve()
    }

    private suspend fun message(s: Session, m: JSONObject) {
        when (m.getString("type")) {
            "knock" ->
                if (active == null && app.state.value.stage != null)
                    offer(s, m.optString("motion", "edge"))
                else s.send(msg("reject", "reason" to "No file or text staged on that device"))
            "offer" -> {
                val size = m.optLong("size", -1)
                val valid =
                    size in 0..9007199254740991L &&
                        m.getString("name").length <= 256 &&
                        m.getString("mime").length <= 128 &&
                        m.getString("kind") in listOf("file", "text", "link") &&
                        m.getString("id").matches(Regex("[0-9a-f-]{36}"))
                val claimed =
                    gate.withLock {
                        if (active != null || !valid) false
                        else {
                            active = s
                            true
                        }
                    }
                if (!claimed) {
                    s.send(msg("reject", "reason" to "Busy or invalid offer"))
                    return
                }
                s.offer = m
                s.incoming = true
                val automatic = app.state.value.autoReceive
                app.state.update {
                    it.copy(
                        consent =
                            if (automatic) null
                            else Consent(m.getString("name"), size, s.name, s.code, true),
                        busy = true,
                        progress = 0f,
                        status = "Incoming · open AppSEND to review",
                    )
                }
                if (automatic) approve()
            }
            "accept" -> {
                check(s.offer != null && !s.incoming && !s.accepted) { "Unexpected acceptance" }
                val token = m.getString("token")
                require(token.matches(Regex("[0-9a-f]{64}")))
                s.token = token
                s.accepted = true
                maybeStart(s)
            }
            "ping" -> s.send(msg("pong", "t" to m.getLong("t"), "remote" to now()))
            "pong" -> {
                require(s.syncing && m.getLong("t") == s.pingAt) { "Unexpected timing response" }
                val t = m.getLong("t")
                s.samples.add((now() - t) to (m.getDouble("remote") - (now() + t) / 2.0))
                if (s.samples.size < 5) ping(s)
                else {
                    val best = s.samples.minBy { it.first }
                    require(best.first <= 500) { "LAN latency too high to synchronize" }
                    // Immediate gesture departure has already played on this phone.
                    // Keep enough lead for begin/ready on the measured LAN, without a fixed 1.2 s
                    // wait.
                    val lead = if (s.departureShown) maxOf(250L, best.first * 3 + 180) else 1200L
                    s.start = now() + lead
                    s.send(msg("begin", "at" to s.start + best.second))
                    s.syncing = false
                }
            }
            "begin" -> {
                val at = m.getDouble("at").toLong()
                require(
                    s.incoming && s.approved && !s.begun && at >= now() + 150 && at <= now() + 5000
                ) {
                    "Invalid animation schedule"
                }
                s.begun = true
                s.start = at
                holdWake()
                app.state.update {
                    it.copy(
                        animation =
                            Animation(
                                at,
                                true,
                                s.offer!!.getString("name"),
                                s.offer!!.optString("motion", "edge"),
                                departureShown = !s.incoming && s.departureShown,
                            ),
                        status = "Receiving…",
                    )
                }
                s.send(msg("ready"))
            }
            "ready" -> {
                require(!s.incoming && s.start > now() + 100 && !s.sending) {
                    "Peer missed animation deadline"
                }
                s.sending = true
                holdWake()
                app.state.update {
                    it.copy(
                        animation =
                            Animation(
                                s.start,
                                false,
                                s.offer!!.getString("name"),
                                s.offer!!.optString("motion", "edge"),
                                departureShown = !s.incoming && s.departureShown,
                            ),
                        status = "Sending…",
                    )
                }
                scope.launch {
                    try {
                        delay(maxOf(0, s.start + 350 - now()))
                        sendData(s)
                    } catch (e: Exception) {
                        fail(s, e.message ?: "Upload failed")
                    }
                }
            }
            "complete" -> {
                require(!s.incoming && s.sending)
                app.state.update {
                    it.copy(progress = 1f, status = "Delivered · integrity verified")
                }
                s.ws.close()
            }
            "reject" -> error(m.optString("reason", "Declined").take(200))
            "cancel" -> error("Peer cancelled")
            else -> error("Unknown control message")
        }
    }

    suspend fun approve() {
        val s = active ?: return
        if (s.approved) return
        s.approved = true
        app.state.update {
            it.copy(
                consent = null,
                status = if (s.incoming) "Preparing transfer…" else "Connecting…",
            )
        }
        if (s.incoming) {
            s.token = Crypto.token()
            s.send(msg("accept", "token" to s.token!!))
        } else maybeStart(s)
    }

    private suspend fun maybeStart(s: Session) {
        if (s.approved && s.accepted && !s.syncing) {
            s.syncing = true
            ping(s)
        }
    }

    private suspend fun ping(s: Session) {
        s.pingAt = now()
        s.send(msg("ping", "t" to s.pingAt))
    }

    suspend fun cancel() {
        val s = active ?: return
        runCatching { s.send(msg("cancel")) }
        fail(s, "Transfer cancelled")
    }

    private suspend fun fail(s: Session, reason: String) {
        if (s.failed) return
        s.failed = true
        if (active === s || s.outbound)
            app.state.update { it.copy(status = reason, animation = null) }
        s.connection?.disconnect()
        runCatching { s.stream?.close() }
        s.ws.close(CloseReason(CloseReason.Codes.NORMAL, "Closed"))
    }

    private fun holdWake() {
        wake.acquire(120000)
    }

    private fun releaseWake() {
        if (wake.isHeld) wake.release()
    }

    private fun progress(s: Session, total: Long) {
        s.last = now()
        holdWake()
        val size = s.offer!!.getLong("size")
        app.state.update {
            it.copy(
                progress = if (size == 0L) 1f else total.toFloat() / size,
                status = "${total/1048576} / ${size/1048576} MiB",
            )
        }
    }

    private suspend fun sendData(s: Session) =
        withContext(Dispatchers.IO) {
            val source = s.source!!
            val con =
                URL("http://${s.host}:${s.remotePort}/upload/${s.offer!!.getString("id")}")
                    .openConnection() as HttpURLConnection
            s.connection = con
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 8000
            con.readTimeout = 30000
            con.setChunkedStreamingMode(CHUNK)
            con.setRequestProperty("Authorization", "Bearer ${s.token}")
            con.setRequestProperty("Content-Type", "application/octet-stream")
            var index = 0L
            var total = 0L
            var last = 0L
            val hash = MessageDigest.getInstance("SHA-256")
            try {
                source.stream().use { input ->
                    s.stream = input
                    con.outputStream.buffered(CHUNK).use { out ->
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            total += n
                            require(total <= source.size) { "Source changed size" }
                            val bytes = buf.copyOf(n)
                            hash.update(bytes)
                            out.write(Crypto.record(s.key!!, index++, bytes))
                            if (now() - last > 80) {
                                progress(s, total)
                                last = now()
                            }
                        }
                        require(total == source.size) { "Source changed size" }
                        out.write(Crypto.record(s.key!!, index, hash.digest(), true))
                    }
                }
                check(con.responseCode == 200) { "Receiver rejected upload: ${con.responseCode}" }
                con.inputStream.close()
            } finally {
                s.stream = null
                s.connection = null
                con.disconnect()
            }
        }

    private suspend fun receiveUpload(call: ApplicationCall) =
        withContext(Dispatchers.IO) {
            val s = active
            if (
                s == null ||
                    !s.incoming ||
                    !s.approved ||
                    !s.begun ||
                    s.uploading ||
                    call.parameters["id"] != s.offer!!.getString("id") ||
                    call.request.headers["Authorization"] != "Bearer ${s.token}"
            ) {
                call.respond(HttpStatusCode.Forbidden)
                return@withContext
            }
            s.uploading = true
            s.token = null
            val temp = File(receivedFolder, ".edgebeam-${UUID.randomUUID()}.part")
            try {
                var index = 0L
                var total = 0L
                var final = false
                var last = 0L
                val hash = MessageDigest.getInstance("SHA-256")
                call.receiveChannel().toInputStream().use { raw ->
                    s.stream = raw
                    val input = DataInputStream(raw)
                    FileOutputStream(temp).use { out ->
                        while (true) {
                            val first = input.read()
                            if (first < 0) break
                            val lenBytes =
                                byteArrayOf(
                                    first.toByte(),
                                    input.readByte(),
                                    input.readByte(),
                                    input.readByte(),
                                )
                            val n = ByteBuffer.wrap(lenBytes).int
                            require(n in 37..CHUNK + 37 && !final) {
                                "Invalid record size or trailing data"
                            }
                            val packet = ByteArray(n)
                            input.readFully(packet)
                            val plain = Crypto.open(s.key!!, packet)
                            val header = ByteBuffer.wrap(plain)
                            val type = header.get().toInt()
                            require(header.long == index++) { "Out of order record" }
                            val data = plain.copyOfRange(9, plain.size)
                            if (type == 1) {
                                require(
                                    data.size == 32 &&
                                        total == s.offer!!.getLong("size") &&
                                        MessageDigest.isEqual(data, hash.digest())
                                ) {
                                    "Integrity check failed"
                                }
                                final = true
                            } else if (type == 0) {
                                total += data.size
                                require(total <= s.offer!!.getLong("size")) {
                                    "Exceeded advertised file size"
                                }
                                hash.update(data)
                                out.write(data)
                            } else error("Unknown record")
                            if (now() - last > 80) {
                                progress(s, total)
                                last = now()
                            }
                        }
                        require(final) { "Incomplete upload" }
                        out.fd.sync()
                    }
                }
                val name =
                    s.offer!!
                        .getString("name")
                        .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_")
                        .trimEnd('.', ' ')
                        .take(160)
                        .ifBlank { "received-file" }
                val dest = File(receivedFolder, "${UUID.randomUUID().toString().take(8)}-$name")
                Files.move(temp.toPath(), dest.toPath())
                val text =
                    if (s.offer!!.getString("kind") != "file" && dest.length() <= 1048576)
                        dest.readText()
                    else null
                call.respond(HttpStatusCode.OK, "OK")
                s.send(msg("complete"))
                app.state.update {
                    it.copy(
                        received = Received(dest, s.offer!!.getString("mime"), text),
                        history =
                            (listOf(Received(dest, s.offer!!.getString("mime"), text)) + it.history)
                                .take(100),
                        progress = 1f,
                        status = "Received · integrity verified",
                    )
                }
            } catch (e: Exception) {
                runCatching { call.respond(HttpStatusCode.BadRequest, "Transfer failed") }
                fail(s, e.message ?: "Receive failed")
            } finally {
                s.stream = null
                temp.delete()
            }
        }

    fun close() {
        discovery?.close()
        for (s in sessions) {
            s.connection?.disconnect()
            runCatching { s.stream?.close() }
        }
        scope.cancel()
        client.close()
        if (::server.isInitialized) server.stop(100, 1000)
        releaseWake()
        app.state.update {
            it.copy(
                running = false,
                busy = false,
                peers = emptyList(),
                consent = null,
                status = "LAN availability stopped",
            )
        }
    }
}
