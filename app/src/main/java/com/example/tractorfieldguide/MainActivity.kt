package com.example.tractorfieldguide

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlin.math.*
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker

data class GeoPoint(val lat: Double, val lon: Double, val timeMs: Long = System.currentTimeMillis())
data class GuidanceInfo(val passNumber: Int, val offsetMeters: Double)

enum class GuidanceMode {
    AB,
    CURVE,
    FIELD_AUTO,
    FIELD_LONG,
    FIELD_SHORT,
    FIELD_DIAGONAL,
    FIELD_CURVE_LONG,
    FIELD_CURVE_SHORT
}

class MainActivity : ComponentActivity() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private var callback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fused = LocationServices.getFusedLocationProviderClient(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        setContent {
            MaterialTheme {
                TractorApp(
                    hasLocationPermission = {
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    },
                    startGps = { onLocation -> startLocationUpdates(onLocation) },
                    stopGps = { stopLocationUpdates() },
                    startGnss = { onSatellites -> startGnssStatus(onSatellites) },
                    stopGnss = { stopGnssStatus() }
                )
            }
        }
    }

    private fun startLocationUpdates(onLocation: (Location) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        stopLocationUpdates()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocation)
            }
        }
        fused.requestLocationUpdates(request, callback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }


    private fun startGnssStatus(onSatellites: (Int, Int) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        stopGnssStatus()
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                val visible = status.satelliteCount
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                onSatellites(visible, used)
            }
        }
        locationManager.registerGnssStatusCallback(mainExecutor, gnssCallback!!)
    }

    private fun stopGnssStatus() {
        gnssCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        gnssCallback = null
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopGnssStatus()
        super.onDestroy()
    }
}

