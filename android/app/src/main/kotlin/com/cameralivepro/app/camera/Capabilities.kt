package com.cameralivepro.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import android.util.Size

/** Uma resolução realmente utilizável: suportada pela câmera E por um codificador H.264. */
data class ResOption(val size: Size, val fps: List<Int>, val ranges: Map<Int, Range<Int>>) {
    val label: String get() = when (size.width to size.height) {
        3840 to 2160 -> "4K (3840×2160)"; 2560 to 1440 -> "1440p (2560×1440)"
        1920 to 1080 -> "1080p (1920×1080)"; 1280 to 720 -> "720p (1280×720)"
        else -> "${size.width}×${size.height}"
    }
}

enum class Quality(val label: String, val bitsPerPixel: Double) {
    LOW("Baixa", 0.04), MEDIUM("Média", 0.07), HIGH("Alta", 0.10), MAX("Máxima", 0.14)
}

object Bitrate {
    /** kbps = pixels × fps × bits/pixel, limitado a 500…40000. */
    fun compute(w: Int, h: Int, fps: Int, q: Quality): Int =
        (w.toLong() * h * fps * q.bitsPerPixel / 1000.0).toInt().coerceIn(500, 40_000)
}

object Capabilities {
    private val WANTED = listOf(Size(640, 480), Size(1280, 720), Size(1920, 1080), Size(2560, 1440), Size(3840, 2160))
    private val FPS = listOf(24, 25, 30, 50, 60)

    fun query(context: Context, front: Boolean): List<ResOption> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: return emptyList()
        val ch = mgr.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toSet() ?: return emptyList()
        val aeRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val encoder = h264Encoder()

        return WANTED.filter { it in sizes }.mapNotNull { size ->
            val minDur = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            val camMaxFps = if (minDur > 0) 1e9 / minDur else 30.0
            val ranges = LinkedHashMap<Int, Range<Int>>()
            for (f in FPS) {
                if (f > camMaxFps * 1.02) continue
                val r = pickRange(aeRanges, f) ?: continue
                val encOk = encoder?.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, f.toDouble()) ?: false
                if (encOk) ranges[f] = r
            }
            if (ranges.isEmpty()) null else ResOption(size, ranges.keys.toList(), ranges)
        }
    }

    private fun pickRange(ranges: List<Range<Int>>, fps: Int): Range<Int>? =
        ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.firstOrNull { it.upper == fps }
            ?: ranges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }

    /** Prefere codificador de hardware (API 29+); senão o primeiro disponível. */
    fun h264Encoder(): MediaCodecInfo.CodecCapabilities? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val pick = infos.firstOrNull { Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated } ?: infos.firstOrNull()
        return pick?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }
}
