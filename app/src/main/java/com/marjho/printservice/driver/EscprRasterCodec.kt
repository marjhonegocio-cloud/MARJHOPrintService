package com.marjho.printservice.driver

import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.LocalDateTime

/**
 * Codificador ESC/P-R raster. Experimental: NO es un SDK oficial de Epson.
 *
 * Basado en la secuencia de protocolo documentada por el proyecto abierto
 * python-epson (Jason S. McMullan, MIT, 2016); ver AVISO_LICENCIA_ESCPR.txt.
 *
 * Este flujo por TCP/9100 se verificó físicamente con una página en una
 * Epson L4150 el 2026-11-08. Los trabajos multipágina y otros modelos aún
 * requieren validación en hardware.
 * La salida NO es PDF ni ESC/POS.
 */
class EscprRasterCodec(salida: OutputStream) {
    // Each raster row is encoded with several small writes. Buffer them so a
    // page does not become thousands of tiny TCP packets on mobile Wi-Fi.
    private val out = DataOutputStream(BufferedOutputStream(salida, OUTPUT_BUFFER_BYTES))
    private var iniciado = false
    private var paginaAbierta = false
    private var paginasFinalizadas = 0
    private var altoUtil = 0
    private var anchoUtil = 0
    private var ultimaFila = -1

    var bytesRgbOriginal: Long = 0
        private set
    var bytesRgbTransmitidos: Long = 0
        private set
    var filasComprimidas: Int = 0
        private set

    data class TamanoImprimible(val ancho: Int, val alto: Int, val margen: Int)