@Composable
fun TractorApp(
    hasLocationPermission: () -> Boolean,
    startGps: ((Location) -> Unit) -> Unit,
    stopGps: () -> Unit,
    startGnss: (((Int, Int) -> Unit) -> Unit),
    stopGnss: () -> Unit
) {
    var permissionGranted by remember { mutableStateOf(hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    var widthText by remember { mutableStateOf("3.6") }
    var headlandText by remember { mutableStateOf("8.0") }
    val headlandWidth =
        headlandText.replace(",", ".").toDoubleOrNull()?.coerceIn(0.0, 50.0) ?: 8.0
    val implementWidth =
        widthText.replace(",", ".").toDoubleOrNull()?.coerceIn(0.5, 30.0) ?: 3.6

    var recordingWork by remember { mutableStateOf(false) }
        var workPaused by remember { mutableStateOf(false) }
    var recordingBoundary by remember { mutableStateOf(false) }
    var recordingCurve by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var visibleSatellites by remember { mutableIntStateOf(0) }
    var usedSatellites by remember { mutableIntStateOf(0) }
    var rejectedGpsPoints by remember { mutableIntStateOf(0) }
    var locationSourceName by remember { mutableStateOf("Телефон GPS") }

    val track = remember { mutableStateListOf<GeoPoint>() }
    val boundary = remember { mutableStateListOf<GeoPoint>() }
    val curveReference = remember { mutableStateListOf<GeoPoint>() }

    var pointA by remember { mutableStateOf<GeoPoint?>(null) }
    var pointB by remember { mutableStateOf<GeoPoint?>(null) }
    var entryPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var mode by remember { mutableStateOf(GuidanceMode.FIELD_AUTO) }
    var startTimeMs by remember { mutableStateOf<Long?>(null) }

    fun onLocation(location: Location) {
        // Reject clearly poor points. Keep currentLocation updated only with accepted fixes.
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0
        val acceptable = accuracy <= 12.0

        if (!acceptable) {
            rejectedGpsPoints++
            return
        }

        currentLocation = location
        val p = GeoPoint(location.latitude, location.longitude)

        fun shouldAdd(last: GeoPoint?, minDistance: Double): Boolean {
            if (last == null) return true
            val d = distanceMeters(last, p)

            // Prevent obvious one-second GPS jumps. At normal tractor speeds,
            // a jump over 25 m between accepted fixes is almost certainly bad data.
            if (d > 25.0) {
                rejectedGpsPoints++
                return false
            }
            return d >= minDistance
        }

        if (recordingWork && !workPaused && shouldAdd(track.lastOrNull(), 0.4)) {
            track.add(p)
        }
        if (recordingBoundary && shouldAdd(boundary.lastOrNull(), 0.8)) {
            boundary.add(p)
        }
        if (recordingCurve && shouldAdd(curveReference.lastOrNull(), 0.8)) {
            curveReference.add(p)
        }
    }

    val gpsNeeded =
        recordingWork || recordingBoundary || recordingCurve ||
        pointA != null || pointB != null || entryPoint != null || boundary.isNotEmpty()

    DisposableEffect(gpsNeeded, permissionGranted) {
        if (gpsNeeded && permissionGranted) {
            startGps(::onLocation)
            startGnss { visible, used ->
                visibleSatellites = visible
                usedSatellites = used
            }
        } else {
            stopGps()
            stopGnss()
        }
        onDispose { }
    }

    val currentPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
    val distance = remember(track.toList()) { polylineDistance(track) }
    val workedAreaHa = (distance * implementWidth) / 10000.0
    val fieldAreaHa = remember(boundary.toList()) { polygonAreaHa(boundary) }
    val speedKmh = currentLocation?.speed?.times(3.6) ?: 0.0
    val gpsAccuracy = currentLocation?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
    val gpsQuality = when {
        gpsAccuracy == null -> "Нет фикса"
        gpsAccuracy <= 1.0 -> "Отлично"
        gpsAccuracy <= 2.5 -> "Очень хорошо"
        gpsAccuracy <= 5.0 -> "Хорошо"
        gpsAccuracy <= 10.0 -> "Средне"
        else -> "Плохо"
    }
    var timerTick by remember { mutableStateOf(0L) }
    LaunchedEffect(startTimeMs) {
        while (startTimeMs != null) {
            kotlinx.coroutines.delay(1000)
            timerTick++
        }
    }
    val elapsedMin = startTimeMs?.let {
        timerTick
        (System.currentTimeMillis() - it) / 60000.0
    } ?: 0.0
    val fieldAngles = remember(boundary.toList()) { fieldPrincipalAngles(boundary) }
    val selectedAngle = when (mode) {
        GuidanceMode.FIELD_AUTO, GuidanceMode.FIELD_LONG,
        GuidanceMode.FIELD_CURVE_LONG -> fieldAngles?.first
        GuidanceMode.FIELD_SHORT, GuidanceMode.FIELD_CURVE_SHORT -> fieldAngles?.second
        GuidanceMode.FIELD_DIAGONAL -> fieldAngles?.first?.plus(Math.PI / 4.0)
        else -> null
    }

    val guidance = when {
        mode == GuidanceMode.AB && pointA != null && pointB != null && currentPoint != null ->
            guidanceInfo(pointA!!, pointB!!, currentPoint, implementWidth)

        mode == GuidanceMode.CURVE && curveReference.size >= 2 && currentPoint != null ->
            curveGuidance(curveReference, currentPoint, implementWidth)

        selectedAngle != null && boundary.size >= 3 && currentPoint != null ->
            angleGuidance(boundary, selectedAngle, currentPoint, implementWidth)

        else -> null
    }

    val plannedPasses = if (selectedAngle != null && boundary.size >= 3)
        estimatePassCount(boundary, selectedAngle, implementWidth) else 0

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Tractor Field Guide", style = MaterialTheme.typography.headlineSmall)
            Text("Версия 0.3 — прямые, кривые и планирование проходов")

            if (!permissionGranted) {
                Button(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text("Разрешить GPS") }
            }

            OutlinedTextField(
                value = widthText,
                onValueChange = { widthText = it },
                label = { Text("Ширина агрегата, м") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = headlandText,
                onValueChange = { headlandText = it },
                label = { Text("Разворотная полоса, м") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        recordingWork = !recordingWork
                        if (recordingWork) {
                            if (startTimeMs == null) startTimeMs = System.currentTimeMillis()
                            recordingBoundary = false
                            recordingCurve = false
                        }
                    },
                    enabled = permissionGranted
                ) {
                    Text(if (recordingWork) "Стоп работа" else "Начать работу")
                }

                Button(
                    onClick = {
                        recordingBoundary = !recordingBoundary
                        if (recordingBoundary) {
                            recordingWork = false
                            recordingCurve = false
                        }
                    },
                    enabled = permissionGranted
                ) {
                    Text(if (recordingBoundary) "Завершить контур" else "Объезд поля")
                }
            }
        if (recordingWork) {
            Button(onClick = { workPaused = !workPaused }) {
                Text(if (workPaused) "Продолжить обработку" else "Пауза обработки")
            }
        }

            Text("Направление проходов", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_AUTO,
                    onClick = { mode = GuidanceMode.FIELD_AUTO },
                    label = { Text("Оптимально") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_LONG,
                    onClick = { mode = GuidanceMode.FIELD_LONG },
                    label = { Text("По длине") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_SHORT,
                    onClick = { mode = GuidanceMode.FIELD_SHORT },
                    label = { Text("По ширине") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_DIAGONAL,
                    onClick = { mode = GuidanceMode.FIELD_DIAGONAL },
                    label = { Text("Диагональ") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.AB,
                    onClick = { mode = GuidanceMode.AB },
                    label = { Text("AB вручную") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.CURVE,
                    onClick = { mode = GuidanceMode.CURVE },
                    label = { Text("Кривая") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_CURVE_LONG,
                    onClick = { mode = GuidanceMode.FIELD_CURVE_LONG },
                    label = { Text("Кривые по длине") }
                )
                FilterChip(
                    selected = mode == GuidanceMode.FIELD_CURVE_SHORT,
                    onClick = { mode = GuidanceMode.FIELD_CURVE_SHORT },
                    label = { Text("Кривые по ширине") }
                )
            }

            if (mode == GuidanceMode.AB) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            currentPoint?.let {
                                pointA = it
                                pointB = null
                            }
                        },
                        enabled = permissionGranted && currentPoint != null
                    ) { Text("Точка A") }

                    Button(
                        onClick = {
                            currentPoint?.let {
                                if (pointA != null && distanceMeters(pointA!!, it) >= 5.0) {
                                    pointB = it
                                }
                            }
                        },
                        enabled = permissionGranted && pointA != null && currentPoint != null
                    ) { Text("Точка B") }

                    OutlinedButton(onClick = {
                        pointA = null
                        pointB = null
                    }) { Text("Сброс AB") }
                }
            }

            if (mode == GuidanceMode.CURVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (!recordingCurve) {
                                curveReference.clear()
                                recordingWork = false
                                recordingBoundary = false
                            }
                            recordingCurve = !recordingCurve
                        },
                        enabled = permissionGranted
                    ) {
                        Text(if (recordingCurve) "Завершить кривую" else "Записать 1-й проход")
                    }
                    OutlinedButton(onClick = {
                        recordingCurve = false
                        curveReference.clear()
                    }) { Text("Сброс кривой") }
                }
            }

            if (boundary.size >= 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { currentPoint?.let { entryPoint = it } },
                        enabled = permissionGranted && currentPoint != null
                    ) { Text(if (entryPoint == null) "Точка въезда" else "Сменить въезд") }

                    if (entryPoint != null) {
                        OutlinedButton(onClick = { entryPoint = null }) {
                            Text("Сброс въезда")
                        }
                    }
                }
            }

            if (boundary.size >= 3 && selectedAngle != null) {
                Text(
                    "План: примерно $plannedPasses проходов. " +
                    if (mode == GuidanceMode.FIELD_AUTO)
                        "Предложено направление с меньшим числом разворотов."
                    else "Можно сравнить варианты на карте."
                )
            }

            guidance?.let {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Навигация", style = MaterialTheme.typography.titleMedium)
                        Text("Полоса: ${it.passNumber}")
                        val cm = abs(it.offsetMeters) * 100.0
                        val side = when {
                            abs(it.offsetMeters) < 0.05 -> "По центру"
                            it.offsetMeters > 0 -> "Сместись влево"
                            else -> "Сместись вправо"
                        }
                        Text("$side: %.0f см".format(cm))
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("GPS / GNSS", style = MaterialTheme.typography.titleMedium)
                    Text("Источник: $locationSourceName")
                    Text("Спутников видно: $visibleSatellites   используется: $usedSatellites")
                    Text(
                        "Точность: " +
                        (gpsAccuracy?.let { "±%.1f м".format(it) } ?: "нет данных") +
                        "   Качество: $gpsQuality"
                    )
                    Text("Отброшено плохих GPS-точек: $rejectedGpsPoints")
                    Text("Внешний RTK: подготовлено, подключение будет в следующем модуле")
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("Поле: %.2f га".format(fieldAreaHa))
                    Text("Обработано: %.2f га".format(workedAreaHa))
                    Text("Пройдено: %.2f км   Скорость: %.1f км/ч".format(distance / 1000.0, speedKmh))
                    Text("Время: %.0f мин   Осталось: %.2f га".format(
                        elapsedMin,
                        (fieldAreaHa - workedAreaHa).coerceAtLeast(0.0)
                    ))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    track.clear()
                    startTimeMs = null
                }) { Text("Сброс работы") }

                OutlinedButton(onClick = {
                    boundary.clear()
                }) { Text("Сброс поля") }
            }

            FieldMap(
                current = currentPoint,
                track = track,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
fun FieldCanvas(
    track: List<GeoPoint>,
    boundary: List<GeoPoint>,
    curveReference: List<GeoPoint>,
    implementWidthMeters: Double,
    current: GeoPoint?,
    pointA: GeoPoint?,
    pointB: GeoPoint?,
    mode: GuidanceMode,
    fieldAngle: Double?,
    headlandWidthMeters: Double,
    entryPoint: GeoPoint?,
    modifier: Modifier = Modifier
) {
    val all = buildList {
        addAll(track)
        addAll(boundary)
        addAll(curveReference)
        current?.let { add(it) }
        pointA?.let { add(it) }
        pointB?.let { add(it) }
        entryPoint?.let { add(it) }
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFFF3F5F1), RoundedCornerShape(16.dp))
            .padding(4.dp)
    ) {
        if (all.isEmpty()) {
            drawCircle(Color.DarkGray, radius = 8f, center = center)
            return@Canvas
        }

        val origin = all.first()
        val xyAll = all.map { projectMeters(origin, it) }.toMutableList()

        val minX = xyAll.minOf { it.first }
        val maxX = xyAll.maxOf { it.first }
        val minY = xyAll.minOf { it.second }
        val maxY = xyAll.maxOf { it.second }

        val spanX = max(30.0, maxX - minX)
        val spanY = max(30.0, maxY - minY)
        val scale = min(
            size.width / spanX.toFloat(),
            size.height / spanY.toFloat()
        ) * 0.86f

        fun metersToScreen(x: Double, y: Double): Offset {
            val cx = (x - (minX + maxX) / 2.0).toFloat()
            val cy = (y - (minY + maxY) / 2.0).toFloat()
            return Offset(
                size.width / 2f + cx * scale,
                size.height / 2f - cy * scale
            )
        }

        fun toScreen(p: GeoPoint): Offset {
            val m = projectMeters(origin, p)
            return metersToScreen(m.first, m.second)
        }

        if (boundary.size >= 2) {
            val path = Path()
            boundary.forEachIndexed { i, p ->
                val s = toScreen(p)
                if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
            }
            if (boundary.size >= 3) path.close()
            drawPath(path, Color(0xFF2E7D32), style = Stroke(width = 4f))
        }

        if (boundary.size >= 3 && fieldAngle != null) {
            val poly = boundary.map { projectMeters(origin, it) }

            if (mode in listOf(
                    GuidanceMode.FIELD_AUTO,
                    GuidanceMode.FIELD_LONG,
                    GuidanceMode.FIELD_SHORT,
                    GuidanceMode.FIELD_DIAGONAL
                )
            ) {
                val segments = plannedSegmentsInsidePolygon(
                    poly,
                    fieldAngle,
                    implementWidthMeters,
                    headlandWidthMeters
                )
                segments.forEachIndexed { index, seg ->
                    drawLine(
                        color = if (index % 2 == 0) Color(0xFF5C6BC0) else Color(0xFF7986CB),
                        start = metersToScreen(seg.first.first, seg.first.second),
                        end = metersToScreen(seg.second.first, seg.second.second),
                        strokeWidth = 2.5f
                    )
                }
            }

            if (mode == GuidanceMode.FIELD_CURVE_LONG ||
                mode == GuidanceMode.FIELD_CURVE_SHORT
            ) {
                val ref = boundaryReferenceCurve(poly, fieldAngle)
                for (n in 0..40) {
                    val off = headlandWidthMeters + n * implementWidthMeters
                    val shifted = offsetPolyline(ref, off)
                    if (shifted.size >= 2) {
                        val path = Path()
                        shifted.forEachIndexed { i, p ->
                            val s = metersToScreen(p.first, p.second)
                            if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                        }
                        drawPath(
                            path,
                            color = if (n == 0) Color(0xFF8E24AA) else Color(0x668E24AA),
                            style = Stroke(width = if (n == 0) 4f else 2f)
                        )
                    }
                }
            }

            if (headlandWidthMeters > 0.0) {
                // Visual hint: perimeter itself remains the outer boundary; working lines start inward.
                drawCircle(
                    Color(0x22000000),
                    radius = (headlandWidthMeters * scale).toFloat().coerceAtLeast(2f),
                    center = toScreen(boundary.first()),
                    style = Stroke(width = 1f)
                )
            }
        }

        if (mode == GuidanceMode.AB && pointA != null && pointB != null) {
            val a = projectMeters(origin, pointA)
            val b = projectMeters(origin, pointB)
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = hypot(dx, dy).coerceAtLeast(1.0)
            val ux = dx / len
            val uy = dy / len
            val px = -uy
            val py = ux
            val lineLength = max(spanX, spanY) * 3.0

            for (n in -30..30) {
                val off = n * implementWidthMeters
                val cx = a.first + px * off
                val cy = a.second + py * off
                drawLine(
                    color = if (n == 0) Color(0xFF1565C0) else Color(0x553F51B5),
                    start = metersToScreen(cx - ux * lineLength, cy - uy * lineLength),
                    end = metersToScreen(cx + ux * lineLength, cy + uy * lineLength),
                    strokeWidth = if (n == 0) 4f else 2f
                )
            }
        }

        if (curveReference.size >= 2) {
            val refXY = curveReference.map { projectMeters(origin, it) }

            for (n in -10..10) {
                val offset = n * implementWidthMeters
                val shifted = offsetPolyline(refXY, offset)
                if (shifted.size >= 2) {
                    val path = Path()
                    shifted.forEachIndexed { i, p ->
                        val s = metersToScreen(p.first, p.second)
                        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                    }
                    drawPath(
                        path,
                        color = if (n == 0) Color(0xFFFF8F00) else Color(0x66FF8F00),
                        style = Stroke(width = if (n == 0) 4f else 2f)
                    )
                }
            }
        }

        if (track.size >= 2) {
            val widthPx = (implementWidthMeters * scale).toFloat().coerceAtLeast(3f)
            for (i in 1 until track.size) {
                drawLine(
                    Color(0x66388E3C),
                    toScreen(track[i - 1]),
                    toScreen(track[i]),
                    widthPx
                )
            }
        }

        pointA?.let { drawCircle(Color(0xFFFF9800), 9f, toScreen(it)) }
        pointB?.let { drawCircle(Color(0xFFE65100), 9f, toScreen(it)) }
        entryPoint?.let { drawCircle(Color(0xFFD32F2F), 12f, toScreen(it)) }
        current?.let { drawCircle(Color(0xFF0D47A1), 10f, toScreen(it)) }
    }
}

