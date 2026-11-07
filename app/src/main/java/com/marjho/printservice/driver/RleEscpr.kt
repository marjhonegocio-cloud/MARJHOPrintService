package com.marjho.printservice.driver

import java.io.ByteArrayOutputStream

/**
 * Compresión RLE por píxeles RGB (3 bytes por píxel) para ESC/P-R.
 *
 * Los bloques de repetición usan un contador negativo (estilo PackBits),
 * los bloques literales usan un contador positivo. Cada bloque representa
 * entre 1 y 128 píxeles, NO bytes individuales.
 *
 * Basado en el formato RLE documentado por python-epson (MIT).
 * No es una implementación oficial del SDK de Epson.
 */
internal object RleEscpr {
    fun comprimirRgb(rgb: ByteArray): ByteArray {
        require(rgb.size % 3 == 0) { "Una fila RGB debe tener 3 bytes por píxel" }
        val pixeles = rgb.size / 3
        val salida = ByteArrayOutputStream(rgb.size / 2 + 64)
        var i = 0
        while (i < pixeles) {
            val repetidos = contarRepetidos(rgb, i, pixeles)
            if (repetidos >= 2) {
                // 0xFF = repetir 2 píxeles; 0x81 = repetir 128.
                salida.write((1 - repetidos) and 0xff)
                salida.write(rgb, i * 3, 3)
                i += repetidos
            } else {
                val comienzo = i
                i++
                while (i < pixeles && i - comienzo < 128) {
                    if (contarRepetidos(rgb, i, pixeles) >= 2) break
                    i++
                }
                val cantidad = i - comienzo
                salida.write(cantidad - 1)
                salida.write(rgb, comienzo * 3, cantidad * 3)
            }
        }
        return salida.toByteArray()
    }

    private fun contarRepetidos(rgb: ByteArray, pixel: Int, total: Int): Int {
        val base = pixel * 3
        var cantidad = 1
        while (pixel + cantidad < total && cantidad < 128) {
            val otro = (pixel + cantidad) * 3
            if (rgb[base] != rgb[otro] ||
                rgb[base + 1] != rgb[otro + 1] ||
                rgb[base + 2] != rgb[otro + 2]) break
            cantidad++
        }
        return cantidad
    }
}
