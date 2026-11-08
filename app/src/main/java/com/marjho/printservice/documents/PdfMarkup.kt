package com.marjho.printservice.documents

import android.content.Context
import androidx.annotation.ColorInt
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File

data class PdfPageSize(val width: Int, val height: Int)

data class PdfMarkPoint(val x: Float, val y: Float)

data class PdfMarkStroke(
    val points: List<PdfMarkPoint>,
    @ColorInt val color: Int,
    /** Width in PDF points, independent of screen density and zoom. */
    val width: Float,
    val highlighter: Boolean
)

/** Writes the viewer's freehand marks into a separate PDF copy. */
object PdfMarkup {
    fun export(context: Context, source: File, marks: Map<Int, List<PdfMarkStroke>>): File {
        require(marks.values.any { strokes -> strokes.any { it.points.size > 1 } }) {
            "Dibujá una marca antes de guardar una copia."
        }
        PDFBoxResourceLoader.init(context.applicationContext)
        val output = File(context.cacheDir, "${source.nameWithoutExtension}_anotado_${System.currentTimeMillis()}.pdf")
        PDDocument.load(source).use { document ->
            marks.forEach { (pageIndex, strokes) ->
                if (pageIndex !in 0 until document.numberOfPages) return@forEach
                val page = document.getPage(pageIndex)
                val box = page.mediaBox
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    strokes.filter { it.points.size > 1 }.forEach { mark ->
                        val rgb = mark.color
                        val alpha = if (mark.highlighter) 0.30f else 1f
                        stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                            strokingAlphaConstant = alpha
                        })
                        stream.setStrokingColor(android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))
                        stream.setLineWidth(mark.width.coerceIn(0.8f, 28f))
                        stream.setLineCapStyle(1)
                        stream.setLineJoinStyle(1)
                        val first = toPdfPoint(page, box.width, box.height, mark.points.first())
                        stream.moveTo(first.first, first.second)
                        mark.points.drop(1).forEach { point ->
                            val xy = toPdfPoint(page, box.width, box.height, point)
                            stream.lineTo(xy.first, xy.second)
                        }
                        stream.stroke()
                    }
                }
            }
            document.save(output)
        }
        return output
    }

    /** Convert normalized top-left display coordinates into PDF's bottom-left coordinates. */
    private fun toPdfPoint(page: PDPage, rawWidth: Float, rawHeight: Float, point: PdfMarkPoint): Pair<Float, Float> {
        val x = point.x.coerceIn(0f, 1f)
        val y = point.y.coerceIn(0f, 1f)
        val (pdfX, pdfY) = when (((page.rotation % 360) + 360) % 360) {
            90 -> y * rawWidth to x * rawHeight
            180 -> (1f - x) * rawWidth to y * rawHeight
            270 -> (1f - y) * rawWidth to (1f - x) * rawHeight
            else -> x * rawWidth to (1f - y) * rawHeight
        }
        return pdfX to pdfY
    }
}