fun guidanceInfo(a: GeoPoint, b: GeoPoint, p: GeoPoint, width: Double): GuidanceInfo {
    val bp = projectMeters(a, b)
    val pp = projectMeters(a, p)
    val dx = bp.first
    val dy = bp.second
    val len = hypot(dx, dy).coerceAtLeast(0.001)
    val signed = (dx * pp.second - dy * pp.first) / len
    val pass = (signed / width).roundToInt()
    val deviation = signed - pass * width
    return GuidanceInfo(pass + 1, deviation)
}

fun curveGuidance(
    curve: List<GeoPoint>,
    p: GeoPoint,
    width: Double
): GuidanceInfo {
    if (curve.size < 2) return GuidanceInfo(1, 0.0)
    val origin = curve.first()
    val q = projectMeters(origin, p)
    val pts = curve.map { projectMeters(origin, it) }

    var bestDist = Double.MAX_VALUE
    var bestSigned = 0.0

    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val vx = b.first - a.first
        val vy = b.second - a.second
        val len2 = vx * vx + vy * vy
        if (len2 < 0.001) continue

        val wx = q.first - a.first
        val wy = q.second - a.second
        val t = ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
        val cx = a.first + t * vx
        val cy = a.second + t * vy
        val dx = q.first - cx
        val dy = q.second - cy
        val dist = hypot(dx, dy)

        if (dist < bestDist) {
            bestDist = dist
            val len = sqrt(len2)
            bestSigned = (vx * (q.second - a.second) - vy * (q.first - a.first)) / len
        }
    }

    val pass = (bestSigned / width).roundToInt()
    return GuidanceInfo(pass + 1, bestSigned - pass * width)
}

