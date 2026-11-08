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
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
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
        ZipInputStream(original.inputStream()).use { entrada ->
            ZipOutputStream(FileOutputStream(salida)).use { zipSalida ->
                while (true) {
                    val entry = entrada.nextEntry ?: break
                    val nueva = ZipEntry(entry.name).apply { if (entry.time >= 0L) time = entry.time }
                    zipSalida.putNextEntry(nueva)
                    if (entry.name == "word/document.xml") {
                        val xml = entrada.readBytes()
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
                        entrada.copyTo(zipSalida)
                    }
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
        val dom = ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return@use parseWordXml(ByteArrayInputStream(zip.readBytes()))
                zip.closeEntry()
            }
            null
        } ?: throw IllegalArgumentException("El DOCX no contiene word/document.xml o está dañado.")
        val parrafos = dom.getElementsByTagNameNS(WORD_NS, "p")
        return (0 until parrafos.length).map { leerTextoParrafo(parrafos.item(it)) }.joinToString("\n")
    }

    private fun parseWordXml(input: ByteArrayInputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        return factory.newDocumentBuilder().parse(input)
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
