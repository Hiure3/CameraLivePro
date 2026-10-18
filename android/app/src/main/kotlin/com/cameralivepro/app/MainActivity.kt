package com.cameralivepro.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cameralivepro.app.camera.CameraBinder
import com.cameralivepro.app.camera.Quality
import com.cameralivepro.app.net.ConnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Teal = Color(0xFF2DD4BF)
private val Rose = Color(0xFFF43F5E)
private val Amber = Color(0xFFFBBF24)
private val Bg = Color(0xFF0F1419)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Bg)) { Root(vm) }
        }
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) CameraScreen(vm) else Column(
        Modifier.fillMaxSize().background(Bg).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Text("Camera Live Pro", fontSize = 28.sp, color = Teal)
        Spacer(Modifier.height(12.dp))
        Text("Para transformar o celular em câmera, o app precisa de acesso à câmera. " +
            "Nada é transmitido sem você tocar em “Iniciar transmissão”.", color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Abrir configurações") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Se o botão não abrir o pedido, a permissão foi negada em definitivo: ative-a em Configurações → Permissões.", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun CameraScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val connState by vm.streamer.state.collectAsState()
    val netStats by vm.streamer.stats.collectAsState()
    val netMsg by vm.streamer.message.collectAsState()
    val res = vm.selRes

    LaunchedEffect(vm.front, res, vm.selFps) {
        val r = res ?: return@LaunchedEffect
        val range = r.ranges[vm.selFps] ?: return@LaunchedEffect
        CameraBinder.bind(ctx, owner, previewView, vm.front, r.size, range, vm.analyzer) { cam, err ->
            camera = cam
            if (err != null) vm.error = "Não foi possível abrir a câmera em ${r.label} a ${vm.selFps} fps neste aparelho. Escolha outra combinação. (${err.message})"
        }
    }
    LaunchedEffect(camera, vm.zoom) { camera?.cameraControl?.setZoomRatio(vm.zoom) }
    LaunchedEffect(camera, vm.ev) { camera?.cameraControl?.setExposureCompensationIndex(vm.ev) }
    DisposableEffect(vm.streaming) {
        val w = (ctx as Activity).window
        if (vm.streaming) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Miniatura JPEG (~2/s) para a prévia do receptor Windows.
    LaunchedEffect(vm.streaming) {
        while (vm.streaming) {
            delay(500)
            if (vm.streamer.state.value != ConnState.STREAMING) continue
            val bmp = previewView.bitmap ?: continue
            val jpeg = withContext(Dispatchers.Default) {
                val scaled = Bitmap.createScaledBitmap(bmp, 480, (480f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
            }
            vm.streamer.submitPreview(jpeg)
        }
    }

    val maxZoom = (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f).coerceAtMost(8f)
    val evInfo = camera?.cameraInfo?.exposureState
    val evRange = evInfo?.exposureCompensationRange

    Row(Modifier.fillMaxSize().background(Bg).padding(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView({ previewView }, Modifier.fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { o: Offset ->
                        val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                    }
                }
                .pointerInput(maxZoom) {
                    detectTransformGestures { _, _, z, _ -> vm.zoom = (vm.zoom * z).coerceIn(1f, maxZoom) }
                })
            if (vm.grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color(0x88FFFFFF)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                }
            }
            val (label, color) = when (connState) {
                ConnState.STREAMING -> "● AO VIVO" to Rose
                ConnState.CONNECTING -> "Conectando…" to Amber
                ConnState.RECONNECTING -> "Reconectando…" to Amber
                ConnState.AUTH_FAILED -> "Código incorreto" to Rose
                ConnState.IDLE -> "Pronto" to Teal
            }
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(color.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
            val d = vm.device
            val info = buildString {
                append(if (vm.activeInfo.isNotEmpty()) vm.activeInfo else (res?.let { "${it.size.width}×${it.size.height} @ ${vm.selFps} fps" } ?: ""))
                if (connState == ConnState.STREAMING) append("  ·  %.0f fps  ·  %d kbps  ·  RTT %s".format(netStats.sentFps, netStats.sentKbps, if (netStats.rttMs >= 0) "${netStats.rttMs} ms" else "—"))
                append("  ·  Bat ${d.batteryPct}%  ·  %.1f °C".format(d.tempC))
            }
            Text(info, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp))
            if (d.thermal >= 2 || d.tempC >= 42.0) {
                val severe = d.thermal >= 3 || d.tempC >= 45.0
                Text(if (severe) "Celular muito quente! Reduza resolução/FPS ou pare a transmissão." else "Celular aquecendo. Considere reduzir resolução ou FPS.",
                    color = Color.Black, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .background(if (severe) Rose else Amber, RoundedCornerShape(6.dp)).padding(6.dp))
            }
        }

        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
            Text("Camera Live Pro", fontSize = 20.sp, color = Teal)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(vm.host, { vm.host = it.take(64) }, label = { Text("IP do PC") }, singleLine = true,
                enabled = !vm.streaming, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.code, { vm.code = it.filter(Char::isDigit).take(8) }, label = { Text("Código (8 dígitos)") }, singleLine = true,
                enabled = !vm.streaming, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedButton({ vm.searchReceivers() }, enabled = !vm.streaming && !vm.searching, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.searching) "Procurando…" else "Procurar PC na rede")
            }
            vm.found.forEach { f -> OutlinedButton({ vm.host = f.host }, Modifier.fillMaxWidth()) { Text("${f.name} (${f.host})") } }

            Spacer(Modifier.height(8.dp))
            OutlinedButton({ vm.toggleLens() }, Modifier.fillMaxWidth()) { Text("Câmera: ${if (vm.front) "frontal" else "traseira"} (trocar)") }
            Picker("Resolução", res?.label ?: "—", vm.options, { it.label }) { vm.selectRes(it) }
            Picker("FPS", "${vm.selFps}", res?.fps ?: emptyList(), { "$it fps" }) { vm.selFps = it }
            Picker("Qualidade", vm.quality.label, Quality.values().toList(), { it.label }) { vm.setQualityLevel(it) }

            Text("Zoom: %.1f×".format(vm.zoom), fontSize = 12.sp, color = Color.LightGray)
            Slider(vm.zoom, { vm.zoom = it }, valueRange = 1f..maxOf(1.01f, maxZoom))
            if (evInfo?.isExposureCompensationSupported == true && evRange != null && evRange.upper > evRange.lower) {
                Text("Exposição: ${vm.ev}", fontSize = 12.sp, color = Color.LightGray)
                Slider(vm.ev.toFloat(), { vm.ev = it.toInt() }, valueRange = evRange.lower.toFloat()..evRange.upper.toFloat())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(vm.grid, { vm.grid = it }); Spacer(Modifier.width(8.dp)); Text("Grade de composição", fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (vm.streaming) vm.stopStreaming() else vm.startStreaming() },
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.streaming) Rose else Teal, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (vm.streaming) "Encerrar transmissão" else "Iniciar transmissão", fontSize = 16.sp) }

            (vm.error ?: netMsg.takeIf { it.isNotEmpty() })?.let {
                Spacer(Modifier.height(8.dp)); Text(it, color = Amber, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Toque na imagem para focar; pince para dar zoom. Áudio ainda não é transmitido nesta versão.", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun <T> Picker(label: String, current: String, items: List<T>, itemLabel: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: $current") }
        DropdownMenu(open, { open = false }) {
            items.forEach { DropdownMenuItem(text = { Text(itemLabel(it)) }, onClick = { open = false; onPick(it) }) }
        }
    }
}