fun angleGuidance(
    boundary: List<GeoPoint>,
    angle: Double,
    p: GeoPoint,
    width: Double
): GuidanceInfo {
    val origin = boundary.first()
    val poly = boundary.map { projectMeters(origin, it) }
    val q = projectMeters(origin, p)

    val nx = -sin(angle)
    val ny = cos(angle)
    val centerProj = poly.map { it.first * nx + it.second * ny }.average()
    val qProj = q.first * nx + q.second * ny
    val signed = qProj - centerProj
    val pass = (signed / width).roundToInt()
    return GuidanceInfo(pass + 1, signed - pass * width)
}

fun fieldPrincipalAngles(points: List<GeoPoint>): Pair<Double, Double>? {
    if (points.size < 3) return null
    val origin = points.first()
    val xy = points.map { projectMeters(origin, it) }
    val mx = xy.map { it.first }.average()
    val my = xy.map { it.second }.average()

    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    xy.forEach {
        val x = it.first - mx
        val y = it.second - my
        sxx += x * x
        syy += y * y
        sxy += x * y
    }

    val longAngle = 0.5 * atan2(2.0 * sxy, sxx - syy)
    val shortAngle = longAngle + Math.PI / 2.0
    return longAngle to shortAngle
}

fun estimatePassCount(
    boundary: List<GeoPoint>,
    angle: Double,
    width: Double
): Int {
    if (boundary.size < 3) return 0
    val origin = boundary.first()
    val xy = boundary.map { projectMeters(origin, it) }
    val nx = -sin(angle)
    val ny = cos(angle)
    val projections = xy.map { it.first * nx + it.second * ny }
    val span = (projections.maxOrNull() ?: 0.0) - (projections.minOrNull() ?: 0.0)
    return max(1, ceil(span / width).toInt())
}

