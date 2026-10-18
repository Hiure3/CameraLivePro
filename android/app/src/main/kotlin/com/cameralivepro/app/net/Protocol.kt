package com.cameralivepro.app.net

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Protocolo CLP v1 — espelho de shared/CameraLive.Protocol/Protocol.cs (ver docs/PROTOCOL.md). */
object MsgType {
    const val HELLO = 1; const val CHALLENGE = 2; const val AUTH = 3; const val AUTH_OK = 4; const val AUTH_FAIL = 5
    const val CONFIG = 10; const val VIDEO = 11; const val PREVIEW = 12; const val STATS = 13
    const val PING = 14; const val PONG = 15; const val CONTROL = 20
}

object Ports {
    const val STREAM = 47800
    const val DISCOVERY = 47801
}

class AuthException(message: String) : IOException(message)

/** Funções criptográficas puras (testadas em ClpCryptoTest com vetores gerados em Python). */
object Clp {
    private val SALT = "CameraLivePro-v1".toByteArray(Charsets.US_ASCII)
    const val MAX_FRAME = 16 * 1024 * 1024

    fun master(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), SALT, 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun mac(key: ByteArray, tag: Char, nonceC: ByteArray, nonceS: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        m.update(tag.code.toByte()); m.update(nonceC); m.update(nonceS)
        return m.doFinal()
    }

    fun nonce(prefix: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(prefix).putLong(counter).array()

    fun aad(type: Int, ctLen: Int): ByteArray =
        ByteBuffer.allocate(5).put(type.toByte()).putInt(ctLen).array()

    /** Retorna texto cifrado || tag(16). */
    fun seal(key: ByteArray, prefix: Int, counter: Long, type: Int, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter)))
        c.updateAAD(aad(type, plain.size + 16))
        return c.doFinal(plain)
    }

    fun open(key: ByteArray, prefix: Int, counter: Long, type: Int, sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter)))
        c.updateAAD(aad(type, sealed.size))
        return c.doFinal(sealed)
    }
}

internal fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
    out.write(ByteBuffer.allocate(5).put(type.toByte()).putInt(payload.size).array())
    out.write(payload)
    out.flush()
}

internal fun readFrame(inp: DataInputStream, max: Int): Pair<Int, ByteArray> {
    val type = inp.readUnsignedByte()
    val len = inp.readInt()
    if (len < 0 || len > max) throw IOException("Quadro inválido ou grande demais")
    val buf = ByteArray(len)
    inp.readFully(buf)
    return type to buf
}

/** Canal autenticado AES-256-GCM; contadores estritamente sequenciais (proteção contra repetição). */
class SecureChannel(
    private val inp: DataInputStream,
    private val out: OutputStream,
    private val key: ByteArray,
    private val sendPrefix: Int,
    private val recvPrefix: Int,
) {
    private var sendCounter = 0L
    private var recvCounter = 0L

    @Synchronized
    fun send(type: Int, plain: ByteArray) {
        val sealed = Clp.seal(key, sendPrefix, sendCounter++, type, plain)
        writeFrame(out, type, sealed)
    }

    fun receive(): Pair<Int, ByteArray> {
        val (type, sealed) = readFrame(inp, Clp.MAX_FRAME)
        if (sealed.size < 16) throw IOException("Quadro cifrado inválido")
        val plain = try {
            Clp.open(key, recvPrefix, recvCounter, type, sealed)
        } catch (e: GeneralSecurityException) {
            throw IOException("Falha de autenticação do quadro")
        }
        recvCounter++
        return type to plain
    }
}

object ClientHandshake {
    private const val CLIENT_TO_SERVER = 1
    private const val SERVER_TO_CLIENT = 2

    fun run(inp: DataInputStream, out: OutputStream, code: String, deviceName: String): SecureChannel {
        val nonceC = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hello = JSONObject().put("v", 1).put("name", deviceName)
            .put("nonceC", Base64.getEncoder().encodeToString(nonceC)).toString()
        writeFrame(out, MsgType.HELLO, hello.toByteArray(Charsets.UTF_8))

        val (t, p) = readFrame(inp, 4096)
        if (t != MsgType.CHALLENGE) throw IOException("Resposta inesperada do receptor")
        val nonceS = Base64.getDecoder().decode(JSONObject(String(p, Charsets.UTF_8)).getString("nonceS"))
        if (nonceS.size != 16) throw IOException("Nonce inválido")

        val master = Clp.master(code)
        writeFrame(out, MsgType.AUTH, Clp.mac(master, 'C', nonceC, nonceS))
        val (t2, p2) = readFrame(inp, 64)
        if (t2 == MsgType.AUTH_FAIL) throw AuthException("Código de pareamento incorreto")
        if (t2 != MsgType.AUTH_OK || !MessageDigest.isEqual(p2, Clp.mac(master, 'S', nonceC, nonceS)))
            throw AuthException("O receptor não provou conhecer o código")
        return SecureChannel(inp, out, Clp.mac(master, 'K', nonceC, nonceS), CLIENT_TO_SERVER, SERVER_TO_CLIENT)
    }
}
