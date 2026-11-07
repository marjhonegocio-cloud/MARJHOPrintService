package com.marjho.printservice.driver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException

/**
 * Impresion ESC/P-R local para Epson L4150, probada con UNA hoja real.
 * Esta ampliacion envia varias paginas dentro de UN trabajo ESC/P-R:
 * iniciarTrabajo -> (comenzarPagina / raster / finalizarPagina)* -> terminarTrabajo.
 *
 * El limite de 20 paginas es deliberado mientras se valida la impresion por lotes.
 * Un socket sin excepcion NO demuestra que haya salido el papel.
 * Ejecutar en Dispatchers.IO; NO invocar desde el hilo principal.
 */
object ImpresorEscprDirecto {
    const val MAX_HOJAS_DIRECTAS = 20

    data class Diagnostico(val disponible: Boolean, val detalle: String)

    data class Avance(
        val hojaActual: Int,
        val totalHojas: Int,
        val porcentajeHoja: Int,
        val hojasTransmitidas: Int
    ) {
        val porcentajeTotal: Int
            get() = ((hojasTransmitidas * 100L + porcentajeHoja) / totalHojas)
                .toInt().coerceIn(0, 100)
    }

    fun diagnosticar(ip: String): Diagnostico {
        validarIp(ip)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 9100), 2500)
                Diagnostico(true, "El puerto TCP 9100 de $ip responde; no confirma el estado del papel.")
            }
        } catch (e: Exception) {
            Diagnostico(false, "No hay conexion a $ip:9100: ${e.localizedMessage ?: "error de red"}.")
        }
    }

    /**
     * Devuelve las estadisticas cuando se han enviado todos los bytes.
     * [progreso] se ejecuta en el hilo de llamada (normalmente IO).
     * [comprobarCancelacion] puede lanzar CancellationException; NO reintentar
     * inmediatamente un trabajo interrumpido, ya que algunas paginas pueden salir.
     */
    fun imprimirHojas(
        ip: String,
        pdf: File,
        progreso: (Avance) -> Unit = {},
        comprobarCancelacion: () -> Unit = {}
    ): String {
        validarIp(ip)
        require(pdf.isFile && pdf.length() > 0L) { "No se encontro el PDF de etiquetas." }

        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val total = renderer.pageCount
                require(total in 1..MAX_HOJAS_DIRECTAS) {
                    "La impresion directa admite de 1 a $MAX_HOJAS_DIRECTAS hojas A4 por trabajo; " +
                        "este PDF contiene $total. Dividi el trabajo o usa impresion Android."
                }

                var hojasCompletadas = 0
                var hojaEnProceso = 0
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        socket.connect(InetSocketAddress(ip, 9100), 3500)
                        val codec = EscprRasterCodec(socket.getOutputStream())
                        val tamano = codec.iniciarTrabajo()

                        for (indice in 0 until total) {
                            comprobarCancelacion()
                            hojaEnProceso = indice + 1
                            progreso(Avance(hojaEnProceso, total, 0, hojasCompletadas))

                            renderer.openPage(indice).use { pagina ->
                                codec.comenzarPagina(hojaEnProceso)
                                enviarRaster(pagina, codec, tamano, comprobarCancelacion) { porcentaje ->
                                    progreso(Avance(hojaEnProceso, total, porcentaje, hojasCompletadas))
                                }
                                codec.finalizarPagina()
                            }
                            hojasCompletadas++
                            progreso(Avance(hojaEnProceso, total, 0, hojasCompletadas))
                        }

                        comprobarCancelacion()
                        codec.terminarTrabajo() // Una sola finalizacion al terminar TODAS las hojas
                        socket.getOutputStream().flush()

                        val original = codec.bytesRgbOriginal / 1024
                        val comprimido = codec.bytesRgbTransmitidos / 1024
                        val ahorro = if (codec.bytesRgbOriginal == 0L) 0L else
                            100L - codec.bytesRgbTransmitidos * 100L / codec.bytesRgbOriginal
                        return "$total hojas enviadas por ESC/P-R a $ip:9100. " +
                            "RGB: $original KB; raster: $comprimido KB " +
                            "(ahorro aproximado: $ahorro %). " +
                            "Datos transmitidos, no confirmacion de impresion fisica. " +
                            "Revisa las hojas antes de repetir."
                    }
                } catch (e: CancellationException) {
                    // No convertir en exito ni interceptar la cancelacion de coroutines.
                    throw e
                } catch (e: Exception) {
                    throw IOException(
                        "Se interrumpio el envio en la hoja ${hojaEnProceso.coerceAtLeast(1)} de $total " +
                            "($hojasCompletadas hojas terminadas en el flujo). " +
                            "Puede haber paginas pendientes o ya impresas. " +
                            "Revisa la Epson antes de reintentar. " +
                            "Detalle: ${e.localizedMessage ?: "error de conexion"}", e
                    )
                }
            }
        }
    }

    private fun enviarRaster(
        pagina: PdfRenderer.Page,
        codec: EscprRasterCodec,
        t: EscprRasterCodec.TamanoImprimible,
        comprobarCancelacion: () -> Unit,
        progreso: (Int) -> Unit
    ) {
        val anchoPapel = t.ancho + 2 * t.margen
        val altoPapel = t.alto + 2 * t.margen
        val horizontal = pagina.width > pagina.height
        val bandaAltura = 40
        val pixels = IntArray(t.ancho * bandaAltura)
        val rgb = ByteArray(t.ancho * 3)
        var y = 0
        var ultimoProgreso = 0

        while (y < t.alto) {
            comprobarCancelacion()
            val filas = minOf(bandaAltura, t.alto - y)
            val bmp = Bitmap.createBitmap(t.ancho, filas, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                val matrix = Matrix()
                if (horizontal) {
                    // Mantener exactamente la transformacion de la Etapa 11.
                    // El PDF horizontal se rota para A4 fisico vertical.
                    matrix.setValues(floatArrayOf(
                        0f, -anchoPapel.toFloat() / pagina.height,
                        anchoPapel.toFloat() - t.margen,
                        altoPapel.toFloat() / pagina.width, 0f,
                        -t.margen.toFloat() - y,
                        0f, 0f, 1f
                    ))
                } else {
                    matrix.setValues(floatArrayOf(
                        anchoPapel.toFloat() / pagina.width, 0f, -t.margen.toFloat(),
                        0f, altoPapel.toFloat() / pagina.height,
                        -t.margen.toFloat() - y,
                        0f, 0f, 1f
                    ))
                }
                pagina.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bmp.getPixels(pixels, 0, t.ancho, 0, 0, t.ancho, filas)
                for (fila in 0 until filas) {
                    var destino = 0
                    val origen = fila * t.ancho
                    for (columna in 0 until t.ancho) {
                        val pixel = pixels[origen + columna]
                        rgb[destino++] = Color.red(pixel).toByte()
                        rgb[destino++] = Color.green(pixel).toByte()
                        rgb[destino++] = Color.blue(pixel).toByte()
                    }
                    codec.escribirLinea(y + fila, rgb)
                }
            } finally {
                bmp.recycle()
            }
            y += filas
            val porcentaje = y * 100 / t.alto
            // Evita miles de actualizaciones en Compose durante varias hojas.
            if (porcentaje >= ultimoProgreso + 10 || y == t.alto) {
                ultimoProgreso = porcentaje
                progreso(porcentaje)
            }
        }
    }

    private fun validarIp(ip: String) {
        val partes = ip.trim().split('.')
        require(partes.size == 4 && partes.all { parte ->
            parte.isNotEmpty() && parte.length <= 3 &&
                parte.all(Char::isDigit) && (parte.toIntOrNull() ?: -1) in 0..255
        }) { "IP de impresora invalida." }
    }
}
