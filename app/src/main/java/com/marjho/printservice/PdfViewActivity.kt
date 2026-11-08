package com.marjho.printservice

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.marjho.printservice.documents.GestorDocumentos
import com.marjho.printservice.network.DestinoImpresion
import com.marjho.printservice.network.ProtocoloImpresion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var archivo by remember { mutableStateOf<File?>(null) }
                var pagina by remember { mutableIntStateOf(0) }
                var paginas by remember { mutableIntStateOf(0) }
                var vista by remember { mutableStateOf<Bitmap?>(null) }
                var cargando by remember { mutableStateOf(true) }
                var imprimiendo by remember { mutableStateOf(false) }
                var mensaje by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    try {
                        val local = intent.getStringExtra(EXTRA_FILE_PATH)
                        val pdf = withContext(Dispatchers.IO) {
                            if (local != null) {
                                val candidato = File(local).canonicalFile
                                val permitidos = listOf(
                                    File(filesDir, "recent_documents").canonicalFile,
                                    cacheDir.canonicalFile
                                )
                                require(candidato.isFile && permitidos.any { candidato.parentFile == it } && candidato.extension.equals("pdf", true)) {
                                    "El PDF local ya no está disponible."
                                }
                                candidato
                            } else {
                                require(intent.action == Intent.ACTION_VIEW) { "Elegí un archivo PDF para abrirlo." }
                                val uri = requireNotNull(intent.data) { "No se recibió el archivo PDF." }
                                val tipo = contentResolver.getType(uri)
                                require(tipo == null || tipo == "application/pdf" || uri.lastPathSegment.orEmpty().endsWith(".pdf", true)) {
                                    "PDF View solo puede abrir archivos PDF."
                                }
                                val abierto = GestorDocumentos.importar(this@PdfViewActivity, uri, leerTextoPdf = false)
                                require(abierto.extension == "pdf") { "El archivo seleccionado no es un PDF válido." }
                                abierto.archivo
                            }
                        }
                        val total = withContext(Dispatchers.IO) {
                            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                                PdfRenderer(fd).use { it.pageCount }
                            }
                        }
                        require(total > 0) { "El PDF no contiene páginas." }
                        archivo = pdf
                        paginas = total
                    } catch (error: Exception) {
                        mensaje = error.localizedMessage ?: "No se pudo abrir el PDF."
                        cargando = false
                    }
                }

                LaunchedEffect(archivo, pagina) {
                    val pdf = archivo ?: return@LaunchedEffect
                    cargando = true
                    vista = withContext(Dispatchers.IO) { renderizarPagina(pdf, pagina) }
                    cargando = false
                }

                LaunchedEffect(mensaje) {
                    val texto = mensaje ?: return@LaunchedEffect
                    kotlinx.coroutines.delay(4000)
                    if (mensaje == texto) mensaje = null
                }

                PdfViewerScreen(
                    bitmap = vista,
                    pagina = pagina,
                    totalPaginas = paginas,
                    cargando = cargando,
                    imprimiendo = imprimiendo,
                    mensaje = mensaje,
                    onCambiarPagina = { pagina = it.coerceIn(0, (paginas - 1).coerceAtLeast(0)) },
                    onCompartir = {
                        val pdf = archivo ?: return@PdfViewerScreen
                        runCatching { compartir(pdf) }
                            .onFailure { mensaje = it.localizedMessage ?: "No se pudo compartir el PDF." }
                    },
                    onImprimir = {
                        val pdf = archivo ?: return@PdfViewerScreen
                        if (!imprimiendo) scope.launch {
                            imprimiendo = true
                            try {
                                val impresora = DestinoImpresion.desde(
                                    getSharedPreferences("marjho_print_local", MODE_PRIVATE)
                                ) ?: error("Elegí una impresora en la sección Red antes de imprimir.")
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
                    }
                )
            }
        }
    }

    private fun compartir(pdf: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", pdf)
        val compartir = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, pdf.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(compartir, "Compartir PDF"))
    }

    private fun renderizarPagina(pdf: File, indice: Int): Bitmap? =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (indice !in 0 until renderer.pageCount) return null
                renderer.openPage(indice).use { page ->
                    val escala = minOf(1600f / page.width, 2200f / page.height, 2.2f)
                    Bitmap.createBitmap(
                        (page.width * escala).toInt().coerceAtLeast(1),
                        (page.height * escala).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    ).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    companion object {
        const val EXTRA_FILE_PATH = "com.marjho.printservice.PDF_PATH"

        fun intent(context: android.content.Context, pdf: File) =
            Intent(context, PdfViewActivity::class.java).putExtra(EXTRA_FILE_PATH, pdf.absolutePath)
    }
}
