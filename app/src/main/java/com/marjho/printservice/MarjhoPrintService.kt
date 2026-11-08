package com.marjho.printservice

import android.content.Context
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.os.ParcelFileDescriptor
import com.marjho.printservice.network.DestinoImpresion
import com.marjho.printservice.network.BuscadorImpresorasRed
import com.marjho.printservice.network.ImpresoraEncontrada
import com.marjho.printservice.network.ProtocoloImpresion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Android PrintService: descubre impresoras de red y usa IPP o el perfil ESC/P-R validado. */
class MarjhoPrintService : PrintService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val destinationsByPrinterId = ConcurrentHashMap<String, DestinoImpresion>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = MarjhoDiscoverySession(this)

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobKey = printJob.id.toString()
        val work = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val printerLocalId = runCatching { printJob.info.printerId?.localId }.getOrNull()
                val destino = printerLocalId?.let(destinationsByPrinterId::get)
                    ?: DestinoImpresion.desde(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                    ?: throw IllegalStateException("Elegí una impresora compatible desde MARJHO Print Service.")
                require(destino.puedeImprimirPdfPorIpp || destino.esEpsonL4150) {
                    "No se confirmó un controlador compatible para la impresora seleccionada."
                }
                printJob.start()
                val pdf = File(cacheDir, "marjho_print_${System.currentTimeMillis()}.pdf")
                try {
                    val documentData = printJob.document.data
                        ?: throw IllegalStateException(
                            "Android no proporcionó el archivo del documento para imprimir."
                        )

                    copyDocument(documentData, pdf)
                    val coroutineJob = currentCoroutineContext()[Job]
                    if (destino.esEpsonL4150 && (destino.forzarEscpr || !destino.puedeImprimirPdfPorIpp)) {
                        ProtocoloImpresion.imprimirEscpr(destino, pdf) {
                            if (coroutineJob?.isActive != true) throw CancellationException("Trabajo cancelado por el usuario.")
                        }
                    } else {
                        if (coroutineJob?.isActive != true) throw CancellationException("Trabajo cancelado por el usuario.")
                        ProtocoloImpresion.imprimirPdf(destino, pdf)
                    }
                    printJob.complete()
                } finally {
                    pdf.delete()
                }
            } catch (cancelled: CancellationException) {
                printJob.cancel()
                throw cancelled
            } catch (error: Exception) {
                printJob.fail(error.localizedMessage ?: "Error de impresión local.")
            } finally {
                activeJobs.remove(jobKey)
            }
        }
        activeJobs[jobKey] = work
        work.start()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        activeJobs.remove(printJob.id.toString())?.cancel()
        runCatching { printJob.cancel() }
    }

    override fun onDestroy() {
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun copyDocument(source: ParcelFileDescriptor, destination: File) {
        FileInputStream(source.fileDescriptor).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output, 64 * 1024) }
        }
        require(destination.isFile && destination.length() > 0) { "El documento PDF está vacío." }
        require(destination.length() <= 30L * 1024 * 1024) { "El PDF supera el límite de 30 MB." }
    }

    private class MarjhoDiscoverySession(private val owner: MarjhoPrintService) : PrinterDiscoverySession() {
        private val finder = BuscadorImpresorasRed(owner)
        private val publicadas = linkedMapOf<String, PrinterInfo>()
        private var analisisJob: Job? = null
        private var busquedaActiva = false

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            publicarGuardada()
            if (!busquedaActiva) {
                busquedaActiva = true
                finder.buscar(
                    { encontradas -> analizarYPublicar(encontradas) },
                    { /* El PrintService no muestra texto de estado; la app informa los detalles. */ }
                )
            }
        }

        override fun onStopPrinterDiscovery() {
            busquedaActiva = false
            analisisJob?.cancel()
            finder.detener()
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            val solicitadas = printerIds.map { it.localId }.toSet()
            val actuales = publicadas.filterKeys { it in solicitadas }.values
            if (actuales.isNotEmpty()) addPrinters(actuales.toList())
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            publicadas[printerId.localId]?.let { addPrinters(listOf(it)) }
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() {
            busquedaActiva = false
            analisisJob?.cancel()
            finder.detener()
            publicadas.keys.forEach(owner.destinationsByPrinterId::remove)
            publicadas.clear()
        }

        private fun publicarGuardada() {
            val destino = DestinoImpresion.desde(owner.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            if (destino != null) publicarDestinos(listOf(destino))
        }

        private fun analizarYPublicar(encontradas: List<ImpresoraEncontrada>) {
            analisisJob?.cancel()
            analisisJob = owner.serviceScope.launch {
                delay(350)
                val detectadas = withContext(Dispatchers.IO) {
                    encontradas.mapNotNull { impresora ->
                        runCatching { ProtocoloImpresion.analizar(impresora).comoDestino() }.getOrNull()
                    }
                }
                val guardada = DestinoImpresion.desde(owner.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                val todas = (listOfNotNull(guardada) + detectadas)
                    .filter { it.puedeImprimirPdfPorIpp || it.esEpsonL4150 }
                    .distinctBy { "${it.ip}|${it.nombre}" }
                withContext(Dispatchers.Main) {
                    if (busquedaActiva) publicarDestinos(todas)
                }
            }
        }

        private fun publicarDestinos(destinos: List<DestinoImpresion>) {
            val nuevas = linkedMapOf<String, PrinterInfo>()
            val porId = linkedMapOf<String, DestinoImpresion>()
            destinos.filter { it.puedeImprimirPdfPorIpp || it.esEpsonL4150 }.forEach { destino ->
                val idTexto = "marjho_${("${destino.ip}|${destino.nombre}").hashCode().toUInt().toString(16)}"
                val id = owner.generatePrinterId(idTexto)
                nuevas[idTexto] = crearInfo(id, destino)
                porId[idTexto] = destino
            }
            val quitadas = publicadas.keys - nuevas.keys
            if (quitadas.isNotEmpty()) removePrinters(quitadas.map(owner::generatePrinterId))
            quitadas.forEach(owner.destinationsByPrinterId::remove)
            val cambiadas = nuevas.filter { (id, info) -> publicadas[id] != info }.values.toList()
            if (cambiadas.isNotEmpty()) addPrinters(cambiadas)
            publicadas.clear()
            publicadas.putAll(nuevas)
            owner.destinationsByPrinterId.putAll(porId)
        }

        private fun crearInfo(id: PrinterId, destino: DestinoImpresion): PrinterInfo {
            val media = destino.medios.map { it.lowercase() }
            val builder = PrinterCapabilitiesInfo.Builder(id)
            val conocidas = listOf(
                "iso_a4" to PrintAttributes.MediaSize.ISO_A4,
                "iso_a3" to PrintAttributes.MediaSize.ISO_A3,
                "iso_a5" to PrintAttributes.MediaSize.ISO_A5,
                "iso_b5" to PrintAttributes.MediaSize.ISO_B5,
                "na_letter" to PrintAttributes.MediaSize.NA_LETTER,
                "na_legal" to PrintAttributes.MediaSize.NA_LEGAL,
                "na_tabloid" to PrintAttributes.MediaSize.NA_TABLOID
            )
            val compatibles = if (media.isEmpty()) listOf(PrintAttributes.MediaSize.ISO_A4)
            else conocidas.filter { (nombre, _) -> media.any { it.contains(nombre) || (nombre == "iso_a4" && it.contains("a4")) } }.map { it.second }
                .distinct().ifEmpty { listOf(PrintAttributes.MediaSize.ISO_A4) }
            val indicePredeterminado = compatibles.indexOfFirst { it == PrintAttributes.MediaSize.ISO_A4 }.let { if (it >= 0) it else 0 }
            compatibles.forEachIndexed { indice, size -> builder.addMediaSize(size, indice == indicePredeterminado) }

            val dpiDisponibles = destino.resolucionesDpi.filter { it in 72..2400 }.distinct().sorted()
                .ifEmpty { listOf(if (destino.esEpsonL4150) 360 else 300) }
            val dpiPredeterminado = dpiDisponibles.firstOrNull { it == 300 }
                ?: dpiDisponibles.firstOrNull { it == 360 }
                ?: dpiDisponibles.first()
            dpiDisponibles.forEach { dpi ->
                builder.addResolution(PrintAttributes.Resolution("marjho_${dpi}", "$dpi dpi", dpi, dpi), dpi == dpiPredeterminado)
            }
            builder
                .setMinMargins(if (destino.esEpsonL4150) PrintAttributes.Margins(118, 118, 118, 118) else PrintAttributes.Margins.NO_MARGINS)
                .setColorModes(
                    if (destino.color) PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_MONOCHROME,
                    if (destino.color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setDuplexModes(
                    if (destino.duplex) PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE else PrintAttributes.DUPLEX_MODE_NONE,
                    if (destino.duplex) PrintAttributes.DUPLEX_MODE_LONG_EDGE else PrintAttributes.DUPLEX_MODE_NONE
                )
            return PrinterInfo.Builder(id, destino.nombre.ifBlank { "Impresora de red (MARJHO)" }, PrinterInfo.STATUS_IDLE)
                .setDescription("${destino.ip} · ${destino.rutaDirecta}")
                .setCapabilities(builder.build())
                .build()
        }
    }

    companion object {
        private const val PREFS = "marjho_print_local"
    }
}
