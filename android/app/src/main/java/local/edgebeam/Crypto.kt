package local.edgebeam

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val random = SecureRandom()

    fun identity(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun un64(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun nonce(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun commit(pub: ByteArray, nonce: ByteArray): String =
        hash(pub + nonce).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(bytes)
        }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    fun derive(me: KeyPair, remote: String): ByteArray {
        val a = me.public.encoded
        val b = un64(remote)
        val salt = hash(if (compare(a, b) < 0) a + b else b + a)
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))
        val shared =
            KeyAgreement.getInstance("ECDH").run {
                init(me.private)
                doPhase(peer, true)
                generateSecret()
            }
        return hmac(hmac(salt, shared), "edgebeam/v1".toByteArray() + byteArrayOf(1))
    }

    fun sas(key: ByteArray): String =
        ((ByteBuffer.wrap(hash(key + "sas".toByteArray())).int.toLong() and 0xffffffffL) % 1000000)
            .toString()
            .padStart(6, '0')

    fun seal(
        key: ByteArray,
        plain: ByteArray,
        nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) },
    ): ByteArray =
        nonce +
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(plain)
            }

    fun open(key: ByteArray, packet: ByteArray): ByteArray {
        require(packet.size >= 28) { "Truncated encrypted record" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, packet.copyOfRange(0, 12)),
            )
            doFinal(packet, 12, packet.size - 12)
        }
    }

    fun record(key: ByteArray, index: Long, data: ByteArray, final: Boolean = false): ByteArray {
        val body =
            seal(
                key,
                ByteBuffer.allocate(9)
                    .put(if (final) 1.toByte() else 0.toByte())
                    .putLong(index)
                    .array() + data,
            )
        return ByteBuffer.allocate(4).putInt(body.size).array() + body
    }

    fun token(): String =
        ByteArray(32)
            .also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
