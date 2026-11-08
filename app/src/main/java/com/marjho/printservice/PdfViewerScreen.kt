package com.marjho.printservice

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.marjho.printservice.documents.PdfMarkPoint
import com.marjho.printservice.documents.PdfMarkStroke
import com.marjho.printservice.documents.PdfPageSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

private enum class MarkTool { READ, PEN, HIGHLIGHTER }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PdfViewerScreen(
    totalPaginas: Int,
    paginasCargadas: Int,
    tamanosPagina: Map<Int, PdfPageSize>,
    marcas: Map<Int, List<PdfMarkStroke>>,
    renderizarPagina: suspend (Int, Int) -> Bitmap?,
    cargando: Boolean,
    imprimiendo: Boolean,
    guardando: Boolean,
    mensaje: String?,
    onCargarMas: () -> Unit,
    onCompartir: () -> Unit,
    onImprimir: () -> Unit,
    onGuardarCopia: () -> Unit,
    onDeshacer: () -> Unit,
    onAgregarMarca: (Int, PdfMarkStroke) -> Unit
) {
    val lista = rememberLazyListState()
    var paginaVisible by remember { mutableIntStateOf(0) }
    var controlesVisibles by remember { mutableStateOf(true) }
    var mostrarHerramientas by remember { mutableStateOf(false) }
    var herramienta by remember { mutableStateOf(MarkTool.READ) }
    var color by remember { mutableStateOf(Color(0xFFE53935)) }
    var grosor by remember { mutableFloatStateOf(3.5f) }
    val alphaControles by animateFloatAsState(if (controlesVisibles) 1f else 0.10f, label = "controles PDF")
    val colores = listOf(Color(0xFFE53935), Color(0xFFFFC107), Color(0xFF43A047), Color(0xFF1976D2), Color(0xFF212121))

    LaunchedEffect(controlesVisibles, paginaVisible, mensaje) {
        if (controlesVisibles && herramienta == MarkTool.READ) {
            delay(2800)
            controlesVisibles = false
        }
    }
    LaunchedEffect(lista, totalPaginas) {
        snapshotFlow { lista.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { paginaVisible = it.coerceAtMost((totalPaginas - 1).coerceAtLeast(0)) }
    }
    LaunchedEffect(lista, paginasCargadas, totalPaginas) {
        snapshotFlow { lista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collectLatest { ultimoVisible ->
                if (paginasCargadas < totalPaginas && ultimoVisible >= paginasCargadas - 3) onCargarMas()
            }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF171717)).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed }) controlesVisibles = true
                }
            }
        }
    ) {
        if (totalPaginas > 0) {
            LazyColumn(
                state = lista,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = herramienta == MarkTool.READ,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed((0 until paginasCargadas).toList(), key = { _, index -> index }) { _, index ->
                    val size = tamanosPagina[index] ?: PdfPageSize(595, 842)
                    PdfPage(
                        index = index,
                        size = size,
                        strokes = marcas[index].orEmpty(),
                        tool = herramienta,
                        color = color,
                        strokeWidth = grosor,
                        renderizar = renderizarPagina,
                        onStroke = { onAgregarMarca(index, it) }
                    )
                }
                if (paginasCargadas < totalPaginas) {
                    item(key = "cargando-mas") {
                        Box(Modifier.fillMaxWidth().height(82.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.78f))
                        }
                    }
                }
            }
        }

        if (cargando && totalPaginas == 0) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (totalPaginas > 0 && controlesVisibles) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                color = Color.Black.copy(alpha = 0.60f),
                shape = CircleShape
            ) {
                Text(
                    "${(paginaVisible + 1).coerceAtMost(totalPaginas)} / $totalPaginas${if (herramienta != MarkTool.READ) " · ${if (herramienta == MarkTool.PEN) "Lápiz" else "Resaltador"}" else ""}",
                    Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                    color = Color.White
                )
            }
        }
        if (!mensaje.isNullOrBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.medium
            ) { Text(mensaje, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), maxLines = 3) }
        }

        if (totalPaginas > 0) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                    .graphicsLayer { alpha = alphaControles },
                horizontalArrangement = Arrangement.spacedBy(17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallFloatingActionButton(
                    onClick = onCompartir,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp).semantics { contentDescription = "Compartir PDF anotado" }
                ) { Text("↗", style = MaterialTheme.typography.titleLarge) }
                SmallFloatingActionButton(
                    onClick = { mostrarHerramientas = true; controlesVisibles = true },
                    containerColor = if (herramienta == MarkTool.READ) MaterialTheme.colorScheme.surface.copy(alpha = 0.90f) else MaterialTheme.colorScheme.primary,
                    contentColor = if (herramienta == MarkTool.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(58.dp).semantics { contentDescription = "Herramientas de lápiz y resaltador" }
                ) { Text("✎", style = MaterialTheme.typography.titleLarge) }
                SmallFloatingActionButton(
                    onClick = onImprimir,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp).semantics { contentDescription = "Imprimir PDF directamente" }
                ) {
                    if (imprimiendo) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("⎙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        if (mostrarHerramientas) {
            ModalBottomSheet(onDismissRequest = { mostrarHerramientas = false }) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp).padding(bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Marcar PDF", style = MaterialTheme.typography.titleLarge)
                    Text("Elegí una herramienta. Para volver a desplazar páginas, seleccioná Lectura.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        FilterChip(selected = herramienta == MarkTool.READ, onClick = { herramienta = MarkTool.READ }, label = { Text("Lectura") })
                        FilterChip(selected = herramienta == MarkTool.PEN, onClick = { herramienta = MarkTool.PEN }, label = { Text("Lápiz") })
                        FilterChip(selected = herramienta == MarkTool.HIGHLIGHTER, onClick = { herramienta = MarkTool.HIGHLIGHTER; color = Color(0xFFFFC107) }, label = { Text("Resaltador") })
                    }
                    Text("Color", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        colores.forEach { option ->
                            Surface(
                                onClick = { color = option },
                                modifier = Modifier.size(if (option == color) 40.dp else 34.dp).clip(CircleShape),
                                color = option,
                                shape = CircleShape,
                                border = androidx.compose.foundation.BorderStroke(if (option == color) 3.dp else 1.dp, if (option == color) MaterialTheme.colorScheme.primary else Color.LightGray)
                            ) {}
                        }
                    }
                    Text("Grosor: ${grosor.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                    Slider(value = grosor, onValueChange = { grosor = it }, valueRange = 1f..14f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDeshacer, enabled = marcas.isNotEmpty()) { Text("Deshacer") }
                        TextButton(onClick = { if (marcas.isNotEmpty()) onGuardarCopia() }, enabled = !guardando && marcas.isNotEmpty()) {
                            if (guardando) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Text("Guardar copia")
                        }
                        TextButton(onClick = { mostrarHerramientas = false; controlesVisibles = true }) { Text("Listo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    index: Int,
    size: PdfPageSize,
    strokes: List<PdfMarkStroke>,
    tool: MarkTool,
    color: Color,
    strokeWidth: Float,
    renderizar: suspend (Int, Int) -> Bitmap?,
    onStroke: (PdfMarkStroke) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }
        .coerceAtMost(1440)
    var bitmap by remember(index, screenWidth) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var zoomOffsetX by remember(index) { mutableFloatStateOf(0f) }
    var zoomOffsetY by remember(index) { mutableFloatStateOf(0f) }
    val livePoints = remember(index) { mutableStateListOf<PdfMarkPoint>() }

    LaunchedEffect(index, screenWidth) { bitmap = renderizar(index, screenWidth) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = nextScale
        if (nextScale > 1f) {
            zoomOffsetX += panChange.x
            zoomOffsetY += panChange.y
        } else {
            zoomOffsetX = 0f
            zoomOffsetY = 0f
        }
    }

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp).aspectRatio(size.width.toFloat() / size.height.coerceAtLeast(1))
            .clipToBounds().background(Color.White)
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    val maxPanX = this.size.width * (scale - 1f) / 2f
                    val maxPanY = this.size.height * (scale - 1f) / 2f
                    translationX = zoomOffsetX.coerceIn(-maxPanX, maxPanX)
                    translationY = zoomOffsetY.coerceIn(-maxPanY, maxPanY)
                }
                .then(if (tool == MarkTool.READ) Modifier.transformable(transformState, canPan = { scale > 1.01f }) else Modifier)
                .then(
                    if (tool == MarkTool.READ) Modifier
                    else Modifier.pointerInput(index, tool, color, strokeWidth) {
                        fun addPoint(x: Float, y: Float) {
                            livePoints.add(PdfMarkPoint((x / this.size.width).coerceIn(0f, 1f), (y / this.size.height).coerceIn(0f, 1f)))
                        }
                        detectDragGestures(
                            onDragStart = { livePoints.clear(); addPoint(it.x, it.y) },
                            onDrag = { change, _ -> addPoint(change.position.x, change.position.y); change.consume() },
                            onDragEnd = {
                                if (livePoints.size > 1) {
                                    onStroke(PdfMarkStroke(livePoints.toList(), color.toArgb(), strokeWidth, tool == MarkTool.HIGHLIGHTER))
                                }
                                livePoints.clear()
                            },
                            onDragCancel = { livePoints.clear() }
                        )
                    }
                )
        ) {
            bitmap?.let { image ->
                Image(image.asImageBitmap(), contentDescription = "Página ${index + 1}", modifier = Modifier.fillMaxSize())
            } ?: Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Canvas(Modifier.fillMaxSize()) {
                val all = strokes + if (livePoints.size > 1) listOf(PdfMarkStroke(livePoints.toList(), color.toArgb(), strokeWidth, tool == MarkTool.HIGHLIGHTER)) else emptyList()
                all.forEach { mark ->
                    if (mark.points.size < 2) return@forEach
                    val path = Path().apply {
                        moveTo(mark.points.first().x * this@Canvas.size.width, mark.points.first().y * this@Canvas.size.height)
                        mark.points.drop(1).forEach { lineTo(it.x * this@Canvas.size.width, it.y * this@Canvas.size.height) }
                    }
                    drawPath(
                        path = path,
                        color = Color(mark.color).copy(alpha = if (mark.highlighter) 0.30f else 1f),
                        style = Stroke(
                            width = (mark.width / size.width * this@Canvas.size.width).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
        if (tool != MarkTool.READ) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.58f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(if (tool == MarkTool.PEN) "Lápiz activo" else "Resaltador activo", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
