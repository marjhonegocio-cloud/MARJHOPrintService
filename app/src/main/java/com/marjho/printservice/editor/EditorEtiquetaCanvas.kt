package com.marjho.printservice.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Editor visual WYSIWYG
 * 1:1 con Preview + PDF
 */
@Composable
fun EditorEtiquetaCanvas(
    state: EditorEtiquetaState,
    anchoMm: Float,
    altoMm: Float,
    onChange: (EditorEtiquetaState) -> Unit
) {
    val scale = 3f // 1 mm ≈ 3dp (visual)

    Column {

        /* =========================
         * 🔘 SELECTOR CÓDIGO
         * ========================= */
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text("Código:")

            SelectorBoton(
                texto = "Texto",
                activo = state.tipoCodigo == TipoCodigo.TEXTO
            ) {
                onChange(state.copy(tipoCodigo = TipoCodigo.TEXTO))
            }

            SelectorBoton(
                texto = "Barras",
                activo = state.tipoCodigo == TipoCodigo.BARRAS
            ) {
                onChange(state.copy(tipoCodigo = TipoCodigo.BARRAS))
            }
        }

        /* =========================
         * 🎚 TAMAÑO CÓDIGO
         * ========================= */
        Text("Tamaño del código")
        Slider(
            value = state.codigoSize,
            onValueChange = {
                onChange(state.copy(codigoSize = it))
            },
            valueRange = 10f..36f
        )

        Spacer(Modifier.height(8.dp))

        /* =========================
         * 🧱 CANVAS
         * ========================= */
        Box(
            modifier = Modifier
                .size((anchoMm * scale).dp, (altoMm * scale).dp)
                .border(1.dp, Color.Gray)
        ) {

            // 📝 Nombre
            EditableText(
                text = "Nombre producto",
                pos = state.nombre,
                size = state.nombreSize,
                anchoMm = anchoMm,
                altoMm = altoMm,
                onMove = { onChange(state.copy(nombre = it)) },
                onResize = { onChange(state.copy(nombreSize = it)) }
            )

            // 💲 Precio
            EditableText(
                text = "$ 9999",
                pos = state.precio,
                size = state.precioSize,
                anchoMm = anchoMm,
                altoMm = altoMm,
                onMove = { onChange(state.copy(precio = it)) },
                onResize = { onChange(state.copy(precioSize = it)) }
            )

            // 🔢 CÓDIGO (UNO SOLO)
            if (state.tipoCodigo == TipoCodigo.TEXTO) {

                EditableText(
                    text = "EAN-13",
                    pos = state.codigo,
                    size = state.codigoSize,
                    anchoMm = anchoMm,
                    altoMm = altoMm,
                    onMove = { onChange(state.copy(codigo = it)) },
                    onResize = { onChange(state.copy(codigoSize = it)) }
                )

            } else {
                // 📦 Placeholder visual de barras (NO draggable)
                Text(
                    text = "|||||||||||||||",
                    fontSize = state.codigoSize.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.offset {
                        IntOffset(
                            (state.barcode.x * anchoMm * scale).roundToInt(),
                            (state.barcode.y * altoMm * scale).roundToInt()
                        )
                    }
                )
            }
        }
    }
}

/* ============================================================
 * 🔘 BOTÓN SELECTOR (CLICK REAL, SIN BUG)
 * ============================================================ */
@Composable
private fun SelectorBoton(
    texto: String,
    activo: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = texto,
        modifier = Modifier
            .border(
                1.dp,
                if (activo) MaterialTheme.colorScheme.primary else Color.Gray
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (activo)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.onSurface
    )
}

/* ============================================================
 * ✍ TEXTO EDITABLE (DRAG + RESIZE)
 * ============================================================ */
@Composable
private fun EditableText(
    text: String,
    pos: Offset,
    size: Float,
    anchoMm: Float,
    altoMm: Float,
    onMove: (Offset) -> Unit,
    onResize: (Float) -> Unit
) {
    val scale = 3f

    Column(
        modifier = Modifier
            .offset {
                IntOffset(
                    (pos.x * anchoMm * scale).roundToInt(),
                    (pos.y * altoMm * scale).roundToInt()
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { _, drag ->
                    onMove(
                        Offset(
                            (pos.x + drag.x / (anchoMm * scale)).coerceIn(0f, 0.9f),
                            (pos.y + drag.y / (altoMm * scale)).coerceIn(0f, 0.9f)
                        )
                    )
                }
            }
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .padding(4.dp)
    ) {

        Text(
            text = text,
            fontSize = size.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Slider(
            value = size,
            onValueChange = onResize,
            valueRange = 8f..48f,
            modifier = Modifier.width(120.dp)
        )
    }
}