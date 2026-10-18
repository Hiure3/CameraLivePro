package com.cameralivepro.app.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import androidx.camera.core.ImageProxy

/**
 * Codificador H.264 por MediaCodec (hardware quando disponível). Entrada: ImageProxy YUV_420_888 copiado
 * para a Image do codificador (getInputImage), o que independe do layout de cores do fabricante.
 * Saída: Annex-B; quadros-chave levam SPS/PPS na frente.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrateKbps: Int,
    private val onPacket: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
) {
    private val codec: MediaCodec
    @Volatile private var running = true
    @Volatile private var csd: ByteArray? = null
    @Volatile var droppedInput = 0L
        private set
    private val drainThread: Thread
    private var tmp = ByteArray(0)

    init {
        codec = create(bitrateKbps, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            ?: create(bitrateKbps, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            ?: throw IllegalStateException("Este aparelho não conseguiu iniciar um codificador H.264 em ${width}×$height @ $fps fps.")
        codec.start()
        drainThread = Thread({ drain() }, "clp-encoder-drain").also { it.start() }
    }

    private fun create(kbps: Int, mode: Int): MediaCodec? {
        var c: MediaCodec? = null
        return try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            }
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c
        } catch (e: Exception) {
            try { c?.release() } catch (_: Exception) {}
            null
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val i = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            if (i < 0) continue
            try {
                val buf = codec.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = data
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val sps = csd
                        onPacket(if (key && sps != null) sps + data else data, info.presentationTimeUs, key)
                    }
                }
                codec.releaseOutputBuffer(i, false)
            } catch (e: IllegalStateException) { break }
        }
    }

    /** Copia o quadro para o codificador. Retorna false se descartado (codificador ocupado). */
    fun feed(img: ImageProxy, ptsUs: Long): Boolean {
        if (!running) return false
        try {
            val idx = codec.dequeueInputBuffer(0)
            if (idx < 0) { droppedInput++; return false }
            val dst: Image? = codec.getInputImage(idx)
            if (dst == null) { codec.queueInputBuffer(idx, 0, 0, ptsUs, 0); return false }
            val w = img.width; val h = img.height
            copyPlane(img.planes[0], dst.planes[0], w, h)
            copyPlane(img.planes[1], dst.planes[1], w / 2, h / 2)
            copyPlane(img.planes[2], dst.planes[2], w / 2, h / 2)
            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs, 0)
            return true
        } catch (e: IllegalStateException) { return false }
    }

    private fun copyPlane(src: ImageProxy.PlaneProxy, dst: Image.Plane, cols: Int, rows: Int) {
        val s = src.buffer.duplicate(); val d = dst.buffer
        val sRow = src.rowStride; val sPix = src.pixelStride; val dRow = dst.rowStride; val dPix = dst.pixelStride
        if (sRow == dRow && sPix == dPix) {                    // caminho rápido: layouts idênticos
            s.rewind(); d.rewind()
            val n = minOf(s.remaining(), d.remaining())
            s.limit(n); d.put(s)
            return
        }
        if (tmp.size < cols) tmp = ByteArray(cols)
        for (r in 0 until rows) {
            val so = r * sRow; val dof = r * dRow
            if (sPix == 1 && dPix == 1) {
                val len = minOf(cols, s.limit() - so)
                if (len <= 0) break
                s.position(so); s.get(tmp, 0, len)
                d.position(dof); d.put(tmp, 0, len)
            } else {
                for (c in 0 until cols) d.put(dof + c * dPix, s.get(so + c * sPix))
            }
        }
    }

    fun setBitrate(kbps: Int) {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) }) }
        catch (_: Exception) {}
    }

    fun requestKeyFrame() {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        catch (_: Exception) {}
    }

    fun release() {
        running = false
        try { drainThread.join(500) } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
