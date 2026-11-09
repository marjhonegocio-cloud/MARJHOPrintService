package com.marjho.printservice.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.util.zip.ZipInputStream

data class DocumentoAbierto(
    val nombre: String,
    val extension: String,
    val mime: String,
    val archivo: File,
    val texto: String? = null,
    val imagen: Bitmap? = null,
    val paginasPdf: Int = 0
)

data class DocumentoReciente(
    val archivo: File,
    val nombre: String,
    val extension: String,
    val modificado: Long
)

object GestorDocumentos {
    fun admiteVisorTexto(extension: String): Boolean = extension.lowercase() in TEXT_EXTENSIONS

    fun importar(context: Context, uri: Uri, leerTextoPdf: Boolean = true): DocumentoAbierto {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val nombreOriginal = queryName(context, uri) ?: "documento"
        val nombre = if (mime == "application/pdf" && !nombreOriginal.endsWith(".pdf", true)) "$nombreOriginal.pdf" else nombreOriginal
        val ext = nombre.substringAfterLast('.', "").lowercase()
        val nombreSeguro = nombre.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "documento" }
        val destino = File(directorioRecientes(context), "${System.currentTimeMillis()}_${System.nanoTime()}__$nombreSeguro")
        destino.parentFile?.mkdirs()
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo." }
                FileOutputStream(destino).use { output -> copiarConLimite(input, output, MAX_FILE_BYTES) }
            }
            val documento = clasificar(context, destino, nombre, mime, leerTextoPdf)
            limitarRecientes(context)
            return documento
        } catch (e: Exception) {
            destino.delete()
            throw e
        }
    }

    fun listarRecientes(context: Context): List<DocumentoReciente> = directorioRecientes(context)
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && "__" in it.name }
        .sortedByDescending(File::lastModified)
        .take(MAX_RECENT_FILES)
        .map { archivo ->
            val nombre = archivo.name.substringAfter("__")
            DocumentoReciente(archivo, nombre, nombre.substringAfterLast('.', "").lowercase(), archivo.lastModified())
        }
        .toList()

    fun abrirReciente(context: Context, archivo: File): DocumentoAbierto {
        val directorio = directorioRecientes(context).canonicalFile
        val seguro = archivo.canonicalFile
        require(seguro.isFile && seguro.parentFile == directorio && "__" in seguro.name) {
            "El archivo reciente ya no está disponible. Volvé a seleccionarlo."
        }
        val nombre = seguro.name.substringAfter("__")
        val extension = nombre.substringAfterLast('.', "").lowercase()
        return clasificar(context, seguro, nombre, mimePorExtension(extension))
    }

    private fun clasificar(
        context: Context,
        destino: File,
        nombre: String,
        mime: String,
        leerTextoPdf: Boolean = true
    ): DocumentoAbierto {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" || esPdf(destino) -> {
                val pages = ParcelFileDescriptor.open(destino, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    PdfRenderer(it).use { renderer -> renderer.pageCount }
                }
                val texto = if (leerTextoPdf) {
                    runCatching { extraerTextoPdf(context, destino).take(500_000).takeIf(String::isNotBlank) }.getOrNull()
                } else null
                DocumentoAbierto(nombre, "pdf", "application/pdf", destino, texto = texto, paginasPdf = pages)
            }
            ext == "docx" || mime == DOCX_MIME -> DocumentoAbierto(
                nombre, ext.ifBlank { "docx" }, mime, destino,
                texto = extraerTextoDocx(destino)
            )
            ext in listOf("jpg", "jpeg", "png", "webp", "bmp") || mime.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destino.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "No se pudo leer el tamaño de la imagen." }
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 4096 ||
                    (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 12_000_000L) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(destino.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
                    ?: throw IllegalArgumentException("No se pudo decodificar la imagen.")
                DocumentoAbierto(nombre, ext, mime, destino, imagen = bitmap)
            }
            esTexto(ext, mime, destino) -> DocumentoAbierto(
                nombre, ext.ifBlank { extensionTexto(mime) }, mime, destino,
                texto = leerTexto(destino, ext in TEXT_EXTENSIONS || mime.startsWith("text/"))
            )
            else -> DocumentoAbierto(nombre, ext, mime, destino)
        }
    }

    private fun directorioRecientes(context: Context) = File(context.filesDir, "recent_documents")

    private fun limitarRecientes(context: Context) {
        val archivos = directorioRecientes(context).listFiles().orEmpty()
            .filter { it.isFile && "__" in it.name }
            .sortedByDescending(File::lastModified)
        var total = 0L
        archivos.forEachIndexed { index, archivo ->
            total += archivo.length()
            if (index >= MAX_RECENT_FILES || total > MAX_RECENT_BYTES) {
                total -= archivo.length()
                archivo.delete()
            }
        }
    }

    private fun mimePorExtension(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX_MIME
        "txt", "md", "csv", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    fun guardarTexto(context: Context, nombre: String, texto: String, extension: String = "txt"): File {
        val safe = nombre.substringBeforeLast('.', nombre).replace(Regex("[^A-Za-z0-9_-]"), "_")
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(12).ifBlank { "txt" }
        return File(context.cacheDir, "${safe}_editado.$ext").apply {
            writeText(texto, Charsets.UTF_8)
        }
    }

    /** Actualiza únicamente word/document.xml; los estilos, relaciones e imágenes del DOCX se copian intactos. */
    fun guardarDocxEditado(context: Context, original: File, nombre: String, texto: String): File {
        val base = nombre.substringBeforeLast('.', nombre).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val salida = File(context.cacheDir, "${base}_editado.docx")
        val lineas = texto.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var seActualizo = false
        var totalExpandido = 0L
        ZipInputStream(original.inputStream()).use { entrada ->
            ZipOutputStream(FileOutputStream(salida)).use { zipSalida ->
                while (true) {
                    val entry = entrada.nextEntry ?: break
                    require(!entry.isDirectory || entry.name.length < 240) { "El DOCX contiene una entrada no válida." }
                    val nueva = ZipEntry(entry.name).apply { if (entry.time >= 0L) time = entry.time }
                    zipSalida.putNextEntry(nueva)
                    if (entry.name == "word/document.xml") {
                        val xml = entrada.readLimited(MAX_DOCX_XML_BYTES)
                        totalExpandido += xml.size
                        val dom = parseWordXml(ByteArrayInputStream(xml))
                        val parrafos = dom.getElementsByTagNameNS(WORD_NS, "p")
                        require(parrafos.length == lineas.size) {
                            "Para conservar el diseño de Word, el texto editado debe mantener ${parrafos.length} párrafos. " +
                                "Podés guardar una copia TXT si necesitás cambiar la cantidad de párrafos."
                        }
                        for (indice in 0 until parrafos.length) {
                            reemplazarTextoParrafo(parrafos.item(indice) as Element, lineas[indice], dom)
                        }
                        zipSalida.write(serializarXml(dom))
                        seActualizo = true
                    } else {
                        totalExpandido += entrada.copyLimitedTo(zipSalida, MAX_DOCX_EXPANDED_BYTES - totalExpandido)
                    }
                    require(totalExpandido <= MAX_DOCX_EXPANDED_BYTES) { "El DOCX descomprimido supera el límite seguro de 64 MB." }
                    zipSalida.closeEntry()
                    entrada.closeEntry()
                }
            }
        }
        require(seActualizo) { "El archivo DOCX no contiene word/document.xml." }
        return salida
    }

    fun guardarImagen(context: Context, bitmap: Bitmap, formato: String = "PNG"): File {
        val extension = formato.lowercase()
        val salida = File(context.cacheDir, "marjho_imagen_${System.currentTimeMillis()}.$extension")
        if (extension == "bmp") {
            FileOutputStream(salida).use { escribirBmp24(bitmap, it) }
            return salida
        }
        val compresion = when (extension) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        FileOutputStream(salida).use { out ->
            if (compresion == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) {
                val fondo = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(fondo).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                fondo.compress(compresion, 94, out)
                fondo.recycle()
            } else {
                bitmap.compress(compresion, if (compresion == Bitmap.CompressFormat.PNG) 100 else 92, out)
            }
        }
        return salida
    }

    fun rotar(bitmap: Bitmap, grados: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(grados) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun voltear(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply { postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun recortarCentro(bitmap: Bitmap, porcentaje: Float): Bitmap {
        val factor = porcentaje.coerceIn(0.35f, 1f)
        val ancho = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val alto = (bitmap.height * factor).toInt().coerceAtLeast(1)
        val left = (bitmap.width - ancho) / 2
        val top = (bitmap.height - alto) / 2
        return Bitmap.createBitmap(bitmap, left, top, ancho, alto)
    }

    fun ajustarImagen(bitmap: Bitmap, brillo: Float, contraste: Float, escalaGrises: Boolean): Bitmap {
        val salida = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val factor = (1f + contraste.coerceIn(-1f, 1f)).coerceAtLeast(0f)
        for (i in pixels.indices) {
            val p = pixels[i]
            var r = Color.red(p).toFloat()
            var g = Color.green(p).toFloat()
            var b = Color.blue(p).toFloat()
            if (escalaGrises) {
                val gris = 0.299f * r + 0.587f * g + 0.114f * b
                r = gris; g = gris; b = gris
            }
            r = ((r - 128f) * factor + 128f + brillo * 255f).coerceIn(0f, 255f)
            g = ((g - 128f) * factor + 128f + brillo * 255f).coerceIn(0f, 255f)
            b = ((b - 128f) * factor + 128f + brillo * 255f).coerceIn(0f, 255f)
            pixels[i] = Color.argb(Color.alpha(p), r.toInt(), g.toInt(), b.toInt())
        }
        salida.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return salida
    }

    fun rotarPaginaPdf(context: Context, original: File, indice: Int): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        val salida = File(context.cacheDir, "${original.nameWithoutExtension}_pagina_${indice + 1}_rotada.pdf")
        PDDocument.load(original).use { documento ->
            require(indice in 0 until documento.numberOfPages) { "La página elegida no existe." }
            val pagina = documento.getPage(indice)
            pagina.rotation = (pagina.rotation + 90) % 360
            documento.save(salida)
        }
        return salida
    }

    fun eliminarPaginaPdf(context: Context, original: File, indice: Int): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        val salida = File(context.cacheDir, "${original.nameWithoutExtension}_pagina_${indice + 1}_eliminada.pdf")
        PDDocument.load(original).use { documento ->
            require(indice in 0 until documento.numberOfPages) { "La página elegida no existe." }
            require(documento.numberOfPages > 1) { "Un PDF debe conservar al menos una página." }
            documento.removePage(indice)
            documento.save(salida)
        }
        return salida
    }

    private fun extraerTextoPdf(context: Context, archivo: File): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(archivo).use { PDFTextStripper().getText(it) }
    }

    fun agregarMarcaDeAguaPdf(context: Context, original: File, texto: String): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        val marca = texto.trim().replace(Regex("\\s+"), " ").take(120)
        require(marca.isNotBlank()) { "Escribí el texto de la marca de agua." }
        val salida = File(context.cacheDir, "${original.nameWithoutExtension}_marcado.pdf")
        val fuenteSistema = File("/system/fonts/Roboto-Regular.ttf")
        require(fuenteSistema.isFile) { "No se encontró la fuente del sistema necesaria para guardar la marca." }
        PDDocument.load(original).use { documento ->
            val fuente = FileInputStream(fuenteSistema).use { PDType0Font.load(documento, it) }
            val estado = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.38f }
            for (pagina in documento.pages) {
                val caja: PDRectangle = pagina.mediaBox
                PDPageContentStream(documento, pagina, PDPageContentStream.AppendMode.APPEND, true, true).use { contenido ->
                    contenido.setGraphicsStateParameters(estado)
                    contenido.setNonStrokingColor(65, 77, 92)
                    contenido.beginText()
                    contenido.setFont(fuente, 20f)
                    contenido.newLineAtOffset(28f, (caja.height - 36f).coerceAtLeast(24f))
                    contenido.showText(marca)
                    contenido.endText()
                }
            }
            documento.save(salida)
        }
        return salida
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }

    private fun esPdf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val cabecera = ByteArray(5)
            input.read(cabecera) == cabecera.size && String(cabecera, Charsets.US_ASCII) == "%PDF-"
        }
    }.getOrDefault(false)

    private fun esTexto(extension: String, mime: String, file: File): Boolean {
        if (extension in TEXT_EXTENSIONS || mime.startsWith("text/") || mime in TEXT_MIMES) return true
        return pareceTextoUtf8(file)
    }

    private fun pareceTextoUtf8(file: File): Boolean = runCatching {
        if (file.length() == 0L || file.length() > MAX_TEXT_BYTES) return@runCatching false
        val muestra = ByteArray(minOf(file.length().toInt(), 8192))
        val leidos = file.inputStream().use { it.read(muestra) }
        if (leidos <= 0 || muestra.take(leidos).any { it == 0.toByte() }) return@runCatching false
        val texto = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(muestra, 0, leidos)).toString()
        val controles = texto.count { it.isISOControl() && it !in "\n\r\t\u000C" }
        controles * 100 <= texto.length
    }.getOrDefault(false)

    private fun leerTexto(file: File, permitirLegacy: Boolean): String {
        require(file.length() <= MAX_TEXT_BYTES) {
            "El archivo de texto supera 2 MB; abrilo con un editor especializado para evitar saturar el teléfono."
        }
        val bytes = file.readBytes()
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val inicio = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes, inicio, bytes.size - inicio)).toString()
        } catch (e: Exception) {
            if (!permitirLegacy) throw IllegalArgumentException("El archivo no contiene texto UTF-8 válido.", e)
            Charset.forName("windows-1252").decode(ByteBuffer.wrap(bytes, inicio, bytes.size - inicio)).toString()
        }
    }

    private fun copiarConLimite(input: java.io.InputStream, output: java.io.OutputStream, maximo: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val leidos = input.read(buffer)
            if (leidos < 0) break
            total += leidos
            require(total <= maximo) { "El archivo supera el límite de 40 MB." }
            output.write(buffer, 0, leidos)
        }
    }

    private fun extensionTexto(mime: String): String = when (mime.lowercase()) {
        "application/json" -> "json"
        "application/xml", "text/xml" -> "xml"
        "application/javascript" -> "js"
        else -> "txt"
    }

    private fun extraerTextoDocx(file: File): String {
        val dom = ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("El DOCX no contiene word/document.xml o está dañado.")
            require(entry.size in 1..MAX_DOCX_XML_BYTES) { "El contenido principal de Word supera el límite seguro de 8 MB." }
            zip.getInputStream(entry).use { parseWordXml(ByteArrayInputStream(it.readLimited(MAX_DOCX_XML_BYTES))) }
        }
        val parrafos = dom.getElementsByTagNameNS(WORD_NS, "p")
        return (0 until parrafos.length).map { leerTextoParrafo(parrafos.item(it)) }.joinToString("\n")
    }

    /** Convierte DOCX a una vista HTML local, conservando párrafos, títulos, tablas y formato básico. */
    fun docxComoHtml(file: File): String {
        val document: Document
        val styles: Map<String, String>
        ZipFile(file).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("El DOCX no contiene word/document.xml o está dañado.")
            require(documentEntry.size in 1..MAX_DOCX_XML_BYTES) {
                "El contenido principal de Word supera el límite seguro de 8 MB."
            }
            document = zip.getInputStream(documentEntry).use {
                parseWordXml(ByteArrayInputStream(it.readLimited(MAX_DOCX_XML_BYTES)))
            }
            val styleEntry = zip.getEntry("word/styles.xml")
            styles = if (styleEntry == null || styleEntry.size !in 1..MAX_DOCX_XML_BYTES) emptyMap() else {
                zip.getInputStream(styleEntry).use { parseWordXml(ByteArrayInputStream(it.readLimited(MAX_DOCX_XML_BYTES))) }
                    .let(::extraerEstilosWord)
            }
        }

        val cuerpo = document.getElementsByTagNameNS(WORD_NS, "body").item(0)
            ?: throw IllegalArgumentException("El DOCX no tiene un cuerpo de documento válido.")
        val html = StringBuilder()
        var child = cuerpo.firstChild
        while (child != null) {
            when (child.localName) {
                "p" -> html.append(renderizarParrafoWord(child, styles))
                "tbl" -> html.append(renderizarTablaWord(child, styles))
            }
            require(html.length <= MAX_DOCX_HTML_CHARS) {
                "El DOCX es demasiado extenso para la vista previa segura. Abrilo en una aplicación de Word."
            }
            child = child.nextSibling
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=4">
            <style>
              *{box-sizing:border-box} html,body{margin:0;padding:0;background:#eef1f5;color:#202124;font-family:Roboto,"Noto Sans",Arial,sans-serif;font-size:16px;line-height:1.58}
              body{padding:14px 10px 30px}.paper{width:min(100%,850px);min-height:88vh;margin:0 auto;background:#fff;padding:32px 30px 48px;box-shadow:0 3px 18px #17202a20;border-radius:5px;overflow-wrap:anywhere}
              p{margin:0 0 12px;white-space:pre-wrap}h1,h2,h3,h4{line-height:1.25;margin:22px 0 10px;color:#172b4d}h1{font-size:1.7em}h2{font-size:1.42em}h3{font-size:1.2em}
              table{width:100%;border-collapse:collapse;margin:14px 0;table-layout:fixed}td,th{border:1px solid #aeb7c2;padding:6px 8px;vertical-align:top;overflow-wrap:anywhere}td p,th p{margin:0 0 5px}
              img{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere}
              @media(max-width:480px){body{padding:0;background:#fff}.paper{width:100%;min-height:100vh;padding:20px 17px 36px;border-radius:0;box-shadow:none}}
              @media print{body{background:#fff;padding:0}.paper{width:100%;box-shadow:none;margin:0;padding:0;border-radius:0}}
            </style></head><body><main class="paper">$html</main></body></html>"""
    }

    private fun extraerEstilosWord(dom: Document): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val elements = dom.getElementsByTagNameNS(WORD_NS, "style")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            val id = element.getAttributeNS(WORD_NS, "styleId").ifBlank { element.getAttribute("w:styleId") }
            val name = element.getElementsByTagNameNS(WORD_NS, "name").item(0) as? Element
            val label = name?.getAttributeNS(WORD_NS, "val").orEmpty()
            if (id.isNotBlank()) result[id] = label
        }
        return result
    }

    private fun renderizarParrafoWord(parrafo: Node, styles: Map<String, String>): String {
        val pPr = (parrafo as? Element)?.getElementsByTagNameNS(WORD_NS, "pPr")?.item(0) as? Element
        val styleId = pPr?.getElementsByTagNameNS(WORD_NS, "pStyle")?.item(0)?.let { node ->
            (node as? Element)?.getAttributeNS(WORD_NS, "val") ?: (node as? Element)?.getAttribute("w:val")
        }.orEmpty()
        val name = styles[styleId].orEmpty().lowercase()
        val outline = pPr?.getElementsByTagNameNS(WORD_NS, "outlineLvl")?.item(0)?.let {
            ((it as? Element)?.getAttributeNS(WORD_NS, "val") ?: (it as? Element)?.getAttribute("w:val"))?.toIntOrNull()
        }
        val heading = when {
            outline != null -> (outline + 1).coerceIn(1, 6)
            "title" in name -> 1
            "heading 1" in name || "heading1" in name || "título 1" in name -> 1
            "heading 2" in name || "heading2" in name || "título 2" in name -> 2
            "heading 3" in name || "heading3" in name || "título 3" in name -> 3
            else -> 0
        }
        val align = pPr?.getElementsByTagNameNS(WORD_NS, "jc")?.item(0)?.let {
            ((it as? Element)?.getAttributeNS(WORD_NS, "val") ?: (it as? Element)?.getAttribute("w:val"))
        }
        val alignCss = when (align) { "center" -> "text-align:center;"; "right" -> "text-align:right;"; "both", "distribute" -> "text-align:justify;"; else -> "" }
        val out = StringBuilder()
        var child = parrafo.firstChild
        while (child != null) {
            if (child.localName == "r" || child.localName == "hyperlink" || child.localName == "smartTag") {
                out.append(renderizarInlineWord(child))
            } else if (child.localName == "bookmarkStart" || child.localName == "bookmarkEnd" || child.localName == "pPr") {
                // Structural nodes do not contribute visible text.
            }
            child = child.nextSibling
        }
        val tag = if (heading > 0) "h$heading" else "p"
        return "<$tag style=\"$alignCss\">${out.ifEmpty { "&#8203;" }}</$tag>"
    }

    private fun renderizarInlineWord(node: Node): String {
        if (node.localName == "hyperlink" || node.localName == "smartTag") {
            val out = StringBuilder(); var child = node.firstChild
            while (child != null) { out.append(renderizarInlineWord(child)); child = child.nextSibling }
            return out.toString()
        }
        if (node.localName != "r") return ""
        val run = node as? Element ?: return ""
        val rPr = run.getElementsByTagNameNS(WORD_NS, "rPr").item(0) as? Element
        val declarations = mutableListOf<String>()
        fun enabled(tag: String): Boolean = rPr?.getElementsByTagNameNS(WORD_NS, tag)?.item(0)?.let { el ->
            val value = (el as? Element)?.getAttributeNS(WORD_NS, "val") ?: (el as? Element)?.getAttribute("w:val")
            value != "0" && value != "false" && value != "off"
        } == true
        if (enabled("b")) declarations += "font-weight:700"
        if (enabled("i")) declarations += "font-style:italic"
        if (enabled("u")) declarations += "text-decoration:underline"
        val color = rPr?.getElementsByTagNameNS(WORD_NS, "color")?.item(0)?.let { el ->
            ((el as? Element)?.getAttributeNS(WORD_NS, "val") ?: (el as? Element)?.getAttribute("w:val"))
        }
        if (color != null && color.matches(Regex("[0-9a-fA-F]{6}"))) declarations += "color:#$color"
        val halfPoints = rPr?.getElementsByTagNameNS(WORD_NS, "sz")?.item(0)?.let { el ->
            ((el as? Element)?.getAttributeNS(WORD_NS, "val") ?: (el as? Element)?.getAttribute("w:val"))?.toIntOrNull()
        }
        if (halfPoints != null && halfPoints in 12..96) declarations += "font-size:${halfPoints / 2}pt"
        val content = StringBuilder()
        var child = run.firstChild
        while (child != null) {
            when (child.localName) {
                "t", "delText" -> content.append(escaparHtml(child.textContent))
                "tab" -> content.append("&emsp;")
                "br", "cr" -> content.append("<br>")
                "drawing", "object", "pict" -> content.append("<span style=\"color:#687385;font-size:.9em\">[imagen o elemento gráfico]</span>")
            }
            child = child.nextSibling
        }
        return if (declarations.isEmpty()) content.toString() else "<span style=\"${declarations.joinToString(";")}\">$content</span>"
    }

    private fun renderizarTablaWord(tabla: Node, styles: Map<String, String>): String {
        val output = StringBuilder("<table><tbody>")
        var row = tabla.firstChild
        while (row != null) {
            if (row.localName == "tr") {
                output.append("<tr>")
                var cell = row.firstChild
                while (cell != null) {
                    if (cell.localName == "tc") {
                        output.append("<td>")
                        var block = cell.firstChild
                        while (block != null) {
                            when (block.localName) {
                                "p" -> output.append(renderizarParrafoWord(block, styles))
                                "tbl" -> output.append(renderizarTablaWord(block, styles))
                            }
                            block = block.nextSibling
                        }
                        output.append("</td>")
                    }
                    cell = cell.nextSibling
                }
                output.append("</tr>")
            }
            row = row.nextSibling
        }
        return output.append("</tbody></table>").toString()
    }

    fun textoComoHtml(nombre: String, texto: String, extension: String): String {
        val max = MAX_TEXT_PREVIEW_CHARS
        val source = texto.take(max)
        val safeName = escaparHtml(nombre)
        var body: String
        if (extension in setOf("md", "markdown", "rst")) {
            body = escaparHtml(source)
            body = body.replace(Regex("(?m)^######\\s+(.+)$"), "<h6>$1</h6>")
                .replace(Regex("(?m)^#####\\s+(.+)$"), "<h5>$1</h5>")
                .replace(Regex("(?m)^####\\s+(.+)$"), "<h4>$1</h4>")
                .replace(Regex("(?m)^###\\s+(.+)$"), "<h3>$1</h3>")
                .replace(Regex("(?m)^##\\s+(.+)$"), "<h2>$1</h2>")
                .replace(Regex("(?m)^#\\s+(.+)$"), "<h1>$1</h1>")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
                .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "<em>$1</em>")
                .replace(Regex("`([^`\\n]+)`"), "<code>$1</code>")
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace(Regex("\\n{2,}"), "</p><p>")
                .replace('\n', ' ')
            body = "<article><p>$body</p></article>"
        } else {
            body = "<pre>${escaparHtml(source)}</pre>"
        }
        if (texto.length > max) body += "<p class=\"notice\">Vista previa limitada por memoria. Abrí el archivo en un editor para ver el resto del contenido.</p>"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=4">
            <style>*{box-sizing:border-box}html,body{margin:0;background:#eef1f5;color:#202124;font-family:Roboto,"Noto Sans",Arial,sans-serif;font-size:16px;line-height:1.62}body{padding:14px 10px 32px}.paper{width:min(100%,850px);min-height:88vh;margin:0 auto;background:#fff;padding:30px;box-shadow:0 3px 18px #17202a20;border-radius:5px;overflow-wrap:anywhere}.name{margin:0 0 22px;color:#637083;font-size:13px}pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font-family:"Roboto Mono",monospace;font-size:.9em;line-height:1.55}article{white-space:normal}p{margin:0 0 12px}h1,h2,h3,h4,h5,h6{line-height:1.25;color:#172b4d;margin:20px 0 9px}code{background:#edf0f4;border-radius:4px;padding:1px 4px;font-family:monospace}.notice{margin-top:24px;padding:12px;background:#fff4d6;border-radius:8px;font-size:13px}@media(max-width:480px){body{padding:0;background:#fff}.paper{width:100%;min-height:100vh;padding:20px 17px 36px;border-radius:0;box-shadow:none}}@media print{body{background:#fff;padding:0}.paper{width:100%;box-shadow:none;margin:0;padding:0;border-radius:0}}</style></head>
            <body><main class="paper"><div class="name">$safeName</div>$body</main></body></html>"""
    }

    private fun escaparHtml(value: String): String = buildString(minOf(value.length + 32, MAX_DOCX_HTML_CHARS)) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
            require(length <= MAX_DOCX_HTML_CHARS) { "El documento es demasiado extenso para la vista previa." }
        }
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        require(limit in 1..Int.MAX_VALUE.toLong())
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "El contenido descomprimido supera el límite seguro." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun InputStream.copyLimitedTo(output: java.io.OutputStream, limit: Long): Long {
        require(limit >= 0)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "El DOCX descomprimido supera el límite seguro de 64 MB." }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun parseWordXml(input: ByteArrayInputStream): Document {
        val xmlBytes = input.readLimited(MAX_DOCX_XML_BYTES)
        val xmlText = String(xmlBytes, StandardCharsets.UTF_8)
        require(!xmlText.contains("<!DOCTYPE", ignoreCase = true)) { "El DOCX contiene una declaración XML no permitida." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's built-in parser throws UnsupportedOperationException for XInclude.
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(ByteArrayInputStream(xmlBytes))
    }

    private fun leerTextoParrafo(parrafo: Node): String = buildString {
        fun recorrer(nodo: Node) {
            if (nodo.nodeType == Node.ELEMENT_NODE) {
                when (nodo.localName) {
                    "t" -> append(nodo.textContent)
                    "tab" -> append('\t')
                    // Marca un salto dentro del mismo párrafo sin confundirlo con otro párrafo.
                    "br", "cr" -> append('\u2028')
                }
                if (nodo.localName in setOf("t", "tab", "br", "cr")) return
            }
            var hijo = nodo.firstChild
            while (hijo != null) { recorrer(hijo); hijo = hijo.nextSibling }
        }
        recorrer(parrafo)
    }

    private fun reemplazarTextoParrafo(parrafo: Element, valor: String, dom: Document) {
        // Los saltos y tabulaciones estructurales ya existen en el XML y se conservan allí.
        // No se vuelven a insertar como caracteres dentro de w:t.
        var textoPlano = valor.replace("\u2028", "")
        if (parrafo.getElementsByTagNameNS(WORD_NS, "tab").length > 0) textoPlano = textoPlano.replace("\t", "")
        val textos = parrafo.getElementsByTagNameNS(WORD_NS, "t")
        if (textos.length == 0 && textoPlano.isNotEmpty()) {
            val run = dom.createElementNS(WORD_NS, "w:r")
            val texto = dom.createElementNS(WORD_NS, "w:t").apply { textContent = textoPlano }
            if (textoPlano.firstOrNull()?.isWhitespace() == true || textoPlano.lastOrNull()?.isWhitespace() == true) {
                texto.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
            }
            run.appendChild(texto)
            parrafo.appendChild(run)
            return
        }
        val longitudes = (0 until textos.length).map { textos.item(it).textContent.length }
        val totalAnterior = longitudes.sum()
        var cursor = 0
        for (i in 0 until textos.length) {
            val elemento = textos.item(i) as Element
            val porcion = if (i == textos.length - 1) textoPlano.length - cursor
            else if (totalAnterior == 0) 0
            else ((textoPlano.length.toDouble() * longitudes[i] / totalAnterior).toInt()).coerceAtMost(textoPlano.length - cursor)
            val fin = (cursor + porcion).coerceAtMost(textoPlano.length)
            val parte = textoPlano.substring(cursor, fin)
            cursor = fin
            elemento.textContent = parte
            if (parte.firstOrNull()?.isWhitespace() == true || parte.lastOrNull()?.isWhitespace() == true) {
                elemento.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
            }
        }
    }

    private fun serializarXml(dom: Document): ByteArray {
        val salida = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().apply {
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        transformer.transform(DOMSource(dom), StreamResult(salida))
        return salida.toByteArray()
    }

    private fun escribirBmp24(bitmap: Bitmap, salida: java.io.OutputStream) {
        val stride = (bitmap.width * 3 + 3) and -4
        val bytesImagen = stride.toLong() * bitmap.height
        val cabecera = ByteArray(54)
        fun little32(offset: Int, value: Long) {
            for (i in 0..3) cabecera[offset + i] = (value ushr (i * 8)).toByte()
        }
        cabecera[0] = 'B'.code.toByte(); cabecera[1] = 'M'.code.toByte()
        little32(2, bytesImagen + 54); little32(10, 54); little32(14, 40)
        little32(18, bitmap.width.toLong()); little32(22, bitmap.height.toLong())
        cabecera[26] = 1; cabecera[28] = 24; little32(34, bytesImagen)
        little32(38, 2835); little32(42, 2835)
        salida.write(cabecera)
        val padding = ByteArray(stride - bitmap.width * 3)
        val fila = ByteArray(bitmap.width * 3)
        val pixeles = IntArray(bitmap.width)
        for (y in bitmap.height - 1 downTo 0) {
            bitmap.getPixels(pixeles, 0, bitmap.width, 0, y, bitmap.width, 1)
            var offset = 0
            for (pixel in pixeles) {
                fila[offset++] = Color.blue(pixel).toByte()
                fila[offset++] = Color.green(pixel).toByte()
                fila[offset++] = Color.red(pixel).toByte()
            }
            salida.write(fila)
            if (padding.isNotEmpty()) salida.write(padding)
        }
    }

    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
    private const val MAX_DOCX_XML_BYTES = 8L * 1024 * 1024
    private const val MAX_DOCX_EXPANDED_BYTES = 64L * 1024 * 1024
    private const val MAX_DOCX_HTML_CHARS = 6 * 1024 * 1024
    private const val MAX_TEXT_PREVIEW_CHARS = 700_000
    private const val MAX_RECENT_FILES = 12
    private const val MAX_RECENT_BYTES = 160L * 1024 * 1024
    private val TEXT_MIMES = setOf("application/json", "application/xml", "text/xml", "application/javascript", "application/x-yaml", "application/yaml", "application/sql")
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "rst", "org", "csv", "tsv", "json", "json5", "jsonl", "xml", "xsd", "xsl", "xslt", "html", "htm", "css", "scss", "less",
        "log", "ini", "cfg", "conf", "properties", "env", "yaml", "yml", "toml", "sql", "rtf", "tex", "diff", "patch", "lock", "gitignore", "dockerfile",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "java", "kt", "kts", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "php", "swift",
        "sh", "bash", "zsh", "bat", "cmd", "ps1", "gradle", "mk", "makefile", "ipynb"
    )
}