fun plannedSegmentsInsidePolygon(
    polygon: List<Pair<Double, Double>>,
    angle: Double,
    width: Double,
    headland: Double = 0.0
): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
    if (polygon.size < 3) return emptyList()

    val ux = cos(angle)
    val uy = sin(angle)
    val nx = -uy
    val ny = ux

    val normalProj = polygon.map { it.first * nx + it.second * ny }
    val rawMinN = normalProj.minOrNull() ?: return emptyList()
    val rawMaxN = normalProj.maxOrNull() ?: return emptyList()
    val minN = rawMinN + headland
    val maxN = rawMaxN - headland
    if (maxN <= minN) return emptyList()

    val centerN = (minN + maxN) / 2.0
    val maxIndex = ceil((maxN - minN) / (2.0 * width)).toInt() + 2
    val result = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()

    for (k in -maxIndex..maxIndex) {
        val lineN = centerN + k * width
        if (lineN < minN - width || lineN > maxN + width) continue

        val hits = mutableListOf<Double>()

        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]

            val an = a.first * nx + a.second * ny
            val bn = b.first * nx + b.second * ny
            val da = an - lineN
            val db = bn - lineN

            if ((da <= 0.0 && db > 0.0) || (db <= 0.0 && da > 0.0)) {
                val t = (lineN - an) / (bn - an)
                val x = a.first + t * (b.first - a.first)
                val y = a.second + t * (b.second - a.second)
                hits.add(x * ux + y * uy)
            }
        }

        hits.sort()
        var i = 0
        while (i + 1 < hits.size) {
            val t1 = hits[i]
            val t2 = hits[i + 1]
            val p1 = (ux * t1 + nx * lineN) to (uy * t1 + ny * lineN)
            val p2 = (ux * t2 + nx * lineN) to (uy * t2 + ny * lineN)
            result.add(p1 to p2)
            i += 2
        }
    }
    return result
}


