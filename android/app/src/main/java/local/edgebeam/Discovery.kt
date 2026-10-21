package local.edgebeam

import android.content.Context
import android.net.wifi.WifiManager
import java.net.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject

data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    val seen: Long,
)

class Discovery(
    context: Context,
    private val scope: CoroutineScope,
    id: String,
    name: String,
    port: Int,
    changed: (List<Peer>) -> Unit,
    error: (String) -> Unit,
) {
    private val peers = ConcurrentHashMap<String, Peer>()
    private val lock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("AppSEND discovery")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    private val socket =
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(45871))
            soTimeout = 2000
        }
    private val receive =
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2049)
            while (isActive) try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.length > 2048) continue
                val p = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                val pid = p.getString("id")
                val pp = p.getInt("port")
                if (
                    p.optString("magic") != "edgebeam" ||
                        p.optInt("v") != 1 ||
                        pid == id ||
                        !pid.matches(Regex("[-a-zA-Z0-9]{1,64}")) ||
                        pp !in 1024..65535
                )
                    continue
                peers[pid] =
                    Peer(
                        pid,
                        p.getString("name").take(64),
                        p.getString("platform").take(16),
                        packet.address.hostAddress!!,
                        pp,
                        System.currentTimeMillis(),
                    )
                changed(peers.values.toList())
            } catch (_: SocketTimeoutException) {} catch (e: Exception) {
                if (!socket.isClosed && e is SocketException) error("Discovery: ${e.message}")
            }
        }
    private val announce =
        scope.launch(Dispatchers.IO) {
            val bytes =
                JSONObject()
                    .put("magic", "edgebeam")
                    .put("v", 1)
                    .put("id", id)
                    .put("name", name)
                    .put("platform", "Android")
                    .put("port", port)
                    .toString()
                    .toByteArray()
            while (isActive) {
                try {
                    val addresses = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                    Collections.list(NetworkInterface.getNetworkInterfaces())
                        .filter { it.isUp && !it.isLoopback }
                        .forEach { n ->
                            n.interfaceAddresses.mapNotNull { it.broadcast }.forEach(addresses::add)
                        }
                    for (a in addresses) try {
                        socket.send(DatagramPacket(bytes, bytes.size, a, 45871))
                    } catch (_: Exception) {}
                    val now = System.currentTimeMillis()
                    peers.entries.removeAll { now - it.value.seen > 7000 }
                    changed(peers.values.toList())
                } catch (e: Exception) {
                    error("Discovery: ${e.message}")
                }
                delay(2000)
            }
        }

    fun close() {
        receive.cancel()
        announce.cancel()
        socket.close()
        if (lock.isHeld) lock.release()
    }
}
