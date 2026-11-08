package com.marjho.printservice.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
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

object GestorDocumentos {
    fun importar(context: Context, uri: Uri): DocumentoAbierto {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val nombre = queryName(context, uri) ?: "documento"
        val ext = nombre.substringAfterLast('.', "").lowercase()
        val destino = File(context.cacheDir, "marjho_${System.currentTimeMillis()}_${nombre.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir el archivo." }
            FileOutputStream(destino).use { output -> input.copyTo(output) }
        }
        require(destino.length() <= 40L * 1024 * 1024) { "El archivo supera el límite de 40 MB." }

        return when {
            ext == "txt" || mime.startsWith("text/") -> DocumentoAbierto(
                nombre, ext.ifBlank { "txt" }, mime, destino,
                texto = destino.readText(Charsets.UTF_8)
            )
            ext == "docx" -> DocumentoAbierto(
                nombre, ext, mime, destino,
                texto = extraerTextoDocx(destino)
            )
            ext == "pdf" || mime == "application/pdf" -> {
                val pages = ParcelFileDescriptor.open(destino, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    PdfRenderer(it).use { renderer -> renderer.pageCount }
                }
                DocumentoAbierto(nombre, "pdf", mime, destino, paginasPdf = pages)
            }
            ext in listOf("jpg", "jpeg", "png", "webp", "bmp") || mime.startsWith("image/") -> {
                val bitmap = BitmapFactory.decodeFile(destino.absolutePath)
                    ?: throw IllegalArgumentException("No se pudo decodificar la imagen.")
                DocumentoAbierto(nombre, ext, mime, destino, imagen = bitmap)
            }
            else -> DocumentoAbierto(nombre, ext, mime, destino)
        }
    }

    fun guardarTexto(context: Context, nombre: String, texto: String): File {
        val safe = nombre.substringBeforeLast('.', nombre).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(context.cacheDir, "${safe}_editado.txt").apply {
            writeText(texto, Charsets.UTF_8)
        }
    }

    fun guardarImagen(context: Context, bitmap: Bitmap): File {
        return File(context.cacheDir, "marjho_imagen_${System.currentTimeMillis()}.png").also {
            FileOutputStream(it).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    fun rotar(bitmap: Bitmap, grados: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(grados) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }

    private fun extraerTextoDocx(file: File): String {
        val xml = ZipInputStream(file.inputStream()).use { zip ->
            var result = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    result = zip.bufferedReader(Charsets.UTF_8).readText()
                    break
                }
                zip.closeEntry()
            }
            result
        }
        require(xml.isNotBlank()) { "El DOCX no contiene word/document.xml o está dañado." }
        val paragraphs = Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { match ->
                Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(match.groupValues[1])
                    .joinToString("") { decodeXml(it.groupValues[1]) }
            }.filter { it.isNotBlank() }
        return paragraphs.joinToString("\n")
    }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