fun boundaryReferenceCurve(
    polygon: List<Pair<Double, Double>>,
    angle: Double
): List<Pair<Double, Double>> {
    if (polygon.size < 3) return polygon
    val ux = cos(angle)
    val uy = sin(angle)
    val nx = -uy
    val ny = ux

    // Pick the outer side that behaves like a first curved pass.
    val proj = polygon.map { it.first * nx + it.second * ny }
    val minP = proj.minOrNull() ?: return polygon
    val maxP = proj.maxOrNull() ?: return polygon
    val target = minP
    val tolerance = max(2.0, (maxP - minP) * 0.18)

    val selected = polygon.filterIndexed { index, p ->
        val v = proj[index]
        v <= target + tolerance
    }.sortedBy { it.first * ux + it.second * uy }

    return if (selected.size >= 2) selected else polygon
}

fun offsetPolyline(
    points: List<Pair<Double, Double>>,
    offset: Double
): List<Pair<Double, Double>> {
    if (points.size < 2) return points
    return points.indices.map { i ->
        val prev = points[max(0, i - 1)]
        val next = points[min(points.lastIndex, i + 1)]
        val dx = next.first - prev.first
        val dy = next.second - prev.second
        val len = hypot(dx, dy).coerceAtLeast(0.001)
        val nx = -dy / len
        val ny = dx / len
        (points[i].first + nx * offset) to (points[i].second + ny * offset)
    }
}

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dp = Math.toRadians(b.lat - a.lat)
    val dl = Math.toRadians(b.lon - a.lon)
    val h =
        sin(dp / 2).pow(2) +
        cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

