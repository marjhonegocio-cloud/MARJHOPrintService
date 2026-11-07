package com.marjho.printservice

import android.content.Context
import android.graphics.Color
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.os.ParcelFileDescriptor
import com.marjho.printservice.driver.ImpresorEscprDirecto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Android PrintService: expone la Epson L4150 como impresora seleccionable por el sistema. */
class MarjhoPrintService : PrintService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = MarjhoDiscoverySession(this)

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobKey = printJob.id.toString()
        val work = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val ip = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MainActivity.KEY_IP, "").orEmpty()
                require(validIp(ip)) { "Configurá la IP de la Epson desde MARJHO Print Service." }
                printJob.start()
                val pdf = File(cacheDir, "marjho_print_${System.currentTimeMillis()}.pdf")
                try {
                    val documentData = printJob.document.data
                        ?: throw IllegalStateException(
                            "Android no proporcionó el archivo del documento para imprimir."
                        )

                    copyDocument(documentData, pdf)
                    val diagnostic = Socket().use { socket ->
                        socket.connect(InetSocketAddress(ip, 9100), 3500)
                        ""
                    }
                    diagnostic.length // mantiene explícito el chequeo de conectividad previo.
                    val coroutineJob = currentCoroutineContext()[Job]
                    ImpresorEscprDirecto.imprimirHojas(
                        ip = ip,
                        pdf = pdf,
                        comprobarCancelacion = {
                            if (coroutineJob?.isActive != true) throw CancellationException("Trabajo cancelado por el usuario.")
                        }
                    )
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

    private fun validIp(ip: String): Boolean {
        val parts = ip.split('.')

        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            number != null && number in 0..255
        }
    }

    private class MarjhoDiscoverySession(private val owner: MarjhoPrintService) : PrinterDiscoverySession() {
        private val printerId = owner.generatePrinterId(PRINTER_LOCAL_ID)

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            publishPrinter()
        }

        override fun onStopPrinterDiscovery() = Unit

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            if (printerIds.contains(printerId)) publishPrinter()
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            if (printerId == this.printerId) publishPrinter()
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit

        private fun publishPrinter() {
            val ip = owner.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_IP, "").orEmpty()
            if (!ip.contains('.')) return
            val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                .addResolution(PrintAttributes.Resolution("marjho_360", "360 dpi", 360, 360), true)
                .setMinMargins(PrintAttributes.Margins(118, 118, 118, 118))
                .setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_COLOR)
                .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE)
                .build()
            val info = PrinterInfo.Builder(printerId, "EPSON L4150 Series (MARJHO)", PrinterInfo.STATUS_IDLE)
                .setDescription("Red local · $ip · ESC/P-R TCP 9100")
                .setCapabilities(capabilities)
                .build()
            addPrinters(listOf(info))
        }
    }

    companion object {
        private const val PREFS = "marjho_print_local"
        private const val PRINTER_LOCAL_ID = "epson_l4150_local"
    }
}
