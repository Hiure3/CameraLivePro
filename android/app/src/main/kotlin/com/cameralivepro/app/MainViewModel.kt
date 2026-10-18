package com.cameralivepro.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cameralivepro.app.camera.Bitrate
import com.cameralivepro.app.camera.Capabilities
import com.cameralivepro.app.camera.DeviceMonitor
import com.cameralivepro.app.camera.DeviceStatus
import com.cameralivepro.app.camera.Quality
import com.cameralivepro.app.camera.ResOption
import com.cameralivepro.app.camera.VideoEncoder
import com.cameralivepro.app.net.ConnState
import com.cameralivepro.app.net.Discovery
import com.cameralivepro.app.net.FoundReceiver
import com.cameralivepro.app.net.Ports
import com.cameralivepro.app.net.Streamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("clp", Context.MODE_PRIVATE)

    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var code by mutableStateOf("")                     // o código nunca é gravado em disco
    var front by mutableStateOf(false)
    var options by mutableStateOf<List<ResOption>>(emptyList())
    var selRes by mutableStateOf<ResOption?>(null)
    var selFps by mutableStateOf(30)
    var quality by mutableStateOf(Quality.HIGH)
    var grid by mutableStateOf(false)
    var zoom by mutableStateOf(1f)
    var ev by mutableStateOf(0)
    var streaming by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var found by mutableStateOf<List<FoundReceiver>>(emptyList())
    var device by mutableStateOf(DeviceStatus())
    var error by mutableStateOf<String?>(null)
    var activeInfo by mutableStateOf("")

    private var encoder: VideoEncoder? = null
    private var startNs = 0L

    val streamer = Streamer(
        scope = viewModelScope,
        onNeedKeyFrame = { encoder?.requestKeyFrame() },
        onBitrate = { encoder?.setBitrate(it) },
        deviceInfo = { device.batteryPct to device.tempC },
    )

    val analyzer = ImageAnalysis.Analyzer { img -> onFrame(img) }

    init {
        refreshCapabilities()
        viewModelScope.launch { while (true) { device = DeviceMonitor.read(ctx); delay(2000) } }
    }

    fun refreshCapabilities() {
        options = try { Capabilities.query(ctx, front) } catch (e: Exception) { emptyList() }
        val pick = options.firstOrNull { it.size.width == 1920 } ?: options.lastOrNull()
        selRes = pick
        selFps = pick?.let { if (30 in it.fps) 30 else it.fps.first() } ?: 30
        if (options.isEmpty()) error = "Nenhuma resolução compatível com codificação H.264 foi encontrada para esta câmera."
    }

    fun selectRes(o: ResOption) { selRes = o; if (selFps !in o.fps) selFps = if (30 in o.fps) 30 else o.fps.first() }
    fun toggleLens() { front = !front; zoom = 1f; ev = 0; refreshCapabilities() }

    fun setQualityLevel(q: Quality) {
        quality = q
        val r = selRes ?: return
        val kbps = Bitrate.compute(r.size.width, r.size.height, selFps, q)
        streamer.targetKbps = kbps; encoder?.setBitrate(kbps)
    }

    fun searchReceivers() {
        if (searching) return
        searching = true; error = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { Discovery.search() }
            found = r; searching = false
            if (r.size == 1) host = r[0].host
            if (r.isEmpty()) error = "Nenhum receptor encontrado. Abra o Camera Live Pro Receiver no PC, " +
                "ou digite o IP exibido nele (a descoberta automática pode ser bloqueada pelo roteador)."
        }
    }

    fun startStreaming() {
        error = null
        val h = host.trim()
        if (h.isEmpty()) { error = "Digite o IP do PC (exibido no receptor) ou toque em Procurar PC."; return }
        if (!Regex("^[0-9]{8}$").matches(code.trim())) { error = "O código de pareamento tem 8 dígitos e aparece no receptor do PC."; return }
        if (selRes == null) { error = "Nenhuma resolução disponível para transmitir."; return }
        prefs.edit().putString("host", h).apply()
        startNs = 0L
        streaming = true
        streamer.start(h, Ports.STREAM, code.trim(), Build.MODEL ?: "Android")
    }

    fun stopStreaming() {
        streaming = false
        streamer.stop()
        encoder?.release(); encoder = null
        activeInfo = ""
    }

    private fun onFrame(img: ImageProxy) {
        try {
            if (!streaming || streamer.state.value != ConnState.STREAMING) return
            val enc = ensureEncoder(img.width, img.height) ?: return
            if (startNs == 0L) startNs = img.imageInfo.timestamp
            enc.feed(img, (img.imageInfo.timestamp - startNs) / 1000)
        } catch (e: Exception) {
            error = "Erro ao processar o vídeo: ${e.message}"
        } finally {
            img.close()
        }
    }

    private fun ensureEncoder(w: Int, h: Int): VideoEncoder? {
        val fps = selFps
        encoder?.let {
            if (it.width == w && it.height == h && it.fps == fps) return it
            it.release(); encoder = null
        }
        val kbps = Bitrate.compute(w, h, fps, quality)
        return try {
            val e = VideoEncoder(w, h, fps, kbps) { data, pts, key -> streamer.submitVideo(pts, key, data) }
            encoder = e
            streamer.setConfig(
                JSONObject().put("width", w).put("height", h).put("fps", fps).put("bitrateKbps", kbps)
                    .put("codec", "h264").put("camera", if (front) "frontal" else "traseira").toString(), kbps,
            )
            activeInfo = "${w}×$h @ $fps fps"
            e
        } catch (t: Exception) {
            error = t.message ?: "Falha ao iniciar o codificador."
            stopStreaming()
            null
        }
    }

    override fun onCleared() { stopStreaming(); super.onCleared() }
}