fun polylineDistance(points: List<GeoPoint>): Double =
    points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

fun projectMeters(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
    val r = 6378137.0
    val lat0 = Math.toRadians(origin.lat)
    val x = Math.toRadians(p.lon - origin.lon) * r * cos(lat0)
    val y = Math.toRadians(p.lat - origin.lat) * r
    return x to y
}

fun polygonAreaHa(points: List<GeoPoint>): Double {
    if (points.size < 3) return 0.0
    val origin = points.first()
    val xy = points.map { projectMeters(origin, it) }
    var area = 0.0
    for (i in xy.indices) {
        val (x1, y1) = xy[i]
        val (x2, y2) = xy[(i + 1) % xy.size]
        area += x1 * y2 - x2 * y1
    }
    return abs(area) / 2.0 / 10000.0
}

@Composable
fun FieldMap(
    current: GeoPoint?,
    track: List<GeoPoint>,
    modifier: Modifier = Modifier
) {

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = "TractorFieldGuide/0.3"

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)
            }
        },
        update = { map ->
            map.overlays.clear()

            if (track.size >= 2) {
                val line = Polyline().apply {
                    setPoints(track.map { OsmGeoPoint(it.lat, it.lon) })
                    outlinePaint.strokeWidth = 8f
                }
                map.overlays.add(line)
            }

            current?.let { p ->
                val pos = OsmGeoPoint(p.lat, p.lon)

                val marker = Marker(map).apply {
                    position = pos
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Трактор"
                }

                map.overlays.add(marker)
                map.controller.setCenter(pos)
            }

            map.invalidate()
        }
    )
}
