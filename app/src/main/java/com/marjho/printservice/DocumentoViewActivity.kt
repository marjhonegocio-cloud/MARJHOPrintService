package com.marjho.printservice

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.marjho.printservice.documents.DocumentoAbierto
import com.marjho.printservice.documents.GestorDocumentos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lector local y de solo lectura para DOCX y documentos de texto. */
class DocumentoViewActivity : ComponentActivity() {
    private var lectorWeb: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var documento by remember { mutableStateOf<DocumentoAbierto?>(null) }
            var html by remember { mutableStateOf<String?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var cargando by remember { mutableStateOf(true) }
            var buscando by remember { mutableStateOf(false) }
            var busqueda by remember { mutableStateOf("") }
            var coincidencias by remember { mutableIntStateOf(0) }
            var zoomTexto by remember { mutableIntStateOf(100) }
            var webReady by remember { mutableStateOf(false) }

            LaunchedEffect(intent.data, intent.getStringExtra(EXTRA_FILE_PATH)) {
                cargando = true
                try {
                    val doc = withContext(Dispatchers.IO) { localizarDocumento() }
                    require(doc.extension == "docx" || doc.texto != null) {
                        "Este lector admite PDF, DOCX y archivos de texto. Abrí imágenes desde Documentos."
                    }
                    documento = doc
                    html = withContext(Dispatchers.IO) {
                        if (doc.extension == "docx") GestorDocumentos.docxComoHtml(doc.archivo)
                        else GestorDocumentos.textoComoHtml(doc.nombre, doc.texto.orEmpty(), doc.extension)
                    }
                } catch (exception: Exception) {
                    error = exception.localizedMessage ?: "No se pudo abrir el documento."
                } finally {
                    cargando = false
                }
            }

            LaunchedEffect(zoomTexto) { lectorWeb?.settings?.textZoom = zoomTexto }

            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(documento?.nombre ?: "Vista de documento", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    documento?.let { Text(if (it.extension == "docx") "Word · vista de lectura" else "Texto · ${it.extension.uppercase()}", style = MaterialTheme.typography.labelSmall) }
                                }
                            },
                            actions = {
                                IconButton(onClick = { zoomTexto = (zoomTexto - 10).coerceAtLeast(80) }) { Text("A−") }
                                Text("$zoomTexto%", style = MaterialTheme.typography.labelSmall)
                                IconButton(onClick = { zoomTexto = (zoomTexto + 10).coerceAtMost(180) }) { Text("A+") }
                                IconButton(onClick = { buscando = !buscando; if (!buscando) busqueda = "" }) { Text("⌕") }
                            }
                        )
                    },
                    bottomBar = {
                        Surface(Modifier.navigationBarsPadding(), shadowElevation = 8.dp) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    enabled = documento != null,
                                    onClick = { documento?.let(::compartirDocumento) }
                                ) { Text("Compartir") }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    enabled = documento != null && webReady,
                                    onClick = { imprimirDocumento(documento, error = { error = it }) }
                                ) { Text("Imprimir") }
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (buscando) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = busqueda,
                                    onValueChange = { query ->
                                        busqueda = query
                                        coincidencias = 0
                                        lectorWeb?.findAllAsync(query)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Buscar en el documento") }
                                )
                                IconButton(onClick = { lectorWeb?.findNext(false) }) { Text("↑") }
                                IconButton(onClick = { lectorWeb?.findNext(true) }) { Text("↓") }
                            }
                            if (busqueda.isNotBlank()) {
                                Text("$coincidencias coincidencias", Modifier.padding(start = 16.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                cargando -> CircularProgressIndicator(Modifier.size(34.dp))
                                error != null -> Text(error.orEmpty(), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                                html != null -> AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { viewContext ->
                                        WebView(viewContext).apply {
                                            lectorWeb = this
                                            settings.javaScriptEnabled = false
                                            settings.domStorageEnabled = false
                                            settings.allowFileAccess = false
                                            settings.allowContentAccess = false
                                            settings.setSupportZoom(true)
                                            settings.builtInZoomControls = true
                                            settings.displayZoomControls = false
                                            settings.textZoom = zoomTexto
                                            setBackgroundColor(android.graphics.Color.rgb(238, 241, 245))
                                            isVerticalScrollBarEnabled = true
                                            webChromeClient = WebChromeClient()
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                                    lectorWeb = null
                                                    webReady = false
                                                    error = "El documento superó la memoria disponible para el visor. Cerralo y abrilo con un lector de Word compatible."
                                                    return true
                                                }
                                            }
                                            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                                                coincidencias = numberOfMatches
                                            }
                                            webReady = true
                                            loadDataWithBaseURL(null, html.orEmpty(), "text/html", "UTF-8", null)
                                        }
                                    },
                                    update = { view ->
                                        lectorWeb = view
                                        view.settings.textZoom = zoomTexto
                                    },
                                    onRelease = { view ->
                                        if (lectorWeb === view) lectorWeb = null
                                        webReady = false
                                        view.stopLoading()
                                        view.clearMatches()
                                        view.destroy()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun localizarDocumento(): DocumentoAbierto {
        val local = intent.getStringExtra(EXTRA_FILE_PATH)
        if (!local.isNullOrBlank()) {
            val candidato = File(local).canonicalFile
            val permitidos = listOf(File(filesDir, "recent_documents").canonicalFile, cacheDir.canonicalFile)
            require(candidato.isFile && permitidos.any { it == candidato.parentFile }) {
                "El archivo local ya no está disponible."
            }
            return withContext(Dispatchers.IO) {
                if (candidato.parentFile == File(filesDir, "recent_documents").canonicalFile) {
                    GestorDocumentos.abrirReciente(this@DocumentoViewActivity, candidato)
                } else {
                    val uri = android.net.Uri.fromFile(candidato)
                    GestorDocumentos.importar(this@DocumentoViewActivity, uri)
                }
            }
        }
        require(intent.action == Intent.ACTION_VIEW) { "Elegí un documento para abrirlo." }
        val uri = requireNotNull(intent.data) { "No se recibió el archivo." }
        return withContext(Dispatchers.IO) { GestorDocumentos.importar(this@DocumentoViewActivity, uri) }
    }

    private fun compartirDocumento(doc: DocumentoAbierto) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", doc.archivo)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = doc.mime.takeUnless { it == "application/octet-stream" } ?: "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, doc.nombre, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir documento"))
    }

    private fun imprimirDocumento(doc: DocumentoAbierto?, error: (String) -> Unit) {
        val view = lectorWeb
        if (doc == null || view == null) {
            error("El visor todavía está preparando el documento.")
            return
        }
        runCatching {
            val manager = getSystemService(PRINT_SERVICE) as PrintManager
            val adapter = view.createPrintDocumentAdapter("${doc.nombreWithoutExtension()}")
            manager.print(
                doc.nombreWithoutExtension(),
                adapter,
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
            )
        }.onFailure { error(it.localizedMessage ?: "No se pudo abrir el servicio de impresión Android.") }
    }

    private fun DocumentoAbierto.nombreWithoutExtension(): String = nombre.substringBeforeLast('.', nombre)

    override fun onDestroy() {
        lectorWeb?.let { view ->
            view.stopLoading()
            view.clearMatches()
            view.destroy()
            lectorWeb = null
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FILE_PATH = "document_path"
        fun intent(context: android.content.Context, file: File) = Intent(context, DocumentoViewActivity::class.java)
            .putExtra(EXTRA_FILE_PATH, file.absolutePath)
    }
}
