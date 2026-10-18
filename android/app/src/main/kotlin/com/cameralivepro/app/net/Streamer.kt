package com.cameralivepro.app.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ConnState { IDLE, CONNECTING, STREAMING, RECONNECTING, AUTH_FAILED }

data class NetStats(val sentFps: Double = 0.0, val sentKbps: Int = 0, val rttMs: Int = -1, val dropped: Long = 0, val queue: Int = 0)

/**
 * Conexão com o receptor: pareamento, envio de vídeo, reconexão automática (backoff 1→5 s),
 * descarte controlado até o próximo quadro-chave e bitrate adaptativo pela ocupação da fila.
 */
class Streamer(
    private val scope: CoroutineScope,
    private val onNeedKeyFrame: () -> Unit,
    private val onBitrate: (Int) -> Unit,
    private val deviceInfo: () -> Pair<Int, Double>,   // (bateria %, temperatura °C)
) {
    private class Out(val type: Int, val data: ByteArray)

    val state = MutableStateFlow(ConnState.IDLE)
    val message = MutableStateFlow("")
    val stats = MutableStateFlow(NetStats())

    private val queue = LinkedBlockingQueue<Out>(90)
    private val bytesSent = AtomicLong()
    private val framesSent = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var droppingUntilKey = false
    @Volatile private var configJson: String? = null
    @Volatile private var configVersion = 0
    @Volatile private var rttMs = -1
    @Volatile var targetKbps = 4000
    @Volatile private var currentKbps = 4000
    private var job: Job? = null

    fun setConfig(json: String, bitrateKbps: Int) {
        targetKbps = bitrateKbps; currentKbps = bitrateKbps
        configJson = json; configVersion++
    }

    fun start(host: String, port: Int, code: String, deviceName: String) {
        stop()
        state.value = ConnState.CONNECTING
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            var everConnected = false
            while (isActive) {
                state.value = if (everConnected) ConnState.RECONNECTING else ConnState.CONNECTING
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), 5000)
                    socket.soTimeout = 10_000
                    val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                    val ch = ClientHandshake.run(inp, out, code, deviceName)
                    backoff = 1000; everConnected = true
                    queue.clear(); droppingUntilKey = false; rttMs = -1
                    configVersion++            // reenvia a configuração na nova sessão
                    onNeedKeyFrame()
                    state.value = ConnState.STREAMING
                    message.value = ""
                    session(ch, socket)
                } catch (e: AuthException) {
                    message.value = "Código incorreto. Confira o código de 8 dígitos exibido no receptor do PC."
                    state.value = ConnState.AUTH_FAILED
                    socket.close()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message.value = friendly(e)
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
                if (!isActive) break
                if (state.value != ConnState.AUTH_FAILED) state.value = ConnState.RECONNECTING
                delay(backoff)
                backoff = minOf(backoff * 2, 5000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        queue.clear()
        state.value = ConnState.IDLE
        message.value = ""
    }

    private fun friendly(e: Exception): String = when (e) {
        is ConnectException, is NoRouteToHostException, is UnknownHostException, is SocketTimeoutException ->
            "Não foi possível falar com o PC. Verifique: receptor aberto, IP correto, celular e PC na mesma rede Wi-Fi " +
                "(sem rede de convidados) e firewall do Windows liberado. Tentando de novo…"
        else -> "Conexão interrompida (${e.javaClass.simpleName}). Reconectando…"
    }

    /** Chamado pelo codificador. [data] é Annex-B; quadros-chave já incluem SPS/PPS. */
    fun submitVideo(ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
        if (state.value != ConnState.STREAMING) return
        if (droppingUntilKey && !keyFrame) { dropped.incrementAndGet(); return }
        val payload = ByteBuffer.allocate(9 + data.size).putLong(ptsUs).put((if (keyFrame) 1 else 0).toByte()).put(data).array()
        if (queue.offer(Out(MsgType.VIDEO, payload))) {
            if (keyFrame) droppingUntilKey = false
        } else {
            dropped.incrementAndGet()
            droppingUntilKey = true            // evita enviar P-frames sem referência
            onNeedKeyFrame()
        }
    }

    fun submitPreview(jpeg: ByteArray) {
        if (state.value == ConnState.STREAMING && queue.size < 20) queue.offer(Out(MsgType.PREVIEW, jpeg))
    }

    private suspend fun session(ch: SecureChannel, socket: Socket) = coroutineScope {
        launch(Dispatchers.IO) {
            while (isActive) {
                val (t, p) = ch.receive()
                if (t == MsgType.PONG && p.size == 8) {
                    rttMs = ((System.nanoTime() - ByteBuffer.wrap(p).long) / 1_000_000).toInt().coerceAtLeast(0)
                }
            }
        }
        launch(Dispatchers.IO) { ticker(ch) }
        try {
            withContext(Dispatchers.IO) {
                var sentVersion = -1
                while (isActive) {
                    if (sentVersion != configVersion) {
                        val cfg = configJson
                        if (cfg != null) { ch.send(MsgType.CONFIG, cfg.toByteArray(Charsets.UTF_8)); sentVersion = configVersion }
                    }
                    val o = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    ch.send(o.type, o.data)
                    bytesSent.addAndGet(o.data.size.toLong())
                    if (o.type == MsgType.VIDEO) framesSent.incrementAndGet()
                }
            }
        } finally {
            socket.close()      // destrava a leitura bloqueada do outro coroutine
        }
    }

    private suspend fun ticker(ch: SecureChannel) {
        var calm = 0
        var lastTime = System.nanoTime()
        while (true) {
            delay(1000)
            val now = System.nanoTime()
            val secs = (now - lastTime) / 1e9; lastTime = now
            val frames = framesSent.getAndSet(0)
            val kbps = (bytesSent.getAndSet(0) * 8 / 1000 / secs).toInt()
            val depth = queue.size
            // Bitrate adaptativo: fila enchendo = rede não aguenta.
            if (depth > 30) { currentKbps = maxOf(500, (currentKbps * 0.75).toInt()); onBitrate(currentKbps); calm = 0 }
            else if (depth < 3) { if (++calm >= 5 && currentKbps < targetKbps) { currentKbps = minOf(targetKbps, (currentKbps * 1.1).toInt() + 1); onBitrate(currentKbps); calm = 0 } }
            else calm = 0
            stats.value = NetStats(frames / secs, kbps, rttMs, dropped.get(), depth)
            val (bat, temp) = deviceInfo()
            val js = JSONObject().put("fps", frames / secs).put("rttMs", rttMs).put("bitrateKbps", kbps)
                .put("battery", bat).put("tempC", temp).put("dropped", dropped.get())
            ch.send(MsgType.STATS, js.toString().toByteArray(Charsets.UTF_8))
            ch.send(MsgType.PING, ByteBuffer.allocate(8).putLong(System.nanoTime()).array())
        }
    }
}
