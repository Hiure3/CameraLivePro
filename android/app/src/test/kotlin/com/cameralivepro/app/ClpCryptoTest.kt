package com.cameralivepro.app

import com.cameralivepro.app.camera.Bitrate
import com.cameralivepro.app.camera.Quality
import com.cameralivepro.app.net.Clp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Vetores gerados por scripts/protocol_vectors.py; os mesmos são verificados no C#. */
class ClpCryptoTest {
    private val nc = ByteArray(16) { it.toByte() }
    private val ns = ByteArray(16) { (it + 16).toByte() }

    @Test fun master() =
        assertEquals("f1cd2fe4f6fb95a709e196b9ac2d19c072e65eb4091a7a397cc276b78894ebb7", hex(Clp.master("12345678")))

    @Test fun macs() {
        val m = Clp.master("12345678")
        assertEquals("abc59ecdf4232eb29e093626648160aaa72adcbfbe6929f70b92d17191cd3f40", hex(Clp.mac(m, 'C', nc, ns)))
        assertEquals("d6819d9e14d0941d8164da739fe4de34b9cc18e45744e0c9257a4f06922a4d13", hex(Clp.mac(m, 'S', nc, ns)))
        assertEquals("206ea5305fdf3f85d5a0006939352ce70a39fa2c6521d49b91c66809a311f15f", hex(Clp.mac(m, 'K', nc, ns)))
    }

    @Test fun sealMatchesPython() {
        val key = unhex("206ea5305fdf3f85d5a0006939352ce70a39fa2c6521d49b91c66809a311f15f")
        val sealed = Clp.seal(key, 1, 0, 11, "hello-video".toByteArray())
        assertEquals("1f73cefd4f8e6786c7397980d2b1ce79a2899d485ffafb5679c01e", hex(sealed))
        assertArrayEquals("hello-video".toByteArray(), Clp.open(key, 1, 0, 11, sealed))
    }

    @Test(expected = Exception::class) fun wrongCounterFails() {
        val key = ByteArray(32)
        Clp.open(key, 1, 1, 11, Clp.seal(key, 1, 0, 11, byteArrayOf(1, 2, 3)))
    }

    @Test fun bitrateIsBounded() {
        assertEquals(500, Bitrate.compute(640, 480, 24, Quality.LOW))   // 294 kbps calculado -> piso de 500
        assertTrue(Bitrate.compute(3840, 2160, 60, Quality.MAX) <= 40_000)
        assertTrue(Bitrate.compute(1920, 1080, 30, Quality.HIGH) in 4_000..8_000)
    }
}
