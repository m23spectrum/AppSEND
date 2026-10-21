package local.edgebeam

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class EngineTest {
    private data class Host(val app: BeamApp, val engine: Engine, val root: File)

    private fun host(): Host {
        val app = mock(BeamApp::class.java)
        val root = Files.createTempDirectory("edgebeam-jvm-").toFile()
        val state = MutableStateFlow(UiState())
        `when`(app.state).thenReturn(state)
        `when`(app.filesDir).thenReturn(root)
        `when`(app.cacheDir).thenReturn(root)
        `when`(app.id).thenReturn(UUID.randomUUID().toString())
        `when`(app.name).thenReturn("Android engine test")
        doAnswer { call ->
                state.update { it.copy(status = call.arguments[0] as String) }
                null
            }
            .`when`(app)
            .status(anyString())
        val power = mock(PowerManager::class.java)
        val wake = mock(PowerManager.WakeLock::class.java)
        `when`(app.getSystemService(Context.POWER_SERVICE)).thenReturn(power)
        `when`(power.newWakeLock(anyInt(), anyString())).thenReturn(wake)
        val wifi = mock(WifiManager::class.java)
        val lock = mock(WifiManager.MulticastLock::class.java)
        `when`(app.applicationContext).thenReturn(app)
        `when`(app.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
        `when`(wifi.createMulticastLock(anyString())).thenReturn(lock)
        return Host(app, Engine(app), root)
    }

    private fun CoroutineScope.approve(host: Host) =
        launch(start = CoroutineStart.UNDISPATCHED) {
            host.app.state.map { it.consent }.filterNotNull().collect { host.engine.approve() }
        }

    private fun select(host: Host, port: Int) {
        host.app.state.update {
            it.copy(
                selected = "test-peer",
                peers =
                    listOf(
                        Peer(
                            "test-peer",
                            "Loopback peer",
                            "Test",
                            "127.0.0.1",
                            port,
                            System.currentTimeMillis(),
                        )
                    ),
            )
        }
    }

    private suspend fun received(host: Host): Received =
        withTimeout(20000) { host.app.state.mapNotNull { it.received }.first() }

    @Test
    fun androidToAndroid() = runBlocking {
        val a = host()
        val b = host()
        val approvals = listOf(approve(a), approve(b))
        try {
            a.engine.start()
            b.engine.start()
            val bytes = ByteArray(786469) { (it % 251).toByte() }
            a.app.state.update {
                it.copy(
                    stage =
                        Source(
                            "large.bin",
                            bytes.size.toLong(),
                            "application/octet-stream",
                            "file",
                        ) {
                            bytes.inputStream()
                        }
                )
            }
            select(a, b.engine.listeningPort)
            val send = launch { a.engine.trigger() }
            val file = received(b).file
            assertArrayEquals(bytes, file.readBytes())
            withTimeout(10000) { send.join() }
            assertEquals(1f, a.app.state.value.progress)
            assertFalse(b.root.walk().any { it.extension == "part" })
        } finally {
            approvals.forEach { it.cancel() }
            a.engine.close()
            b.engine.close()
            a.root.deleteRecursively()
            b.root.deleteRecursively()
        }
    }

    private suspend fun nodeRoundTrip(
        mode: String,
        motion: String = "edge",
        departureShown: Boolean = false,
    ) = coroutineScope {
        val a = host()
        val output = Files.createTempDirectory("edgebeam-node-").toFile()
        val script = File(System.getProperty("edgebeam.root"), "desktop/test/interop-peer.cjs")
        val desktopDir = File(System.getProperty("edgebeam.root"), "desktop")
        val nodeBuilder =
            ProcessBuilder("node", script.absolutePath, mode, output.absolutePath)
                .directory(desktopDir)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
        nodeBuilder.environment()["NODE_PATH"] = File(desktopDir, "node_modules").absolutePath
        val node = nodeBuilder.start()
        val approval = approve(a)
        try {
            val reader = node.inputStream.bufferedReader()
            val port = withContext(Dispatchers.IO) { JSONObject(reader.readLine()).getInt("port") }
            a.engine.start()
            a.app.state.update { it.copy(motion = motion) }
            select(a, port)
            if (mode == "receive") a.engine.stageText("Interop payload 🌿")
            val send = launch {
                if (motion == "grab")
                    a.engine.trigger(departureShown, "test-peer", a.app.state.value.stage)
                else a.engine.trigger(departureShown)
            }
            if (mode == "send") assertEquals("Interop payload 🌿", received(a).text)
            withTimeout(20000) { send.join() }
            assertEquals(
                if (motion == "grab") "plane" else motion,
                a.app.state.value.animation?.motion,
            )
            assertEquals(
                mode == "receive" && departureShown,
                a.app.state.value.animation?.departureShown,
            )
            assertFalse(a.app.state.value.busy)
            val exit =
                withContext(Dispatchers.IO) {
                    if (!node.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                        error("Node peer timed out")
                    node.exitValue()
                }
            assertEquals(0, exit)
            if (mode == "receive")
                assertEquals("Interop payload 🌿", output.listFiles()!!.single().readText())
        } finally {
            approval.cancel()
            node.destroyForcibly()
            a.engine.close()
            a.root.deleteRecursively()
            output.deleteRecursively()
        }
    }

    @Test fun androidToWindows() = runBlocking { nodeRoundTrip("receive") }

    @Test fun windowsToAndroid() = runBlocking { nodeRoundTrip("send") }

    @Test fun androidSwipeToWindows() = runBlocking { nodeRoundTrip("receive", "plane", true) }

    @Test fun androidCameraGrabToWindows() = runBlocking { nodeRoundTrip("receive", "grab", true) }

    @Test
    fun cameraGrabRejectsChangedSelectionBeforeConnecting() = runBlocking {
        val a = host()
        try {
            a.engine.stageText("Expected source")
            val source = a.app.state.value.stage!!
            select(a, 1)
            assertTrue(runCatching { a.engine.trigger(true, "other-peer", source) }.isFailure)
            a.engine.stageText("Replacement source")
            assertTrue(runCatching { a.engine.trigger(true, "test-peer", source) }.isFailure)
            assertFalse(a.app.state.value.busy)
            assertNull(a.app.state.value.animation)
        } finally {
            a.engine.close()
            a.root.deleteRecursively()
        }
    }

    @Test
    fun cameraGrabRejectsAndroidRecipient() = runBlocking {
        val a = host()
        try {
            a.engine.stageText("Windows only")
            select(a, 1)
            a.app.state.update {
                it.copy(peers = it.peers.map { p -> p.copy(platform = "Android") })
            }
            assertTrue(
                runCatching { a.engine.trigger(true, "test-peer", a.app.state.value.stage) }
                    .isFailure
            )
            assertFalse(a.app.state.value.busy)
        } finally {
            a.engine.close()
            a.root.deleteRecursively()
        }
    }

    @Test fun androidPlaneToWindows() = runBlocking { nodeRoundTrip("receive", "plane") }

    @Test fun windowsPlaneToAndroid() = runBlocking { nodeRoundTrip("send", "plane") }

    @Test
    fun remoteRequestCannotSilentlyReadStagedFile() = runBlocking {
        val sender = host()
        val receiver = host()
        val receiverApproval = approve(receiver)
        var request: Job? = null
        try {
            sender.engine.start()
            receiver.engine.start()
            sender.engine.stageText("Requires owner's permission")
            select(receiver, sender.engine.listeningPort)
            request = launch { receiver.engine.trigger() }
            val consent = withTimeout(10000) { sender.app.state.mapNotNull { it.consent }.first() }
            assertFalse(consent.incoming)
            delay(250)
            assertNull(receiver.app.state.value.received)
            assertNull(sender.app.state.value.animation)
            sender.engine.approve()
            assertEquals("Requires owner's permission", received(receiver).text)
        } finally {
            receiverApproval.cancel()
            sender.engine.close()
            receiver.engine.close()
            request?.cancelAndJoin()
            sender.root.deleteRecursively()
            receiver.root.deleteRecursively()
        }
    }

    @Test
    fun incomingFileIsAutomaticWithoutAnApprovalCollector() = runBlocking {
        val a = host()
        val b = host()
        var send: Job? = null
        try {
            a.engine.start()
            b.engine.start()
            a.engine.stageText("Quiet receive")
            select(a, b.engine.listeningPort)
            send = launch { a.engine.trigger() }
            assertEquals("Quiet receive", received(b).text)
            assertNull(b.app.state.value.consent)
            withTimeout(10000) { send.join() }
        } finally {
            a.engine.close()
            b.engine.close()
            send?.cancelAndJoin()
            a.root.deleteRecursively()
            b.root.deleteRecursively()
        }
    }

    @Test
    fun automaticReceiveCanBeDisabled() = runBlocking {
        val a = host()
        val b = host()
        var send: Job? = null
        try {
            a.engine.start()
            b.engine.start()
            b.app.state.update { it.copy(autoReceive = false) }
            a.engine.stageText("Manual receive setting")
            select(a, b.engine.listeningPort)
            send = launch { a.engine.trigger() }
            val consent = withTimeout(10000) { b.app.state.mapNotNull { it.consent }.first() }
            assertTrue(consent.incoming)
            delay(250)
            assertNull(b.app.state.value.received)
            b.engine.approve()
            assertEquals("Manual receive setting", received(b).text)
            withTimeout(10000) { send.join() }
        } finally {
            a.engine.close()
            b.engine.close()
            send?.cancelAndJoin()
            a.root.deleteRecursively()
            b.root.deleteRecursively()
        }
    }
}
