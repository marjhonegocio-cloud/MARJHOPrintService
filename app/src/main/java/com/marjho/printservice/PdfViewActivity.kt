package com.marjho.printservice

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.marjho.printservice.documents.GestorDocumentos
import com.marjho.printservice.documents.PdfMarkStroke
import com.marjho.printservice.documents.PdfMarkup
import com.marjho.printservice.documents.PdfPageSize
import com.marjho.printservice.PdfZoomTile
import com.marjho.printservice.network.DestinoImpresion
import com.marjho.printservice.network.ProtocoloImpresion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PdfViewActivity : ComponentActivity() {
    private val renderDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MARJHO-PDF-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingSaveFile: File? = null
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val savePdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val source = pendingSaveFile
            pendingSaveFile = null
            if (uri != null && source != null) {
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                                ?: error("No se pudo crear el PDF en la ubicación elegida.")
                        }
                    }.onSuccess { Toast.makeText(this@PdfViewActivity, "Copia PDF guardada.", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this@PdfViewActivity, it.localizedMessage ?: "No se pudo guardar el PDF.", Toast.LENGTH_LONG).show() }
                }
            }
        }

        setContent {
            MaterialTheme {
                var archivo by remember { mutableStateOf<File?>(null) }
                var paginasTotales by remember { mutableIntStateOf(0) }
                var paginasCargadas by remember { mutableIntStateOf(0) }
                var tamanosPagina by remember { mutableStateOf<Map<Int, PdfPageSize>>(emptyMap()) }
                val marcas = remember { mutableStateMapOf<Int, List<PdfMarkStroke>>() }
                val ordenMarcas = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
                var cargando by remember { mutableStateOf(true) }
                var cargandoMas by remember { mutableStateOf(false) }
                var imprimiendo by remember { mutableStateOf(false) }
                var guardando by remember { mutableStateOf(false) }
                var mensaje by remember { mutableStateOf<String?>(null) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    try {
                        val pdf = withContext(Dispatchers.IO) { localizarPdf() }
                        val total = withContext(renderDispatcher) {
                            val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
                            try {
                                val abierto = PdfRenderer(fd)
                                descriptor = fd
                                renderer = abierto
                                abierto.pageCount
                            } catch (error: Exception) {
                                fd.close()
                                throw error
                            }
                        }
                        require(total > 0) { "El PDF no contiene páginas." }
                        archivo = pdf
                        paginasTotales = total
                        // Show and render the first sheet without waiting for metadata
                        // from the rest of the initial batch.
                        tamanosPagina = leerTamanos(0, 1)
                        paginasCargadas = 1
                        cargando = false

                        val finPrimerLote = minOf(10, total)
                        if (finPrimerLote > 1) {
                            cargandoMas = true
                            scope.launch {
                                try {
                                    // Give the first sheet one frame to enqueue its bitmap
                                    // render before page-size reads use the shared renderer.
                                    kotlinx.coroutines.delay(48)
                                    val tamanosRestantes = leerTamanos(1, finPrimerLote)
                                    tamanosPagina = tamanosPagina + tamanosRestantes
                                    paginasCargadas = finPrimerLote
                                } catch (error: Exception) {
                                    mensaje = error.localizedMessage ?: "No se pudieron cargar más páginas."
                                } finally {
                                    cargandoMas = false
                                }
                            }
                        }
                    } catch (error: Exception) {
                        mensaje = error.localizedMessage ?: "No se pudo abrir el PDF."
                    } finally {
                        cargando = false
                    }
                }

                androidx.compose.runtime.LaunchedEffect(mensaje) {
                    val texto = mensaje ?: return@LaunchedEffect
                    kotlinx.coroutines.delay(4500)
                    if (mensaje == texto) mensaje = null
                }

                PdfViewerScreen(
                    totalPaginas = paginasTotales,
                    paginasCargadas = paginasCargadas,
                    tamanosPagina = tamanosPagina,
                    marcas = marcas,
                    renderizarPagina = ::renderizarPagina,
                    renderizarMosaico = ::renderizarMosaico,
                    cargando = cargando,
                    imprimiendo = imprimiendo,
                    guardando = guardando,
                    mensaje = mensaje,
                    onCargarMas = {
                        if (!cargandoMas && paginasCargadas < paginasTotales) {
                            val inicio = paginasCargadas
                            val fin = minOf(inicio + 10, paginasTotales)
                            cargandoMas = true
                            scope.launch {
                                try {
                                    val nuevosTamanos = leerTamanos(inicio, fin)
                                    tamanosPagina = tamanosPagina + nuevosTamanos
                                    paginasCargadas = fin
                                } catch (error: Exception) {
                                    mensaje = error.localizedMessage ?: "No se pudieron cargar más páginas."
                                } finally {
                                    cargandoMas = false
                                }
                            }
                        }
                    },
                    onAgregarMarca = { index, stroke ->
                        marcas[index] = marcas[index].orEmpty() + stroke
                        ordenMarcas.add(index)
                    },
                    onDeshacer = {
                        if (ordenMarcas.isNotEmpty()) {
                            val index = ordenMarcas.removeAt(ordenMarcas.lastIndex)
                            val strokes = marcas[index].orEmpty()
                            if (strokes.size <= 1) marcas.remove(index) else marcas[index] = strokes.dropLast(1)
                        }
                    },
                    onCompartir = {
                        val original = archivo ?: return@PdfViewerScreen
                        scope.launch {
                            try {
                                val paraCompartir = prepararDocumento(original, marcas.toMap())
                                compartir(paraCompartir)
                            } catch (error: Exception) {
                                mensaje = error.localizedMessage ?: "No se pudo compartir el PDF."
                            }
                        }
                    },
                    onImprimir = {
                        val original = archivo ?: return@PdfViewerScreen
                        if (!imprimiendo) scope.launch {
                            imprimiendo = true
                            try {
                                val pdf = prepararDocumento(original, marcas.toMap())
                                val impresora = DestinoImpresion.desde(getSharedPreferences("marjho_print_local", MODE_PRIVATE))
                                    ?: error("Elegí una impresora en la sección Red antes de imprimir.")
                                require(impresora.puedeImprimirPdfPorIpp || impresora.esEpsonL4150) {
                                    "La impresora guardada no admite envío directo de PDF. Configurá IPP/PDF o el perfil Epson L4150 en Red."
                                }
                                mensaje = withContext(Dispatchers.IO) { ProtocoloImpresion.imprimirPdf(impresora, pdf) }
                            } catch (error: Exception) {
                                mensaje = error.localizedMessage ?: "No se pudo enviar el PDF a la impresora."
                            } finally {
                                imprimiendo = false
                            }
                        }
                    },
                    onGuardarCopia = {
                        val original = archivo ?: return@PdfViewerScreen
                        if (!guardando) scope.launch {
                            guardando = true
                            try {
                                val markedCopy = withContext(Dispatchers.IO) { PdfMarkup.export(this@PdfViewActivity, original, marcas.toMap()) }
                                pendingSaveFile = markedCopy
                                savePdfLauncher.launch("${original.nameWithoutExtension}_anotado.pdf")
                            } catch (error: Exception) {
                                mensaje = error.localizedMessage ?: "No se pudo preparar la copia anotada."
                            } finally {
                                guardando = false
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun localizarPdf(): File {
        val local = intent.getStringExtra(EXTRA_FILE_PATH)
        if (local != null) {
            val candidate = File(local).canonicalFile
            val allowed = listOf(File(filesDir, "recent_documents").canonicalFile, cacheDir.canonicalFile)
            require(candidate.isFile && allowed.any { candidate.parentFile == it } && candidate.extension.equals("pdf", true)) {
                "El PDF local ya no está disponible."
            }
            return candidate
        }
        require(intent.action == Intent.ACTION_VIEW) { "Elegí un archivo PDF para abrirlo." }
        val uri = requireNotNull(intent.data) { "No se recibió el archivo PDF." }
        val mime = contentResolver.getType(uri)
        require(mime == null || mime == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", true)) {
            "PDF View solo puede abrir archivos PDF."
        }
        val imported = withContext(Dispatchers.IO) { GestorDocumentos.importar(this@PdfViewActivity, uri, leerTextoPdf = false) }
        require(imported.extension == "pdf") { "El archivo seleccionado no es un PDF válido." }
        return imported.archivo
    }

    private suspend fun leerTamanos(start: Int, end: Int): Map<Int, PdfPageSize> = withContext(renderDispatcher) {
        val pdfRenderer = renderer ?: error("El documento todavía no está listo.")
        buildMap {
            for (index in start until end) {
                pdfRenderer.openPage(index).use { page -> put(index, PdfPageSize(page.width, page.height)) }
            }
        }
    }

    private suspend fun renderizarPagina(index: Int, targetWidth: Int): Bitmap? = withContext(renderDispatcher) {
        val renderer = renderer ?: return@withContext null
        val width = targetWidth.coerceIn(320, 1440)
        val key = "$index:$width"
        bitmaps.get(key)?.let { return@withContext it }
        if (index !in 0 until renderer.pageCount) return@withContext null
        renderer.openPage(index).use { page ->
            val ratio = page.height.toFloat() / page.width.coerceAtLeast(1)
            val outputWidth = minOf(width, 1440)
            val outputHeight = (outputWidth * ratio).toInt().coerceIn(1, 3600)
            val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmaps.put(key, bitmap)
            bitmap
        }
    }

    private suspend fun renderizarMosaico(
        index: Int,
        viewport: IntSize,
        zoom: Float,
        offset: Offset
    ): PdfZoomTile? = withContext(renderDispatcher) {
        val pdfRenderer = renderer ?: return@withContext null
        if (index !in 0 until pdfRenderer.pageCount || viewport.width <= 0 || viewport.height <= 0) return@withContext null
        val scale = zoom.takeIf { it.isFinite() }?.coerceIn(1.05f, 4f) ?: return@withContext null
        runCatching {
            pdfRenderer.openPage(index).use { page ->
                val ratio = viewport.height.toFloat() / viewport.width
                var tileWidth = viewport.width.coerceIn(320, 1440)
                var tileHeight = (tileWidth * ratio).roundToInt().coerceAtLeast(1)
                val maxPixels = 4_000_000f
                val area = tileWidth.toLong() * tileHeight
                if (area > maxPixels) {
                    val factor = kotlin.math.sqrt(maxPixels / area.toFloat())
                    tileWidth = (tileWidth * factor).toInt().coerceAtLeast(1)
                    tileHeight = (tileHeight * factor).toInt().coerceAtLeast(1)
                }

                val leftFraction = (offset.x / viewport.width).takeIf { it.isFinite() }?.coerceIn(0f, 1f - 1f / scale) ?: 0f
                val topFraction = (offset.y / viewport.height).takeIf { it.isFinite() }?.coerceIn(0f, 1f - 1f / scale) ?: 0f
                val cropLeft = page.width * leftFraction
                val cropTop = page.height * topFraction
                val cropWidth = page.width / scale
                val cropHeight = page.height / scale
                val scaleX = tileWidth / cropWidth
                val scaleY = tileHeight / cropHeight
                val transform = Matrix().apply {
                    setValues(floatArrayOf(
                        scaleX, 0f, -cropLeft * scaleX,
                        0f, scaleY, -cropTop * scaleY,
                        0f, 0f, 1f
                    ))
                }
                val tile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                tile.eraseColor(android.graphics.Color.WHITE)
                page.render(tile, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PdfZoomTile(
                    bitmap = tile,
                    viewport = viewport,
                    scale = zoom,
                    offset = offset
                )
            }
        }.getOrNull()
    }

    private suspend fun prepararDocumento(original: File, marks: Map<Int, List<PdfMarkStroke>>): File {
        if (marks.values.none { list -> list.any { it.points.size > 1 } }) return original
        return withContext(Dispatchers.IO) { PdfMarkup.export(this@PdfViewActivity, original, marks) }
    }

    private fun compartir(pdf: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", pdf)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, pdf.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir PDF"))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) bitmaps.trimToSize(8 * 1024 * 1024)
        if (level >= TRIM_MEMORY_BACKGROUND) bitmaps.evictAll()
    }

    override fun onDestroy() {
        val activeRenderer = renderer
        val activeDescriptor = descriptor
        renderer = null
        descriptor = null
        kotlinx.coroutines.CoroutineScope(renderDispatcher).launch {
            runCatching { activeRenderer?.close() }
            runCatching { activeDescriptor?.close() }
            bitmaps.evictAll()
            (renderDispatcher as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.close()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "com.marjho.printservice.PDF_PATH"

        fun intent(context: android.content.Context, pdf: File) =
            Intent(context, PdfViewActivity::class.java).putExtra(EXTRA_FILE_PATH, pdf.absolutePath)
    }
}
