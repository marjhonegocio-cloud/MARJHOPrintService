package com.marjho.printservice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.marjho.printservice.documents.DocumentoAbierto
import com.marjho.printservice.documents.GestorDocumentos
import com.marjho.printservice.driver.ImpresorEscprDirecto
import com.marjho.printservice.editor.EditorEtiquetaCanvas
import com.marjho.printservice.editor.EditorEtiquetaState
import com.marjho.printservice.network.BuscadorImpresorasRed
import com.marjho.printservice.network.ImpresoraEncontrada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("marjho_print_local", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var ip by remember { mutableStateOf(prefs.getString(KEY_IP, "").orEmpty()) }
                var status by remember { mutableStateOf("Elegí una impresora o abrí un documento.") }
                var impresoras by remember { mutableStateOf(emptyList<ImpresoraEncontrada>()) }
                var seleccionada by remember { mutableStateOf<ImpresoraEncontrada?>(null) }
                var popup by remember { mutableStateOf(false) }
                var buscando by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var documento by remember { mutableStateOf<DocumentoAbierto?>(null) }
                var textoEditado by remember { mutableStateOf("") }
                var imagenEditada by remember { mutableStateOf<Bitmap?>(null) }
                var pdfPreview by remember { mutableStateOf<Bitmap?>(null) }
                var editorEtiquetas by remember { mutableStateOf(false) }
                var layout by remember { mutableStateOf(LabelLayout()) }
                var editor by remember { mutableStateOf(EditorEtiquetaState()) }
                val scope = rememberCoroutineScope()
                val finder = remember { BuscadorImpresorasRed(this@MainActivity) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) {
                        busy = true
                        scope.launch {
                            try {
                                val loaded = withContext(Dispatchers.IO) { GestorDocumentos.importar(this@MainActivity, uri) }
                                documento = loaded
                                textoEditado = loaded.texto.orEmpty()
                                imagenEditada = loaded.imagen
                                pdfPreview = if (loaded.extension == "pdf") withContext(Dispatchers.IO) { renderFirstPage(loaded.archivo) } else null
                                status = "Abierto: ${loaded.nombre}"
                            } catch (e: Exception) {
                                status = "No se pudo abrir: ${e.localizedMessage}"
                            } finally { busy = false }
                        }
                    }
                }

                DisposableEffect(Unit) { onDispose { finder.detener() } }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("MARJHO Print Service", style = MaterialTheme.typography.headlineSmall)
                    Text("Impresión local · documentos · imágenes · etiquetas", style = MaterialTheme.typography.bodyMedium)

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("1. Impresora de red", style = MaterialTheme.typography.titleLarge)
                            Text(if (seleccionada != null) "Seleccionada: ${seleccionada!!.nombre}" else if (ip.isNotBlank()) "Destino guardado: $ip" else "Sin impresora seleccionada")
                            OutlinedTextField(ip, { ip = it }, label = { Text("Dirección IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !buscando, onClick = {
                                    popup = true; buscando = true; status = "Buscando impresoras en la red…"
                                    finder.buscar({ impresoras = it }, { status = it; if (it.startsWith("Búsqueda terminada")) buscando = false })
                                }) { Text(if (buscando) "Buscando…" else "Buscar impresoras") }
                                OutlinedButton(onClick = {
                                    if (validIp(ip)) {
                                        prefs.edit().putString(KEY_IP, ip.trim()).apply()
                                        seleccionada = null
                                        status = "IP guardada como destino."
                                    } else status = "Ingresá una dirección IPv4 válida."
                                }) { Text("Guardar IP") }
                            }
                            OutlinedButton(enabled = validIp(ip), onClick = {
                                status = "Comprobando $ip:9100…"
                                scope.launch {
                                    val d = withContext(Dispatchers.IO) { ImpresorEscprDirecto.diagnosticar(ip.trim()) }
                                    status = d.detalle
                                }
                            }) { Text("Probar conexión ESC/P-R (9100)") }
                            Text("La búsqueda mDNS encuentra servicios anunciados; una impresora puede no aparecer. La IP manual sigue disponible.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("2. Documentos y archivos", style = MaterialTheme.typography.titleLarge)
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "image/*"))
                            }) { Text(if (busy) "Abriendo…" else "Abrir PDF, Word, texto o imagen") }
                            documento?.let { doc ->
                                Text("${doc.nombre} · ${doc.extension.uppercase()}")
                                when {
                                    doc.extension == "txt" || doc.extension == "docx" -> {
                                        Text(if (doc.extension == "docx") "Texto extraído del Word. En esta primera entrega se puede editar y guardar como TXT; no se reescribe todavía el DOCX original conservando su formato." else "Editor de texto")
                                        OutlinedTextField(
                                            value = textoEditado,
                                            onValueChange = { textoEditado = it },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                                            label = { Text("Contenido") }
                                        )
                                        Button(onClick = {
                                            val f = GestorDocumentos.guardarTexto(this@MainActivity, doc.nombre, textoEditado)
                                            status = "Texto editado guardado en caché: ${f.name}"
                                        }) { Text("Guardar texto editado como TXT") }
                                    }
                                    doc.extension == "pdf" -> {
                                        Text("PDF · ${doc.paginasPdf} páginas")
                                        pdfPreview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
                                        Text("Vista previa de la primera página. Se puede enviar el PDF original a impresión.")
                                    }
                                    doc.imagen != null -> {
                                        Text("Editor básico de imagen · rotación 90°")
                                        (imagenEditada ?: doc.imagen)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                imagenEditada = GestorDocumentos.rotar(imagenEditada ?: doc.imagen!!, 90f)
                                            }) { Text("Girar 90°") }
                                            Button(onClick = {
                                                val f = GestorDocumentos.guardarImagen(this@MainActivity, imagenEditada ?: doc.imagen!!)
                                                status = "Imagen editada guardada: ${f.name}"
                                            }) { Text("Guardar imagen PNG") }
                                        }
                                    }
                                    else -> Text("Formato reconocido parcialmente. Todavía no hay editor para este tipo de archivo.")
                                }
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy && (doc.extension == "pdf" || doc.extension in listOf("jpg","jpeg","png","webp","bmp")),
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val file = withContext(Dispatchers.IO) {
                                                    if (doc.extension == "pdf") doc.archivo
                                                    else imageToPdf(imagenEditada ?: doc.imagen!!, File(cacheDir, "marjho_imagen_print.pdf"))
                                                }
                                                if (validIp(ip)) {
                                                    val result = withContext(Dispatchers.IO) { ImpresorEscprDirecto.imprimirHojas(ip.trim(), file) }
                                                    status = result
                                                } else {
                                                    printPdf(file)
                                                    status = "Abierto selector de impresión Android. Configurá una IP para impresión directa."
                                                }
                                            } catch (e: Exception) { status = e.localizedMessage ?: "Error al imprimir" }
                                            finally { busy = false }
                                        }
                                    }
                                ) { Text(if (busy) "Procesando…" else "Imprimir documento / imagen") }
                                if (doc.extension == "docx" || doc.extension == "txt") {
                                    Text("Para imprimir el texto editado, guardalo como TXT y abrilo con otra aplicación; la conversión DOCX/TXT a PDF se incorpora en la siguiente etapa.", style = MaterialTheme.typography.bodySmall)
                                }
                            } ?: Text("Todavía no abriste ningún archivo.")
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("3. Editor de etiquetas", style = MaterialTheme.typography.titleLarge)
                            Text("El editor de etiquetas original se conserva como módulo. Abrilo para configurar medidas, diseño y contenido.")
                            Button(onClick = { editorEtiquetas = !editorEtiquetas }) { Text(if (editorEtiquetas) "Ocultar editor" else "Mostrar editor de etiquetas") }
                            if (editorEtiquetas) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !layout.individual, onClick = { layout = layout.copy(individual = false) }, label = { Text("Varias por hoja") })
                                    FilterChip(selected = layout.individual, onClick = { layout = layout.copy(individual = true) }, label = { Text("Individual") })
                                }
                                NumberField("Ancho etiqueta (mm)", layout.labelWidthMm) { layout = layout.copy(labelWidthMm = it) }
                                NumberField("Alto etiqueta (mm)", layout.labelHeightMm) { layout = layout.copy(labelHeightMm = it) }
                                if (!layout.individual) {
                                    NumberField("Columnas", layout.columns.toFloat(), true) { layout = layout.copy(columns = it.toInt().coerceIn(1,6)) }
                                    NumberField("Filas", layout.rows.toFloat(), true) { layout = layout.copy(rows = it.toInt().coerceIn(1,12)) }
                                    NumberField("Margen (mm)", layout.marginMm) { layout = layout.copy(marginMm = it) }
                                    NumberField("Separación X (mm)", layout.gapXmm) { layout = layout.copy(gapXmm = it) }
                                    NumberField("Separación Y (mm)", layout.gapYmm) { layout = layout.copy(gapYmm = it) }
                                }
                                OutlinedTextField(layout.productName, { layout = layout.copy(productName = it) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(layout.price, { layout = layout.copy(price = it) }, label = { Text("Precio") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(layout.barcode, { layout = layout.copy(barcode = it) }, label = { Text("Código") }, modifier = Modifier.fillMaxWidth())
                                Row { Checkbox(layout.showName, { layout = layout.copy(showName = it) }); Text("Nombre") }
                                Row { Checkbox(layout.showPrice, { layout = layout.copy(showPrice = it) }); Text("Precio") }
                                Row { Checkbox(layout.showBarcode, { layout = layout.copy(showBarcode = it) }); Text("Código") }
                                EditorEtiquetaCanvas(state = editor, anchoMm = layout.labelWidthMm, altoMm = layout.labelHeightMm, onChange = { editor = it })
                                Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val pdf = withContext(Dispatchers.IO) { LabelPdfGenerator.create(this@MainActivity, layout, editor) }
                                            if (validIp(ip)) status = withContext(Dispatchers.IO) { ImpresorEscprDirecto.imprimirHojas(ip.trim(), pdf) }
                                            else { printPdf(pdf); status = "PDF preparado en selector Android." }
                                        } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo generar el PDF." }
                                        finally { busy = false }
                                    }
                                }) { Text("Generar e imprimir etiquetas") }
                            }
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("4. Servicio Android", style = MaterialTheme.typography.titleLarge)
                            Text("También podés activar MARJHO para recibir trabajos enviados desde otras aplicaciones.")
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }) {
                                Text("Abrir ajustes de impresión Android")
                            }
                        }
                    }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                }

                if (popup) {
                    AlertDialog(
                        onDismissRequest = { popup = false; finder.detener(); buscando = false },
                        title = { Text("Impresoras encontradas") },
                        text = {
                            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                                Text(status, style = MaterialTheme.typography.bodySmall)
                                if (impresoras.isEmpty()) Text("Buscando… Si no aparece ninguna, probá ingresar la IP manualmente.")
                                impresoras.forEach { printer ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        onClick = {
                                            seleccionada = printer
                                            ip = printer.ip
                                            prefs.edit().putString(KEY_IP, printer.ip).apply()
                                            popup = false
                                            finder.detener()
                                            buscando = false
                                            status = "Seleccionada ${printer.nombre} (${printer.ip}). El servicio ESC/P-R requiere compatibilidad confirmada."
                                        }
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(printer.nombre, style = MaterialTheme.typography.titleMedium)
                                            Text("IP: ${printer.ip}")
                                            Text("Servicio: ${printer.protocolo} · Puerto ${printer.puerto}")
                                            Text("Tocar para seleccionar", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { popup = false; finder.detener(); buscando = false }) { Text("Cerrar") } }
                    )
                }
            }
        }
    }

    private fun renderFirstPage(file: File): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = minOf(1400f / page.width, 1800f / page.height, 2f)
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun imageToPdf(bitmap: Bitmap, destination: File): File {
        val doc = android.graphics.pdf.PdfDocument()
        val width = 595
        val height = 842
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, 1).create())
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        page.canvas.drawColor(android.graphics.Color.WHITE)
        page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), paint)
        doc.finishPage(page)
        destination.outputStream().use { doc.writeTo(it) }
        doc.close()
        return destination
    }

    private fun printPdf(file: File) {
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        manager.print(file.name, object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancellationSignal.isCanceled) callback.onLayoutCancelled()
                else callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
                try {
                    FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) } }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) { callback.onWriteFailed(e.localizedMessage) }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
    }

    private fun validIp(value: String): Boolean {
        val parts = value.trim().split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    @Composable
    private fun NumberField(label: String, value: Float, integer: Boolean = false, change: (Float) -> Unit) {
        var text by remember(value) { mutableStateOf(if (integer) value.toInt().toString() else value.toString()) }
        OutlinedTextField(text, { text = it; it.replace(',', '.').toFloatOrNull()?.let(change) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    companion object { const val KEY_IP = "printer_ip" }
}
