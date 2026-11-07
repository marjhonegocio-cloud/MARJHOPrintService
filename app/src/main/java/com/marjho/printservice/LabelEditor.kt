package com.marjho.printservice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.marjho.printservice.editor.EditorEtiquetaState
import com.marjho.printservice.editor.TipoCodigo
import kotlin.math.min

/** Medidas físicas. Los formatos de etiqueta se colocan sobre hojas alimentadas por la L4150. */
data class LabelLayout(
    val paper: String = "A4",
    val paperWidthMm: Float = 210f,
    val paperHeightMm: Float = 297f,
    val labelWidthMm: Float = 70f,
    val labelHeightMm: Float = 35f,
    val columns: Int = 2,
    val rows: Int = 8,
    val marginMm: Float = 5f,
    val gapXmm: Float = 2f,
    val gapYmm: Float = 2f,
    val productName: String = "Nombre del producto",
    val price: String = "$ 1.999,00",
    val barcode: String = "7791234567890",
    val showName: Boolean = true,
    val showPrice: Boolean = true,
    val showBarcode: Boolean = true,
    val individual: Boolean = false
)

object LabelPdfGenerator {
    private const val PT_PER_MM = 72f / 25.4f
    private const val A4_W = 210f
    private const val A4_H = 297f

    fun create(context: Context, layout: LabelLayout, editor: EditorEtiquetaState = EditorEtiquetaState()): File {
        validate(layout)
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "MARJHO_etiquetas_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.pdf")
        file.parentFile?.mkdirs()
        val pdf = PdfDocument()
        try {
            // La Epson L4150 es alimentada por hojas: incluso la etiqueta individual
            // se centra sobre una hoja A4 portadora, conservando sus dimensiones físicas.
            val pageW = (A4_W * PT_PER_MM).toInt()
            val pageH = (A4_H * PT_PER_MM).toInt()
            val perPage = if (layout.individual) 1 else layout.columns * layout.rows
            val pages = 1
            repeat(pages) { pageIndex ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                if (layout.individual) {
                    val x = (A4_W - layout.labelWidthMm) / 2f
                    val y = (A4_H - layout.labelHeightMm) / 2f
                    drawLabel(canvas, x, y, layout.labelWidthMm, layout.labelHeightMm, layout, editor)
                } else {
                    repeat(perPage) { index ->
                        val row = index / layout.columns
                        val col = index % layout.columns
                        val x = layout.marginMm + col * (layout.labelWidthMm + layout.gapXmm)
                        val y = layout.marginMm + row * (layout.labelHeightMm + layout.gapYmm)
                        if (x + layout.labelWidthMm <= A4_W - layout.marginMm + .01f &&
                            y + layout.labelHeightMm <= A4_H - layout.marginMm + .01f) {
                            drawLabel(canvas, x, y, layout.labelWidthMm, layout.labelHeightMm, layout, editor)
                        }
                    }
                }
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
            return file
        } catch (t: Throwable) {
            file.delete(); throw t
        } finally { pdf.close() }
    }

    fun validate(l: LabelLayout) {
        require(l.paperWidthMm in 50f..216f && l.paperHeightMm in 50f..1200f) { "Medidas de papel fuera del rango admitido por el editor." }
        require(l.labelWidthMm in 10f..216f && l.labelHeightMm in 10f..297f) { "La etiqueta debe medir entre 10 y 216 mm de ancho y 10 y 297 mm de alto." }
        require(l.columns in 1..6 && l.rows in 1..12) { "Columnas o filas fuera de rango." }
        if (l.individual) require(l.labelWidthMm <= A4_W && l.labelHeightMm <= A4_H) { "La etiqueta individual debe caber en una hoja A4 portadora." }
        else {
            val totalW = 2 * l.marginMm + l.columns * l.labelWidthMm + (l.columns - 1) * l.gapXmm
            val totalH = 2 * l.marginMm + l.rows * l.labelHeightMm + (l.rows - 1) * l.gapYmm
            require(totalW <= A4_W + .01f && totalH <= A4_H + .01f) {
                "La distribución no cabe en A4. Reducí filas, columnas, márgenes o separación."
            }
        }
    }

    private fun drawLabel(canvas: Canvas, xMm: Float, yMm: Float, wMm: Float, hMm: Float, l: LabelLayout, editor: EditorEtiquetaState) {
        val x = xMm * PT_PER_MM; val y = yMm * PT_PER_MM
        val w = wMm * PT_PER_MM; val h = hMm * PT_PER_MM
        val save = canvas.save(); canvas.translate(x, y); canvas.clipRect(0f, 0f, w, h)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = .55f }
        canvas.drawRect(.3f, .3f, w - .3f, h - .3f, border)
        val pad = min(w, h) * .04f
        if (l.showName) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = editor.nombreSize.coerceIn(8f, 72f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val text = l.productName.take(60)
            val tx = (editor.nombre.x * w).coerceIn(pad, (w - pad).coerceAtLeast(pad))
            val ty = (editor.nombre.y * h + p.textSize).coerceIn(p.textSize, (h - pad).coerceAtLeast(p.textSize))
            while (p.measureText(text) > w - 2 * pad && p.textSize > 5f) p.textSize -= .5f
            canvas.drawText(text, tx, ty, p)
        }
        if (l.showPrice) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = editor.precioSize.coerceIn(8f, 100f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val text = l.price.take(30)
            val tx = (editor.precio.x * w).coerceIn(pad, (w - pad).coerceAtLeast(pad))
            val ty = (editor.precio.y * h + p.textSize).coerceIn(p.textSize, (h - pad).coerceAtLeast(p.textSize))
            while (p.measureText(text) > w - 2 * pad && p.textSize > 5f) p.textSize -= .5f
            canvas.drawText(text, tx, ty, p)
        }
        if (l.showBarcode) {
            val digits = l.barcode.filter(Char::isDigit)
            if (digits.isNotEmpty()) {
                val top = (editor.barcode.y * h).coerceIn(0f, h * .8f)
                val left = (editor.barcode.x * w).coerceIn(0f, w * .8f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = editor.codigoSize.coerceIn(6f, 24f) }
                if (editor.tipoCodigo == TipoCodigo.TEXTO) {
                    canvas.drawText(digits, left, (editor.codigo.y * h).coerceIn(p.textSize, h - pad), p)
                } else {
                    var cursor = left
                    val bottom = (top + h * .18f).coerceAtMost(h - pad)
                    digits.take(32).forEachIndexed { i, c ->
                        val bw = (c.digitToInt() % 4 + 1) * .55f
                        if (cursor + bw < w - pad && i % 2 == 0) canvas.drawRect(cursor, top, cursor + bw, bottom, p)
                        cursor += bw + .55f
                    }
                    canvas.drawText(digits, left, (h - pad).coerceAtLeast(p.textSize), p)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawFit(c: Canvas, text: String, x: Float, baseline: Float, maxW: Float, maxH: Float, color: Int, bold: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.DEFAULT }
        var size = maxH.coerceAtLeast(5f)
        p.textSize = size
        while (p.measureText(text) > maxW && size > 5f) { size -= .5f; p.textSize = size }
        c.drawText(text, x, baseline.coerceAtMost(maxH + (baseline - maxH)), p)
    }
}
