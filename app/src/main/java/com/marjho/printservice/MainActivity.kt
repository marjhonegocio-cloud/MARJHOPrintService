package com.marjho.printservice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfDocument
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marjho.printservice.editor.EditorEtiquetaState
import com.marjho.printservice.editor.EditorEtiquetaCanvas
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
                var status by remember { mutableStateOf("Configurá la IP de tu Epson L4150.") }
                var layout by remember { mutableStateOf(LabelLayout()) }
                var editor by remember { mutableStateOf(EditorEtiquetaState()) }
                var preview by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("MARJHO Print Service", style = MaterialTheme.typography.headlineSmall)
                    Text("Editor de etiquetas y controlador local ESC/P-R para Epson L4150.")
                    OutlinedTextField(ip, { ip = it }, label = { Text("IP de la impresora") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (validIp(ip)) { prefs.edit().putString(KEY_IP, ip).apply(); status = "IP guardada." } else status = "Ingresá una IP IPv4 válida." }) { Text("Guardar IP") }
                        OutlinedButton(onClick = {
                            if (!validIp(ip)) { status = "Ingresá una IP válida." } else {
                                status = "Probando $ip:9100…"
                                scope.launch { status = withContext(Dispatchers.IO) { runCatching { Socket().use { it.connect(InetSocketAddress(ip, 9100), 2500) }; "Puerto 9100 accesible. Esto no confirma impresión física." }.getOrElse { "Sin conexión: ${it.localizedMessage}" } } }
                            }
                        }) { Text("Probar red") }
                    }
                    HorizontalDivider()
                    Text("Editor de papel y etiquetas", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !layout.individual, onClick = { layout = layout.copy(individual = false) }, label = { Text("Hoja con varias") })
                        FilterChip(selected = layout.individual, onClick = { layout = layout.copy(individual = true) }, label = { Text("Etiqueta individual") })
                    }
                    Text("Papel portador: A4 (210 × 297 mm)", style = MaterialTheme.typography.bodyMedium)
                    Text("La Epson L4150 alimenta hojas. En modo individual, la etiqueta se centra en una hoja A4; no se configura alimentación de rollo.", style = MaterialTheme.typography.bodySmall)
                    NumberField("Ancho de etiqueta (mm)", layout.labelWidthMm) { layout = layout.copy(labelWidthMm = it) }
                    NumberField("Alto de etiqueta (mm)", layout.labelHeightMm) { layout = layout.copy(labelHeightMm = it) }
                    if (!layout.individual) {
                        NumberField("Columnas", layout.columns.toFloat(), true) { layout = layout.copy(columns = it.toInt()) }
                        NumberField("Filas", layout.rows.toFloat(), true) { layout = layout.copy(rows = it.toInt()) }
                        NumberField("Margen exterior (mm)", layout.marginMm) { layout = layout.copy(marginMm = it) }
                        NumberField("Separación horizontal (mm)", layout.gapXmm) { layout = layout.copy(gapXmm = it) }
                        NumberField("Separación vertical (mm)", layout.gapYmm) { layout = layout.copy(gapYmm = it) }
                    }
                    OutlinedTextField(layout.productName, { layout = layout.copy(productName = it) }, label = { Text("Nombre de ejemplo") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(layout.price, { layout = layout.copy(price = it) }, label = { Text("Precio de ejemplo") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(layout.barcode, { layout = layout.copy(barcode = it) }, label = { Text("Código de barras (solo dígitos)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row { Checkbox(layout.showName, { layout = layout.copy(showName = it) }); Text("Nombre") }
                        Row { Checkbox(layout.showPrice, { layout = layout.copy(showPrice = it) }); Text("Precio") }
                        Row { Checkbox(layout.showBarcode, { layout = layout.copy(showBarcode = it) }); Text("Código") }
                    }
                    Row { Checkbox(preview, { preview = it }); Text("Mostrar vista previa de hoja") }
                    Text("Editor visual: arrastrá nombre, precio y código para ubicarlos", style = MaterialTheme.typography.titleMedium)
                    EditorEtiquetaCanvas(state = editor, anchoMm = layout.labelWidthMm, altoMm = layout.labelHeightMm, onChange = { editor = it })
                    Text("Tamaño del nombre: ${editor.nombreSize.toInt()} pt")
                    Slider(value = editor.nombreSize, onValueChange = { editor = editor.copy(nombreSize = it) }, valueRange = 8f..72f)
                    Text("Tamaño del precio: ${editor.precioSize.toInt()} pt")
                    Slider(value = editor.precioSize, onValueChange = { editor = editor.copy(precioSize = it) }, valueRange = 8f..100f)
                    if (preview) LabelPreview(layout)
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { LabelPdfGenerator.create(this@MainActivity, layout, editor) }
                                printPdf(file)
                                status = "PDF preparado. Elegí MARJHO Print Service o una impresora disponible en el diálogo."
                            } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo preparar el PDF." }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "Preparando…" else "Vista previa del PDF e imprimir") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }) { Text("Activar MARJHO en ajustes de impresión") }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun printPdf(file: File) {
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        manager.print("Etiquetas MARJHO", object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: android.os.Bundle?) {
                if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
                try { FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) } }; callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
                catch (e: Exception) { callback.onWriteFailed(e.localizedMessage) }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
    }

    private fun validIp(v: String): Boolean = v.split('.').let { it.size == 4 && it.all { p -> p.isNotEmpty() && p.toIntOrNull() in 0..255 } }
    @Composable private fun NumberField(label: String, value: Float, integer: Boolean = false, change: (Float) -> Unit) {
        var text by remember(value) { mutableStateOf(if (integer) value.toInt().toString() else value.toString()) }
        OutlinedTextField(text, { text = it; it.replace(',', '.').toFloatOrNull()?.let(change) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    @Composable private fun LabelPreview(l: LabelLayout) {
        Column(Modifier.fillMaxWidth()) {
            Text("Vista previa · ${if (l.individual) "etiqueta individual" else "hoja A4"}")
            Canvas(Modifier.fillMaxWidth().height(310.dp).padding(8.dp)) {
                val scale = minOf(size.width / 210f, size.height / 297f)
                val pageW = 210f * scale; val pageH = 297f * scale
                val left = (size.width - pageW) / 2f; val top = (size.height - pageH) / 2f
                drawRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(pageW, pageH))
                drawRect(Color.DarkGray, Offset(left, top), androidx.compose.ui.geometry.Size(pageW, pageH), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                val count = if (l.individual) 1 else (l.columns * l.rows).coerceAtMost(72)
                repeat(count) { i ->
                    val xMm = if (l.individual) (210f-l.labelWidthMm)/2f else l.marginMm + (i % l.columns) * (l.labelWidthMm+l.gapXmm)
                    val yMm = if (l.individual) (297f-l.labelHeightMm)/2f else l.marginMm + (i / l.columns) * (l.labelHeightMm+l.gapYmm)
                    val x = left + xMm*scale; val y = top+yMm*scale; val w=l.labelWidthMm*scale; val h=l.labelHeightMm*scale
                    drawRect(Color.Black, Offset(x,y), androidx.compose.ui.geometry.Size(w,h), style=androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx()))
                    drawContext.canvas.nativeCanvas.apply {
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = (h*.19f).coerceAtLeast(5f) }
                        if (l.showName) drawText(l.productName.take(24), x+3, y+h*.30f, p)
                        if (l.showPrice) { p.typeface = android.graphics.Typeface.DEFAULT_BOLD; p.textSize=(h*.27f).coerceAtLeast(6f); drawText(l.price.take(18), x+3, y+h*.64f, p) }
                        if (l.showBarcode) { p.typeface=android.graphics.Typeface.DEFAULT; p.textSize=(h*.12f).coerceAtLeast(4f); drawText("|||| |||| ||||", x+3, y+h*.88f, p) }
                    }
                }
            }
        }
    }
    companion object { const val KEY_IP = "printer_ip" }
}
