package com.marjho.printservice

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.graphics.pdf.PdfRenderer
import android.util.Log
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
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Android PrintService: descubre impresoras de red y usa IPP o el perfil ESC/P-R validado. */
class MarjhoPrintService : PrintService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<PrintJobId, Job>()
    private val destinationsByPrinterId = ConcurrentHashMap<String, DestinoImpresion>()
    private var recoveryJob: Job? = null

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = MarjhoDiscoverySession(this)

    override fun onConnected() {
        super.onConnected()
        recoverQueuedPrintJobs()
        // Some MIUI builds disconnect this service without delivering the
        // queued-job callback. Check Android's active-job list while connected
        // so a job created after the print preview is still picked up.
        recoveryJob?.cancel()
        recoveryJob = serviceScope.launch {
            while (isActive) {
                delay(750)
                withContext(Dispatchers.Main.immediate) { recoverQueuedPrintJobs() }
            }
        }
    }

    private fun recoverQueuedPrintJobs() {
        val jobs = runCatching { getActivePrintJobs() }
            .onFailure { Log.e(TAG, "No se pudieron recuperar los trabajos activos", it) }
            .getOrDefault(emptyList())
        val queuedJobs = jobs.filter { runCatching { it.isQueued }.getOrDefault(false) }
        if (queuedJobs.isNotEmpty()) Log.i(TAG, "Trabajos en cola recuperados=${queuedJobs.size}")
        queuedJobs.forEach(::onPrintJobQueued)

        // A process restart loses the in-memory worker. Do not leave such a
        // started job stuck forever or resend it automatically: some bytes may
        // already have reached the printer before the interruption.
        jobs.filter { !activeJobs.containsKey(it.id) && runCatching { it.isStarted }.getOrDefault(false) }
            .forEach { job ->
                runCatching {
                    job.fail("El envío se interrumpió. Revisá la impresora antes de volver a imprimir.")
                }.onFailure { Log.e(TAG, "No se pudo cerrar un trabajo interrumpido", it) }
            }
    }

    override fun onDisconnected() {
        recoveryJob?.cancel()
        recoveryJob = null
        Log.i(TAG, "Android desconectó el servicio de impresión")
        super.onDisconnected()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val printJobId = printJob.id
        val jobKey = printJobId.hashCode().toUInt().toString(16)
        if (activeJobs.containsKey(printJobId)) {
            Log.i(TAG, "Se ignora el trabajo duplicado id=$jobKey")
            return
        }
        val printerLocalId = runCatching { printJob.info.printerId?.localId }
            .onFailure { Log.e(TAG, "No se pudo leer la impresora del trabajo $jobKey", it) }
            .getOrNull()
        val info = printJob.info
        val pageCountAndroid = runCatching { printJob.document.info.pageCount }
            .onFailure { Log.e(TAG, "No se pudo leer la información del documento $jobKey", it) }
            .getOrDefault(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
        val requestedRanges = info.pages.orEmpty().joinToString { "${it.start}-${it.end}" }
        Log.i(TAG, "Trabajo en cola id=$jobKey printer=$printerLocalId queued=${runCatching { printJob.isQueued }.getOrNull()} " +
            "androidPages=$pageCountAndroid requested=$requestedRanges label=${info.label}")
        // PrintJob state queries and transitions are main-thread-only Android
        // APIs; only document copying and network transfer run on the IO scope.
        val started = runCatching { printJob.start() }.getOrElse { error ->
            Log.e(TAG, "No se pudo iniciar el trabajo $jobKey", error)
            runCatching { printJob.fail(error.localizedMessage ?: "No se pudo iniciar el trabajo de impresión.") }
            return
        }
        Log.i(TAG, "Inicio del trabajo id=$jobKey resultado=$started")
        if (!started) {
            runCatching {
                if (printJob.isQueued) printJob.fail("Android no pudo iniciar el trabajo de impresión.")
            }
            return
        }
        runCatching {
            printJob.setProgress(0f)
            printJob.setStatus("Preparando el PDF en el teléfono…")
        }
        // PrintJob and PrintDocument APIs are main-thread-only. Acquire the
        // spooled file descriptor here, before moving the file copy to IO.
        val documentData = runCatching { printJob.document.data }.getOrElse { error ->
            Log.e(TAG, "No se pudo obtener el documento $jobKey", error)
            runCatching { printJob.fail(error.localizedMessage ?: "Android no entregó el documento.") }
            return
        } ?: run {
            Log.e(TAG, "Android entregó un documento vacío id=$jobKey")
            runCatching { printJob.fail("Android no proporcionó el archivo del documento para imprimir.") }
            return
        }

        val work = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val destino = printerLocalId?.let(destinationsByPrinterId::get)
                    ?: DestinoImpresion.desde(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                    ?: throw IllegalStateException("Elegí una impresora compatible desde MARJHO Print Service.")
                Log.i(TAG, "Procesando id=$jobKey destino=${destino.ip} tipo=${destino.rutaDirecta}")
                require(destino.puedeImprimirPdfPorIpp || destino.esEpsonL4150) {
                    "No se confirmó un controlador compatible para la impresora seleccionada."
                }
                val pdf = File(cacheDir, "marjho_print_${System.currentTimeMillis()}.pdf")
                try {
                    copyDocument(documentData, pdf)
                    val pageCountPdf = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { it.pageCount }
                        }
                    }
                    Log.i(TAG, "Documento local listo id=$jobKey bytes=${pdf.length()} androidPages=$pageCountAndroid " +
                        "pdfPages=$pageCountPdf requested=$requestedRanges destino=${destino.ip}")
                    if (pageCountAndroid > 0 && pageCountAndroid != pageCountPdf) {
                        withContext(Dispatchers.Main.immediate) {
                            if (printJob.isStarted) {
                                printJob.setStatus("Android indicó $pageCountAndroid páginas; el PDF recibido contiene $pageCountPdf.")
                            }
                        }
                    }
                    val coroutineJob = currentCoroutineContext()[Job]
                    val mainHandler = Handler(Looper.getMainLooper())
                    var ultimaHojaNotificada = 0
                    var ultimoProgresoNotificado = -2
                    val actualizarProgreso: (com.marjho.printservice.driver.ImpresorEscprDirecto.Avance) -> Unit = { avance ->
                        val porcentaje = avance.porcentajeTotal.coerceIn(0, 100)
                        if (avance.hojaActual != ultimaHojaNotificada || porcentaje >= ultimoProgresoNotificado + 2 || porcentaje == 100) {
                            ultimaHojaNotificada = avance.hojaActual
                            ultimoProgresoNotificado = porcentaje
                            mainHandler.post {
                                runCatching {
                                    if (printJob.isStarted) {
                                        printJob.setProgress(porcentaje / 100f)
                                        printJob.setStatus("Enviando hoja ${avance.hojaActual} de ${avance.totalHojas} a la Epson…")
                                    }
                                }
                            }
                        }
                    }
                    if (destino.esEpsonL4150 && (destino.forzarEscpr || !destino.puedeImprimirPdfPorIpp)) {
                        Log.i(TAG, "Enviando id=$jobKey por ESC/P-R")
                        val resumen = ProtocoloImpresion.imprimirEscpr(
                            destino,
                            pdf,
                            comprobarCancelacion = {
                                if (coroutineJob?.isActive != true) throw CancellationException("Trabajo cancelado por el usuario.")
                            },
                            progreso = actualizarProgreso
                        )
                        Log.i(TAG, "Resultado ESC/P-R id=$jobKey: $resumen")
                    } else {
                        if (coroutineJob?.isActive != true) throw CancellationException("Trabajo cancelado por el usuario.")
                        Log.i(TAG, "Enviando id=$jobKey por IPP")
                        ProtocoloImpresion.imprimirPdf(destino, pdf)
                    }
                    Log.i(TAG, "Transmisión terminada id=$jobKey")
                    withContext(Dispatchers.Main.immediate) {
                        if (printJob.isStarted) printJob.complete()
                    }
                    Log.i(TAG, "Trabajo completado id=$jobKey")
                } finally {
                    pdf.delete()
                }
            } catch (cancelled: CancellationException) {
                Log.i(TAG, "Trabajo cancelado id=$jobKey")
                withContext(Dispatchers.Main.immediate) {
                    if (printJob.isQueued || printJob.isStarted || printJob.isBlocked) printJob.cancel()
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Falló el trabajo id=$jobKey: ${error.localizedMessage}", error)
                withContext(Dispatchers.Main.immediate) {
                    if (printJob.isQueued || printJob.isStarted || printJob.isBlocked) {
                        printJob.fail(error.localizedMessage ?: "Error de impresión local.")
                    }
                }
            } finally {
                runCatching { documentData.close() }
                activeJobs.remove(printJobId)
            }
        }
        activeJobs[printJobId] = work
        work.start()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val id = printJob.id.hashCode().toUInt().toString(16)
        Log.i(TAG, "Android pidió cancelar id=$id started=${runCatching { printJob.isStarted }.getOrNull()} worker=${activeJobs.containsKey(printJob.id)}")
        activeJobs.remove(printJob.id)?.cancel()
        runCatching { printJob.cancel() }
    }

    override fun onDestroy() {
        Log.w(TAG, "Servicio destruido con ${activeJobs.size} trabajador(es) activo(s)")
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun copyDocument(source: ParcelFileDescriptor, destination: File) {
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
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
        private const val TAG = "MarjhoPrintService"
        private const val PREFS = "marjho_print_local"
    }
}

