package local.edgebeam

import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    @Test
    fun nodeJavaInteroperability() {
        val kf = KeyFactory.getInstance("EC")
        val me =
            KeyPair(
                kf.generatePublic(
                    X509EncodedKeySpec(
                        Crypto.un64(
                            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFrEmNkDaPMbvqycw9fAjLoH9ziyToNIIXpcQLnkONvluyEBLmMlOpdKKJIZcm5DpZ+LIazOMggxNIbnEixQUOg=="
                        )
                    )
                ),
                kf.generatePrivate(
                    PKCS8EncodedKeySpec(
                        Crypto.un64(
                            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgFHN9kl4rU4S+BIO6gyxqQBkknFa1FD30NOLii49bIpKhRANCAAQWsSY2QNo8xu+rJzD18CMugf3OLJOg0ghelxAueQ42+W7IQEuYyU6l0ookhlybkOln4shrM4yCDE0hucSLFBQ6"
                        )
                    )
                ),
            )
        val key =
            Crypto.derive(
                me,
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkkZfUtPtY2dmdITxhj3BMK7apmes2w9RxwI5bVXXBR9QGB/5+8Hyo/pM2sEfHs+71a7Iln1DgtobFZUXM3sZqg==",
            )
        assertEquals(
            "41947314797e339c960b4e5674a597fc7254fb08501e4a4204ead62feb6d309b",
            key.joinToString("") { "%02x".format(it.toInt() and 255) },
        )
        assertEquals("450494", Crypto.sas(key))
        val plain = Crypto.un64("RWRnZUJlYW0gaW50ZXJvcCDwn4y/")
        val packet =
            Crypto.un64("BwcHBwcHBwcHBwcHBWlzdetgQiMymHQt72IWJu4Z9i74RGYAd5JwFzMx3lrqeBQjlQ==")
        assertArrayEquals(plain, Crypto.open(key, packet))
        assertArrayEquals(packet, Crypto.seal(key, plain, ByteArray(12) { 7 }))
    }

    @Test
    fun tamperRejected() {
        val key = ByteArray(32) { it.toByte() }
        val packet = Crypto.seal(key, "hello".toByteArray())
        packet[14] = (packet[14].toInt() xor 1).toByte()
        try {
            Crypto.open(key, packet)
            fail("Tampered record accepted")
        } catch (_: javax.crypto.AEADBadTagException) {}
    }

    @Test
    fun recordLayout() {
        val key = ByteArray(32)
        val packet = Crypto.record(key, 23, "abc".toByteArray())
        val plain = Crypto.open(key, packet.copyOfRange(4, packet.size))
        assertEquals(packet.size - 4, java.nio.ByteBuffer.wrap(packet).int)
        assertEquals(23L, java.nio.ByteBuffer.wrap(plain, 1, 8).long)
        assertEquals("abc", String(plain, 9, 3))
    }
}
