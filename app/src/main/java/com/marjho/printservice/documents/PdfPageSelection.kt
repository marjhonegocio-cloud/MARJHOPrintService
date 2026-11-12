package com.marjho.printservice.documents

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Parses user-facing 1-based page numbers and creates a compact PDF containing only those pages. */
object PdfPageSelection {
    fun parse(text: String, pageCount: Int): List<Int> {
        require(pageCount > 0) { "El documento no contiene páginas." }
        require(text.isNotBlank()) { "Escribí al menos una página, por ejemplo 1, 3-5." }

        val selected = sortedSetOf<Int>()
        text.split(',').forEach { rawPart ->
            val part = rawPart.trim()
            require(part.isNotEmpty()) { "Revisá la lista: hay una página vacía entre comas." }
            val bounds = part.split('-').map(String::trim)
            require(bounds.size in 1..2 && bounds.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                "Usá números separados por comas o rangos, por ejemplo 1, 3-5, 8."
            }
            val first = bounds[0].toIntOrNull()
                ?: throw IllegalArgumentException("El número de página es demasiado grande.")
            val last = if (bounds.size == 2) bounds[1].toIntOrNull()
                ?: throw IllegalArgumentException("El número de página es demasiado grande.") else first
            require(first in 1..pageCount && last in 1..pageCount) {
                "Las páginas deben estar entre 1 y $pageCount."
            }
            require(first <= last) { "En cada rango, la primera página debe ser menor o igual a la última." }
            for (page in first..last) selected.add(page - 1)
        }
        require(selected.isNotEmpty()) { "Elegí al menos una página." }
        return selected.toList()
    }

    /** Returns the original file for an all-pages request; otherwise writes a temporary subset. */
    fun createPdf(context: Context, source: File, pageIndexes: List<Int>): File {
        require(source.isFile && source.length() > 0L) { "El PDF no existe o está vacío." }
        val requested = pageIndexes.distinct().sorted()
        require(requested.isNotEmpty()) { "Elegí al menos una página para imprimir." }

        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source).use { document ->
            require(requested.all { it in 0 until document.numberOfPages }) {
                "La selección contiene una página fuera del documento."
            }
            if (requested.size == document.numberOfPages && requested.first() == 0 &&
                requested.last() == document.numberOfPages - 1
            ) return source

            val output = File.createTempFile("marjho-paginas-", ".pdf", context.cacheDir)
            try {
                val keep = requested.toHashSet()
                for (index in document.numberOfPages - 1 downTo 0) {
                    if (index !in keep) document.removePage(index)
                }
                document.save(output)
                require(output.isFile && output.length() > 0L) { "No se pudo preparar el PDF seleccionado." }
                return output
            } catch (error: Exception) {
                output.delete()
                throw error
            }
        }
    }
}
