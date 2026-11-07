package com.marjho.printservice.editor

import androidx.compose.ui.geometry.Offset

enum class TipoCodigo {
    TEXTO,
    BARRAS
}

data class EditorEtiquetaState(

    // POSICIONES RELATIVAS (0f - 1f)
    val nombre: Offset = Offset(0.05f, 0.18f),
    val precio: Offset = Offset(0.05f, 0.48f),
    val barcode: Offset = Offset(0.05f, 0.70f),
    val codigo: Offset = Offset(0.05f, 0.90f),

    // ESCALAS (PDF POINTS)
    val precioSize: Float = 48f,
    val nombreSize: Float = 24f,
    val codigoSize: Float = 12f,

    val tipoCodigo: TipoCodigo = TipoCodigo.BARRAS,

    // Casilla global para toda la hoja: barras y número (o número en góndola compacta).
    // Si es false, el diseño agranda automáticamente nombre y precio.
    val imprimirCodigo: Boolean = true
)