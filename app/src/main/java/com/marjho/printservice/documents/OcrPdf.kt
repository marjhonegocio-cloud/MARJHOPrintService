package com.marjho.printservice.documents

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR local para PDF escaneados: procesa una página por vez y no envía el archivo a Internet. */
object OcrPdf {
    suspend fun extraerTexto(
        archivo: File,
        progreso: suspend (pagina: Int, total: Int) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val salida = StringBuilder()
            ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "El PDF no contiene páginas." }
                    for (indice in 0 until renderer.pageCount) {
                        progreso(indice + 1, renderer.pageCount)
                        val textoPagina = renderer.openPage(indice).use { page ->
                            val escala = minOf(2.8f, 2200f / maxOf(page.width, page.height))
                            val bitmap = Bitmap.createBitmap(
                                (page.width * escala).toInt().coerceAtLeast(1),
                                (page.height * escala).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask().text
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        if (salida.isNotEmpty()) salida.append("\n\n\n\n")
                        salida.append(textoPagina)
                    }
                }
            }
            salida.toString().trim()
        } finally {
            recognizer.close()
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { resultado ->
            if (continuation.isActive) continuation.resume(resultado)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