    fun iniciarTrabajo(nombre: String = "MARJHO-PRINT"): TamanoImprimible {
        check(!iniciado) { "El trabajo ya empezó" }
        iniciado = true
        // Secuencia EJL para abandonar packet mode y preparar ESC/P-R.
        bytes(0, 0, 0, 27, 1)
        texto("@EJL 1284.4\n@EJL     \n")
        bytes(27, 64)
        bytes(27, 40, 82, 8, 0, 0)
        texto("REMOTE1")
        val ahora = LocalDateTime.now()
        val hora = java.io.ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeShort(ahora.year)
                writeByte(ahora.monthValue)
                writeByte(ahora.dayOfMonth)
                writeByte(ahora.hour)
                writeByte(ahora.minute)
                writeByte(ahora.second)
            }
        }.toByteArray()
        remoto("TI", hora)
        remoto("JS", byteArrayOf(0, 0, 0))
        val cabecera = java.io.ByteArrayOutputStream().also { b ->
            DataOutputStream(b).writeInt(0)
        }.toByteArray()
        // El encabezado JH de referencia utiliza un nombre fijo de 8 caracteres.
        // Un nombre variable de longitud arbitraria puede no ser aceptado.
        remoto("JH", byteArrayOf(0) + cabecera + nombre.take(8).padEnd(8, ' ').toByteArray(Charsets.US_ASCII))
        remoto("HD", byteArrayOf(3, 4))
        remoto("PP", byteArrayOf(1, 0)) // Alimentación trasera
        bytes(27, 0, 0, 0) // Salir de REMOTE1

        bytes(27, 40, 82, 6, 0, 0)
        texto("ESCPR")
        // Papel normal, calidad NORMAL, color RGB, sin paleta.
        raster('q', "setq", byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0))

        // A4 vertical a 360dpi, incluso cuando el PDF original es horizontal.
        // En ese caso Android gira el contenido al rasterizarlo.
        val dpi = 360
        val anchoPapel = kotlin.math.ceil(210.0 / 25.4 * dpi).toInt()
        val altoPapel = kotlin.math.ceil(297.0 / 25.4 * dpi).toInt()
        val margen = kotlin.math.floor(3.0 / 25.4 * dpi).toInt()
        anchoUtil = anchoPapel - 2 * margen
        altoUtil = altoPapel - 2 * margen
        val cab = java.io.ByteArrayOutputStream()
        DataOutputStream(cab).apply {
            writeInt(anchoPapel)
            writeInt(altoPapel)
            writeShort(margen) // Margen superior
            writeShort(margen) // Margen izquierdo
            writeInt(anchoUtil)
            writeInt(altoUtil)
            writeByte(0) // 360 dpi
            writeByte(0) // Bidireccional
        }
        raster('j', "setj", cab.toByteArray())
        return TamanoImprimible(anchoUtil, altoUtil, margen)
    }

    fun comenzarPagina(numero: Int = 1) {
        check(iniciado && !paginaAbierta) { "Orden de páginas inválido" }
        require(numero in 1..99) { "Número de página fuera de rango" }
        raster('p', "sttp", byteArrayOf())
        raster('p', "setn", byteArrayOf(numero.toByte()))
        paginaAbierta = true
        ultimaFila = -1
    }

    /** Envia una línea RGB (3 bytes por píxel) sin compresión, fila y coordenadas. */
    fun escribirLinea(y: Int, rgb: ByteArray) {
        check(paginaAbierta) { "Todavía no comenzó la página" }
        require(y == ultimaFila + 1 && y in 0 until altoUtil) { "Orden de filas inválido" }
        require(rgb.size == anchoUtil * 3) { "El ancho RGB no coincide con el papel" }
        require(rgb.size <= 65535) { "La línea supera el límite del protocolo" }
        val cab = ByteArray(7)
        cab[2] = ((y ushr 8) and 0xFF).toByte()
        cab[3] = (y and 0xFF).toByte()
        // RLE por tríos RGB: evita enviar unos 36 MB por hoja A4 cuando
        // gran parte del papel es blanco. Si no conviene, enviamos RGB crudo.
        val comprimido = RleEscpr.comprimirRgb(rgb)
        val usarRle = comprimido.size < rgb.size
        val datos = if (usarRle) comprimido else rgb
        require(datos.size <= 65535) { "La línea excede el máximo ESC/P-R" }
        cab[4] = if (usarRle) 1 else 0
        cab[5] = ((datos.size ushr 8) and 0xFF).toByte()
        cab[6] = (datos.size and 0xFF).toByte()
        raster('d', "dsnd", cab, datos)
        bytesRgbOriginal += rgb.size
        bytesRgbTransmitidos += datos.size
        if (usarRle) filasComprimidas++
        ultimaFila = y
    }

    fun finalizarPagina() {
        check(paginaAbierta && ultimaFila == altoUtil - 1) { "Página incompleta" }
        raster('p', "endp", byteArrayOf(0))
        paginaAbierta = false
        paginasFinalizadas++
        out.flush()
    }

    fun terminarTrabajo() {
        check(iniciado && !paginaAbierta && paginasFinalizadas > 0) { "No hay trabajo completo" }
        raster('j', "endj", byteArrayOf())
        bytes(27, 64)
        bytes(27, 40, 82, 8, 0, 0)
        texto("REMOTE1")
        remoto("LD", byteArrayOf())
        remoto("JE", byteArrayOf())
        bytes(27, 0, 0, 0)
        out.flush()
        iniciado = false
    }

    private fun remoto(nombre: String, datos: ByteArray) {
        require(nombre.length == 2)
        texto(nombre)
        val len = datos.size + 1
        bytes(len and 255, (len ushr 8) and 255, 0)
        out.write(datos)
    }

    private fun raster(tipo: Char, nombre: String, datos: ByteArray, resto: ByteArray = byteArrayOf()) {
        require(nombre.length == 4)
        val largo = datos.size + resto.size
        bytes(27, tipo.code)
        bytes(largo and 255, (largo ushr 8) and 255, (largo ushr 16) and 255, (largo ushr 24) and 255)
        texto(nombre)
        out.write(datos)
        if (resto.isNotEmpty()) out.write(resto)
    }

    private fun bytes(vararg valores: Int) = valores.forEach { out.writeByte(it) }
    private fun texto(valor: String) = out.write(valor.toByteArray(Charsets.US_ASCII))

    private companion object {
        const val OUTPUT_BUFFER_BYTES = 64 * 1024
    }
}
