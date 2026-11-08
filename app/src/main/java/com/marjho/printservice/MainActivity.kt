package com.marjho.printservice

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import com.marjho.printservice.editor.EditorEtiquetaCanvas
import com.marjho.printservice.editor.EditorEtiquetaState
import com.marjho.printservice.network.BuscadorImpresorasRed
import com.marjho.printservice.network.DestinoImpresion
import com.marjho.printservice.network.ImpresoraEncontrada
import com.marjho.printservice.network.ProtocoloImpresion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

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
                var destino by remember { mutableStateOf(DestinoImpresion.desde(prefs)) }
                var popup by remember { mutableStateOf(false) }
                var buscando by remember { mutableStateOf(false) }
                var busquedaAutomatica by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var servicioHabilitado by remember { mutableStateOf(isMarjhoPrintServiceEnabled()) }
                var documento by remember { mutableStateOf<DocumentoAbierto?>(null) }
                var textoEditado by remember { mutableStateOf("") }
                var imagenEditada by remember { mutableStateOf<Bitmap?>(null) }
                var pdfPreview by remember { mutableStateOf<Bitmap?>(null) }
                var paginaPdf by remember { mutableIntStateOf(0) }
                var marcaPdf by remember { mutableStateOf("") }
                var brilloImagen by remember { mutableFloatStateOf(0f) }
                var contrasteImagen by remember { mutableFloatStateOf(0f) }
                var recorteImagen by remember { mutableFloatStateOf(1f) }
                var escalaGrises by remember { mutableStateOf(false) }
                var formatoImagen by remember { mutableStateOf("PNG") }
                var menuFormatoImagen by remember { mutableStateOf(false) }
                var archivoParaExportar by remember { mutableStateOf<File?>(null) }
                var editorEtiquetas by remember { mutableStateOf(false) }
                var layout by remember { mutableStateOf(LabelLayout()) }
                var editor by remember { mutableStateOf(EditorEtiquetaState()) }
                val scope = rememberCoroutineScope()
                val finder = remember { BuscadorImpresorasRed(this@MainActivity) }
                val resultadosRecientes = remember { AtomicReference(emptyList<ImpresoraEncontrada>()) }
                val exportador = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    val archivo = archivoParaExportar
                    if (uri != null && archivo != null) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    contentResolver.openOutputStream(uri, "wt")?.use { salida ->
                                        archivo.inputStream().use { entrada -> entrada.copyTo(salida) }
                                    } ?: error("No se pudo abrir el destino para guardar.")
                                }
                                status = "Archivo guardado: ${uri.lastPathSegment ?: archivo.name}"
                            } catch (e: Exception) {
                                status = "No se pudo guardar: ${e.localizedMessage}"
                            }
                        }
                    }
                }
                val ajustesImpresion = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    servicioHabilitado = isMarjhoPrintServiceEnabled()
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) {
                        busy = true
                        scope.launch {
                            try {
                                val loaded = withContext(Dispatchers.IO) { GestorDocumentos.importar(this@MainActivity, uri) }
                                documento = loaded
                                textoEditado = loaded.texto.orEmpty()
                                imagenEditada = loaded.imagen
                                paginaPdf = 0
                                pdfPreview = if (loaded.extension == "pdf") withContext(Dispatchers.IO) { renderPdfPage(loaded.archivo, 0) } else null
                                status = "Abierto: ${loaded.nombre}"
                            } catch (e: Exception) {
                                status = "No se pudo abrir: ${e.localizedMessage}"
                            } finally { busy = false }
                        }
                    }
                }

                DisposableEffect(Unit) { onDispose { finder.detener() } }

                fun seleccionarImpresora(printer: ImpresoraEncontrada) {
                    seleccionada = printer
                    ip = printer.ip
                    popup = false
                    buscando = false
                    finder.detener()
                    busy = true
                    status = "Consultando capacidades de ${printer.nombre}…"
                    scope.launch {
                        try {
                            val reporte = withContext(Dispatchers.IO) { ProtocoloImpresion.analizar(printer) }
                            val base = reporte.comoDestino()
                            val guardado = if (base.esEpsonL4150) base.copy(
                                forzarEscpr = true,
                                rutaDirecta = "ESC/P-R: TCP 9100 (directo seleccionado)"
                            ) else base
                            guardado.guardar(prefs)
                            destino = guardado
                            status = reporte.resumen()
                        } catch (e: Exception) {
                            status = "Se detectó ${printer.nombre} (${printer.ip}), pero no se pudieron consultar sus capacidades: ${e.localizedMessage}"
                        } finally { busy = false }
                    }
                }

                LaunchedEffect(Unit) {
                    buscando = true
                    status = "Buscando automáticamente impresoras en la red local…"
                    finder.buscar(
                        { resultadosRecientes.set(it); impresoras = it },
                        { mensaje ->
                            status = mensaje
                            if (mensaje.startsWith("Búsqueda terminada")) {
                                buscando = false
                                val destinoListo = destino?.let { it.puedeImprimirPdfPorIpp || it.esEpsonL4150 } == true
                                if (busquedaAutomatica && !destinoListo) {
                                    val detectadas = resultadosRecientes.get()
                                    when (detectadas.size) {
                                        0 -> status = "No se encontró un anuncio de impresora. Podés probar con la IP manual."
                                        1 -> seleccionarImpresora(detectadas.first())
                                        else -> {
                                            status = "Se detectaron ${detectadas.size} anuncios; elegí el destino de impresión."
                                            popup = true
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                fun exportar(archivo: File, nombre: String) {
                    archivoParaExportar = archivo
                    exportador.launch(nombre)
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("MARJHO Print Service", style = MaterialTheme.typography.headlineSmall)
                    Text("Impresión local · documentos · imágenes · etiquetas", style = MaterialTheme.typography.bodyMedium)

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("1. Impresora de red", style = MaterialTheme.typography.titleLarge)
                            Text(destino?.let { "Destino: ${it.nombre} · ${it.ip}\n${it.rutaDirecta}" } ?: "Sin impresora compatible seleccionada")
                            Text(
                                if (servicioHabilitado) "Servicio de impresión Android: habilitado"
                                else "Servicio de impresión Android: deshabilitado (afecta trabajos enviados desde otras apps)."
                            )
                            if (!servicioHabilitado) {
                                Text("La impresión directa ESC/P-R desde MARJHO no necesita activar este servicio.", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                ajustesImpresion.launch(Intent(Settings.ACTION_PRINT_SETTINGS))
                            }) {
                                Text(if (servicioHabilitado) "Abrir ajustes de impresión Android" else "Activar MARJHO para otras apps")
                            }
                            destino?.takeIf { it.esEpsonL4150 }?.let { perfil ->
                                Text("Epson L4150 · ${if (perfil.forzarEscpr) "ESC/P-R directo" else if (perfil.puedeImprimirPdfPorIpp) "IPP PDF" else "ESC/P-R disponible"}")
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                                    val actualizado = perfil.copy(
                                        forzarEscpr = !perfil.forzarEscpr,
                                        rutaDirecta = if (!perfil.forzarEscpr) "ESC/P-R: TCP 9100 (directo seleccionado)"
                                            else if (perfil.puedeImprimirPdfPorIpp) "IPP: PDF" else "ESC/P-R: TCP 9100"
                                    )
                                    actualizado.guardar(prefs)
                                    destino = actualizado
                                    status = if (actualizado.forzarEscpr) "ESC/P-R directo seleccionado para ${perfil.nombre}."
                                        else "Se usará IPP si acepta PDF; ESC/P-R queda como alternativa para la L4150."
                                }) {
                                    Text(if (perfil.forzarEscpr) "Cambiar a IPP automático" else "Forzar ESC/P-R directo")
                                }
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val pdf = withContext(Dispatchers.IO) {
                                                textoToPdf(
                                                    "PRUEBA DE IMPRESION DIRECTA\nMARJHO Print Service\nEpson L4150\nESC/P-R por TCP 9100\n${perfil.ip}",
                                                    File(cacheDir, "marjho_prueba_escpr.pdf")
                                                )
                                            }
                                            status = withContext(Dispatchers.IO) { ProtocoloImpresion.imprimirEscpr(perfil, pdf) }
                                        } catch (e: Exception) {
                                            status = e.localizedMessage ?: "No se pudo enviar la prueba ESC/P-R."
                                        } finally { busy = false }
                                    }
                                }) { Text(if (busy) "Enviando prueba…" else "Imprimir una página de prueba ESC/P-R") }
                            }
                            OutlinedTextField(ip, { ip = it }, label = { Text("Dirección IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !buscando, onClick = {
                                    popup = true; buscando = true; status = "Buscando impresoras en la red…"
                                    busquedaAutomatica = false
                                    resultadosRecientes.set(emptyList())
                                    finder.buscar({ resultadosRecientes.set(it); impresoras = it }, { status = it; if (it.startsWith("Búsqueda terminada")) buscando = false })
                                }) { Text(if (buscando) "Buscando…" else "Buscar impresoras") }
                                OutlinedButton(onClick = {
                                    if (validIp(ip)) {
                                        prefs.edit().putString(KEY_IP, ip.trim()).apply()
                                        DestinoImpresion.limpiar(prefs)
                                        destino = null
                                        seleccionada = null
                                        status = "IP guardada. Se probará IPP al detectar; no se enviará ESC/P-R sin identificar el modelo."
                                    } else status = "Ingresá una dirección IPv4 válida."
                                }) { Text("Guardar IP") }
                            }
                            OutlinedButton(enabled = validIp(ip) && !busy, onClick = {
                                status = "Consultando IPP y TCP 9100 en $ip…"
                                scope.launch {
                                    busy = true
                                    try {
                                        val reporte = withContext(Dispatchers.IO) { ProtocoloImpresion.analizarIp(ip.trim()) }
                                        if (reporte.soportaPdf) {
                                            val elegido = reporte.copy(impresora = reporte.impresora.copy(nombre = "Impresora ${ip.trim()}"))
                                            elegido.comoDestino().guardar(prefs)
                                            destino = elegido.comoDestino()
                                        } else {
                                            status = "${reporte.resumen()}\nLa conexión TCP no identifica por sí sola el lenguaje que entiende la impresora."
                                        }
                                        if (reporte.soportaPdf) status = reporte.resumen()
                                    } catch (e: Exception) {
                                        status = e.localizedMessage ?: "No se pudo consultar la impresora."
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "Consultando…" else "Detectar protocolo por IP") }
                            OutlinedButton(enabled = validIp(ip) && !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val abre = withContext(Dispatchers.IO) { ProtocoloImpresion.puertoAbierto(ip.trim(), 9100, 1500) }
                                        if (!abre) error("$ip no responde en TCP 9100.")
                                        val manual = DestinoImpresion(
                                            ip = ip.trim(), nombre = "Epson L4150", modelo = "Epson L4150",
                                            protocolo = "ESC/P-R TCP 9100 (seleccionado manualmente)", uriIpp = null,
                                            formatos = emptySet(), medios = setOf("iso_a4_210x297mm"), color = true,
                                            resolucionesDpi = setOf(360),
                                            duplex = false, puerto9100Abierto = true,
                                            rutaDirecta = "ESC/P-R: TCP 9100 (directo seleccionado)", forzarEscpr = true
                                        )
                                        manual.guardar(prefs)
                                        destino = manual
                                        status = "Destino configurado como Epson L4150. La respuesta TCP no valida la impresión física."
                                    } catch (e: Exception) {
                                        status = e.localizedMessage ?: "No se pudo comprobar TCP 9100."
                                    } finally { busy = false }
                                }
                            }) { Text("Configurar manualmente como Epson L4150") }
                            Text("La detección automática usa DNS-SD/mDNS e IPP. Si una impresora no anuncia el servicio, ingresá la IP; no se deduce su lenguaje solo por ver abierto TCP 9100.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("2. Documentos y archivos", style = MaterialTheme.typography.titleLarge)
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                picker.launch(arrayOf("*/*"))
                            }) { Text(if (busy) "Abriendo…" else "Abrir PDF, Word, texto o imagen") }
                            documento?.let { doc ->
                                Text("${doc.nombre} · ${doc.extension.uppercase()}")
                                when {
                                    doc.extension != "pdf" && doc.texto != null -> {
                                        Text(
                                            if (doc.extension == "docx") "Editor DOCX básico: modifica el texto de los párrafos del documento (incluye celdas de tabla) y conserva el paquete y sus recursos. Para guardar en DOCX, mantené la cantidad de párrafos. No es una vista fiel de Word ni edita encabezados, pies o paginación."
                                            else "Editor de texto · ${doc.extension.uppercase()}. Se abre como texto plano; no aplica formato visual específico del lenguaje."
                                        , style = MaterialTheme.typography.bodySmall)
                                        OutlinedTextField(
                                            value = textoEditado,
                                            onValueChange = { textoEditado = it },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                                            label = { Text("Contenido") }
                                        )
                                        if (doc.extension == "docx") {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(enabled = !busy, onClick = {
                                                    val f = GestorDocumentos.guardarTexto(this@MainActivity, doc.nombre, textoEditado)
                                                    exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_editado.txt")
                                                }) { Text("Exportar TXT") }
                                                Button(enabled = !busy, onClick = {
                                                    scope.launch {
                                                        busy = true
                                                        try {
                                                            val f = withContext(Dispatchers.IO) {
                                                                GestorDocumentos.guardarDocxEditado(this@MainActivity, doc.archivo, doc.nombre, textoEditado)
                                                            }
                                                            exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_editado.docx")
                                                        } catch (e: Exception) {
                                                            status = e.localizedMessage ?: "No se pudo guardar el DOCX editado."
                                                        } finally { busy = false }
                                                    }
                                                }) { Text(if (busy) "Preparando…" else "Guardar copia DOCX") }
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                                                    val f = GestorDocumentos.guardarTexto(this@MainActivity, doc.nombre, textoEditado)
                                                    exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_editado.txt")
                                                }) { Text("Guardar como TXT") }
                                                Button(modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                                                    val ext = doc.extension.ifBlank { "txt" }
                                                    val f = GestorDocumentos.guardarTexto(this@MainActivity, doc.nombre, textoEditado, ext)
                                                    exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_editado.$ext")
                                                }) { Text("Guardar .${doc.extension.ifBlank { "txt" }}") }
                                            }
                                            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try {
                                                        val f = withContext(Dispatchers.IO) {
                                                            textoToPdf(textoEditado, File(cacheDir, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_lectura.pdf"))
                                                        }
                                                        exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_lectura.pdf")
                                                        status = "PDF de lectura preparado. Se convierte el texto plano; no conserva formato de Word ni de Markdown."
                                                    } catch (e: Exception) {
                                                        status = e.localizedMessage ?: "No se pudo crear el PDF de lectura."
                                                    } finally { busy = false }
                                                }
                                            }) { Text(if (busy) "Preparando…" else "Crear PDF de lectura") }
                                        }
                                    }
                                    doc.extension == "pdf" -> {
                                        Text("PDF · ${doc.paginasPdf} páginas")
                                        pdfPreview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
                                        Text("Página ${paginaPdf + 1} de ${doc.paginasPdf}. El PDF original conserva texto y gráficos al girar o agregar la marca.")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(enabled = paginaPdf > 0 && !busy, onClick = {
                                                paginaPdf--
                                                scope.launch { pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(doc.archivo, paginaPdf) } }
                                            }) { Text("Anterior") }
                                            OutlinedButton(enabled = paginaPdf < doc.paginasPdf - 1 && !busy, onClick = {
                                                paginaPdf++
                                                val destinoPagina = paginaPdf
                                                scope.launch { pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(doc.archivo, destinoPagina) } }
                                            }) { Text("Siguiente") }
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try {
                                                        val editado = withContext(Dispatchers.IO) { GestorDocumentos.rotarPaginaPdf(this@MainActivity, doc.archivo, paginaPdf) }
                                                        documento = doc.copy(archivo = editado, nombre = editado.name)
                                                        pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(editado, paginaPdf) }
                                                        status = "Se giró la página ${paginaPdf + 1}; el contenido original se conservó."
                                                    } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo girar la página." }
                                                    finally { busy = false }
                                                }
                                            }) { Text("Girar página") }
                                        }
                                        OutlinedButton(enabled = doc.paginasPdf > 1 && !busy, onClick = {
                                            scope.launch {
                                                busy = true
                                                try {
                                                    val editado = withContext(Dispatchers.IO) {
                                                        GestorDocumentos.eliminarPaginaPdf(this@MainActivity, doc.archivo, paginaPdf)
                                                    }
                                                    val paginasRestantes = doc.paginasPdf - 1
                                                    paginaPdf = paginaPdf.coerceAtMost(paginasRestantes - 1)
                                                    documento = doc.copy(archivo = editado, nombre = editado.name, paginasPdf = paginasRestantes)
                                                    pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(editado, paginaPdf) }
                                                    status = "Página eliminada de la copia de trabajo. Exportá el PDF para guardar el cambio."
                                                } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo eliminar la página." }
                                                finally { busy = false }
                                            }
                                        }) { Text("Eliminar página actual") }
                                        OutlinedTextField(marcaPdf, { marcaPdf = it }, label = { Text("Texto de marca de agua") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = !busy && marcaPdf.isNotBlank(), onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try {
                                                        val editado = withContext(Dispatchers.IO) { GestorDocumentos.agregarMarcaDeAguaPdf(this@MainActivity, doc.archivo, marcaPdf) }
                                                        documento = doc.copy(archivo = editado, nombre = editado.name)
                                                        pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(editado, paginaPdf) }
                                                        status = "Marca de agua agregada al PDF."
                                                    } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo modificar el PDF." }
                                                    finally { busy = false }
                                                }
                                            }) { Text("Aplicar marca a todas las páginas") }
                                            OutlinedButton(onClick = { exportar(doc.archivo, doc.nombre.substringBeforeLast('.', doc.nombre) + "_editado.pdf") }) { Text("Exportar PDF") }
                                        }
                                        if (doc.texto != null) {
                                            Text("Texto extraído del PDF. Editarlo aquí crea un PDF nuevo de texto; no sustituye las letras dentro del diseño original.", style = MaterialTheme.typography.bodySmall)
                                            OutlinedTextField(
                                                value = textoEditado,
                                                onValueChange = { textoEditado = it },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
                                                label = { Text("Texto extraído") }
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = {
                                                    val f = GestorDocumentos.guardarTexto(this@MainActivity, doc.nombre, textoEditado)
                                                    exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_texto.txt")
                                                }) { Text("Exportar texto TXT") }
                                                Button(enabled = !busy, onClick = {
                                                    scope.launch {
                                                        busy = true
                                                        try {
                                                            val f = withContext(Dispatchers.IO) {
                                                                textoToPdf(textoEditado, File(cacheDir, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_texto_editado.pdf"))
                                                            }
                                                            val pages = withContext(Dispatchers.IO) {
                                                                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
                                                            }
                                                            documento = doc.copy(archivo = f, nombre = f.name, texto = textoEditado, paginasPdf = pages)
                                                            paginaPdf = 0
                                                            pdfPreview = withContext(Dispatchers.IO) { renderPdfPage(f, 0) }
                                                            status = "Se creó un PDF nuevo a partir del texto editado."
                                                        } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo crear el PDF." }
                                                        finally { busy = false }
                                                    }
                                                }) { Text("Crear PDF desde el texto") }
                                            }
                                        } else {
                                            Text("No se extrajo texto seleccionable. Si es un escaneo, esta versión necesita OCR externo para convertirlo en texto.", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    doc.imagen != null -> {
                                        Text("Editor de imagen · giro, espejo, recorte central, brillo, contraste y escala de grises")
                                        (imagenEditada ?: doc.imagen)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch { imagenEditada = withContext(Dispatchers.IO) { GestorDocumentos.rotar(imagenEditada ?: doc.imagen!!, 90f) } }
                                            }) { Text("Girar 90°") }
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch { imagenEditada = withContext(Dispatchers.IO) { GestorDocumentos.voltear(imagenEditada ?: doc.imagen!!, true) } }
                                            }) { Text("Espejo") }
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch { imagenEditada = withContext(Dispatchers.IO) { GestorDocumentos.recortarCentro(imagenEditada ?: doc.imagen!!, recorteImagen) } }
                                            }) { Text("Recortar") }
                                        }
                                        Text("Recorte central ${(recorteImagen * 100).toInt()}%")
                                        Slider(recorteImagen, { recorteImagen = it }, valueRange = 0.55f..1f)
                                        Text("Brillo ${(brilloImagen * 100).toInt()}%")
                                        Slider(brilloImagen, { brilloImagen = it }, valueRange = -0.5f..0.5f)
                                        Text("Contraste ${(contrasteImagen * 100).toInt()}%")
                                        Slider(contrasteImagen, { contrasteImagen = it }, valueRange = -0.5f..0.8f)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row { Checkbox(escalaGrises, { escalaGrises = it }); Text("Escala de grises") }
                                            Button(enabled = !busy, onClick = {
                                                scope.launch {
                                                    busy = true
                                                    try { imagenEditada = withContext(Dispatchers.IO) { GestorDocumentos.ajustarImagen(imagenEditada ?: doc.imagen!!, brilloImagen, contrasteImagen, escalaGrises) } }
                                                    finally { busy = false }
                                                }
                                            }) { Text("Aplicar ajustes") }
                                        }
                                        Box {
                                            OutlinedButton(onClick = { menuFormatoImagen = true }) { Text("Formato de salida: $formatoImagen") }
                                            DropdownMenu(expanded = menuFormatoImagen, onDismissRequest = { menuFormatoImagen = false }) {
                                                listOf("PNG", "JPEG", "WEBP", "BMP").forEach { formato ->
                                                    DropdownMenuItem(text = { Text(formato) }, onClick = { formatoImagen = formato; menuFormatoImagen = false })
                                                }
                                            }
                                        }
                                        OutlinedButton(enabled = !busy, onClick = {
                                            scope.launch {
                                                busy = true
                                                try {
                                                    val f = withContext(Dispatchers.IO) { GestorDocumentos.guardarImagen(this@MainActivity, imagenEditada ?: doc.imagen!!, formatoImagen) }
                                                    exportar(f, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_editada.${formatoImagen.lowercase()}")
                                                } catch (e: Exception) { status = e.localizedMessage ?: "No se pudo convertir la imagen." }
                                                finally { busy = false }
                                            }
                                        }) { Text("Exportar imagen") }
                                    }
                                    doc.extension == "doc" -> Text("El formato Word .DOC antiguo no se puede editar como texto seguro aquí. Abrilo en una aplicación compatible y guardalo como .DOCX o exportalo a PDF.")
                                    else -> Text("Formato reconocido parcialmente. Todavía no hay editor para este tipo de archivo.")
                                }
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy && (doc.extension == "pdf" || doc.texto != null || doc.imagen != null),
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val file = withContext(Dispatchers.IO) {
                                                    if (doc.extension == "pdf") doc.archivo
                                                    else if (doc.texto != null) textoToPdf(textoEditado, File(cacheDir, "${doc.nombre.substringBeforeLast('.', doc.nombre)}_print.pdf"))
                                                    else imageToPdf(imagenEditada ?: doc.imagen!!, File(cacheDir, "marjho_imagen_print.pdf"))
                                                }
                                                val printer = destino
                                                if (printer != null && (printer.puedeImprimirPdfPorIpp || printer.esEpsonL4150)) {
                                                    val result = withContext(Dispatchers.IO) { ProtocoloImpresion.imprimirPdf(printer, file) }
                                                    status = result
                                                } else if (servicioHabilitado) {
                                                    printPdf(file)
                                                    status = "Abierto el selector de impresión Android. El envío directo requiere capacidades IPP confirmadas o el perfil Epson L4150."
                                                } else {
                                                    status = "No envié el trabajo: configura la impresora de red para IPP/PDF o Epson L4150 ESC/P-R directo, o activa MARJHO en ajustes de impresión Android."
                                                }
                                            } catch (e: Exception) { status = e.localizedMessage ?: "Error al imprimir" }
                                            finally { busy = false }
                                        }
                                    }
                                ) { Text(if (busy) "Procesando…" else "Imprimir contenido actual") }
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
                                            val printer = destino
                                            if (printer != null && (printer.puedeImprimirPdfPorIpp || printer.esEpsonL4150)) {
                                                status = withContext(Dispatchers.IO) { ProtocoloImpresion.imprimirPdf(printer, pdf) }
                                            } else if (servicioHabilitado) {
                                                printPdf(pdf); status = "PDF preparado en el selector de impresión Android."
                                            } else {
                                                status = "No envié las etiquetas: configura IPP/PDF o Epson L4150 ESC/P-R directo, o activa MARJHO en ajustes de impresión Android."
                                            }
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
                                            busy = true
                                            status = "Consultando capacidades de ${printer.nombre}…"
                                            scope.launch {
                                                try {
                                                    val reporte = withContext(Dispatchers.IO) { ProtocoloImpresion.analizar(printer) }
                                                    val guardado = reporte.comoDestino()
                                                    guardado.guardar(prefs)
                                                    destino = guardado
                            status = if (guardado.esEpsonL4150) {
                                "${guardado.nombre} · ${guardado.ip}\nESC/P-R directo seleccionado en TCP 9100."
                            } else reporte.resumen()
                                                } catch (e: Exception) {
                                                    status = "Se detectó ${printer.nombre} (${printer.ip}), pero no se pudieron consultar sus capacidades: ${e.localizedMessage}"
                                                } finally { busy = false }
                                            }
                                        }
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(printer.nombre, style = MaterialTheme.typography.titleMedium)
                                            Text("IP: ${printer.ip}")
                                            Text("Servicio: ${printer.protocolo} · Puerto ${printer.puerto}")
                                            printer.modeloAnunciado?.let { Text("Modelo anunciado: $it") }
                                            printer.ippUri?.let { Text("IPP: $it", style = MaterialTheme.typography.bodySmall) }
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

    private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0 || pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
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

    private fun textoToPdf(texto: String, destination: File): File {
        val document = android.graphics.pdf.PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val lineHeight = 17f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
        }
        var number = 0
        var y = margin
        var page: android.graphics.pdf.PdfDocument.Page? = null
        fun newPage() {
            page?.let(document::finishPage)
            number++
            page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number).create())
            page!!.canvas.drawColor(android.graphics.Color.WHITE)
            y = margin
        }
        fun drawLine(line: String) {
            if (y > pageHeight - margin) newPage()
            page!!.canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
        newPage()
        val maxWidth = pageWidth - margin * 2f
        for (paragraph in texto.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (paragraph.isBlank()) { drawLine(""); continue }
            var line = ""
            for (word in paragraph.trim().split(Regex("\\s+"))) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    drawLine(line)
                    line = word
                } else line = candidate
            }
            drawLine(line)
        }
        page?.let(document::finishPage)
        destination.outputStream().use { document.writeTo(it) }
        document.close()
        return destination
    }

    private fun printPdf(file: File) {
        val pageCount = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        manager.print(file.name, object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancellationSignal.isCanceled) callback.onLayoutCancelled()
                else callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build(), true)
            }
            override fun onWrite(pages: Array<out android.print.PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
                try {
                    if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return }
                    FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) } }
                    callback.onWriteFinished(if (pageCount == 0) emptyArray() else arrayOf(android.print.PageRange(0, pageCount - 1)))
                } catch (e: Exception) { callback.onWriteFailed(e.localizedMessage) }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
    }

    private fun isMarjhoPrintServiceEnabled(): Boolean {
        val component = ComponentName(this, MarjhoPrintService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = getSystemService(PRINT_SERVICE) as PrintManager
            return manager.isPrintServiceEnabled(component)
        }
        val enabled = Settings.Secure.getString(contentResolver, "enabled_print_services").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
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
