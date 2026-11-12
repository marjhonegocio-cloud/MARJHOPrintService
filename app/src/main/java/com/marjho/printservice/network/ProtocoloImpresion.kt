package com.marjho.printservice.network

import android.content.SharedPreferences
import com.marjho.printservice.driver.ImpresorEscprDirecto
import de.gmuth.ipp.attributes.DocumentFormat
import de.gmuth.ipp.client.IppClient
import de.gmuth.ipp.client.IppConfig
import de.gmuth.ipp.client.IppPrinter
import de.gmuth.ipp.core.IppResolution
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.time.Duration

/** Resultado de contrastar el anuncio mDNS con las capacidades publicadas por IPP. */
data class DiagnosticoImpresora(
    val impresora: ImpresoraEncontrada,
    val modelo: String,
    val uriIpp: String?,
    val formatos: Set<String>,
    val medios: Set<String>,
    val resolucionesDpi: Set<Int>,
    val aceptaTrabajos: Boolean?,
    val color: Boolean,
    val duplex: Boolean,
    val puerto9100Abierto: Boolean,
    val detalleErrorIpp: String? = null
) {
    val soportaPdf: Boolean
        get() = formatos.any { it.equals("application/pdf", ignoreCase = true) } && uriIpp != null

    val esEpsonL4150: Boolean
        get() = "${impresora.nombre} $modelo".let {
            it.contains("L4150", ignoreCase = true) && it.contains("Epson", ignoreCase = true)
        }

    val rutaDirecta: String
        get() = when {
            soportaPdf -> "IPP: PDF"
            esEpsonL4150 && puerto9100Abierto -> "ESC/P-R: TCP 9100"
            puerto9100Abierto -> "TCP 9100 abierto; lenguaje sin identificar"
            uriIpp != null -> "IPP detectado; no anuncia PDF"
            else -> "No se confirmó un controlador directo compatible"
        }

    fun resumen(): String = buildString {
        append(impresora.nombre)
        if (modelo.isNotBlank()) append(" · ").append(modelo)
        append(" · ").append(impresora.ip).append('\n')
        append("Servicios: ").append(impresora.protocolo).append('\n')
        if (uriIpp != null) {
            append("Formato IPP: ").append(if (formatos.isEmpty()) "sin lista de formatos" else formatos.joinToString())
            append('\n')
            if (resolucionesDpi.isNotEmpty()) append("Resoluciones: ").append(resolucionesDpi.sorted().joinToString { "$it dpi" }).append('\n')
            append("Acepta trabajos: ").append(aceptaTrabajos?.let { if (it) "sí" else "no" } ?: "sin dato").append('\n')
        }
        append("Ruta directa: ").append(rutaDirecta)
        if (detalleErrorIpp != null) append("\nDetalle IPP: ").append(detalleErrorIpp)
    }

    fun comoDestino(): DestinoImpresion = DestinoImpresion(
        ip = impresora.ip,
        nombre = impresora.nombre,
        modelo = modelo,
        protocolo = impresora.protocolo,
        uriIpp = uriIpp,
        formatos = formatos,
        medios = medios,
        resolucionesDpi = resolucionesDpi,
        color = color,
        duplex = duplex,
        puerto9100Abierto = puerto9100Abierto,
        rutaDirecta = rutaDirecta
    )
}

/** Configuración persistida del destino elegido. No se deduce un lenguaje de impresión por el puerto. */
data class DestinoImpresion(
    val ip: String,
    val nombre: String,
    val modelo: String,
    val protocolo: String,
    val uriIpp: String?,
    val formatos: Set<String>,
    val medios: Set<String>,
    val resolucionesDpi: Set<Int>,
    val color: Boolean,
    val duplex: Boolean,
    val puerto9100Abierto: Boolean,
    val rutaDirecta: String,
    val forzarEscpr: Boolean = false
) {
    val puedeImprimirPdfPorIpp: Boolean
        get() = uriIpp != null && formatos.any { it.equals("application/pdf", ignoreCase = true) }

    val esEpsonL4150: Boolean
        get() = "$nombre $modelo".let {
            it.contains("L4150", ignoreCase = true) && it.contains("Epson", ignoreCase = true)
        }

    fun guardar(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_IP, ip)
            .putString(KEY_NAME, nombre)
            .putString(KEY_MODEL, modelo)
            .putString(KEY_PROTOCOL, protocolo)
            .putString(KEY_IPP_URI, uriIpp)
            .putStringSet(KEY_FORMATS, formatos.toSet())
            .putStringSet(KEY_MEDIA, medios.toSet())
            .putStringSet(KEY_RESOLUTIONS, resolucionesDpi.map { it.toString() }.toSet())
            .putBoolean(KEY_COLOR, color)
            .putBoolean(KEY_DUPLEX, duplex)
            .putBoolean(KEY_RAW_9100, puerto9100Abierto)
            .putString(KEY_ROUTE, rutaDirecta)
            .putBoolean(KEY_FORCE_ESCPR, forzarEscpr)
            .apply()
    }

    companion object {
        const val KEY_IP = "printer_ip"
        const val KEY_NAME = "printer_name"
        const val KEY_MODEL = "printer_model"
        const val KEY_PROTOCOL = "printer_protocol"
        const val KEY_IPP_URI = "printer_ipp_uri"
        const val KEY_FORMATS = "printer_formats"
        const val KEY_MEDIA = "printer_media"
        const val KEY_RESOLUTIONS = "printer_resolutions_dpi"
        const val KEY_COLOR = "printer_color_supported"
        const val KEY_DUPLEX = "printer_duplex_supported"
        const val KEY_RAW_9100 = "printer_raw_9100_open"
        const val KEY_ROUTE = "printer_route"
        const val KEY_FORCE_ESCPR = "printer_force_escpr"

        fun desde(prefs: SharedPreferences): DestinoImpresion? {
            val ip = prefs.getString(KEY_IP, null)?.takeIf(String::isNotBlank) ?: return null
            return DestinoImpresion(
                ip = ip,
                nombre = prefs.getString(KEY_NAME, null).orEmpty(),
                modelo = prefs.getString(KEY_MODEL, null).orEmpty(),
                protocolo = prefs.getString(KEY_PROTOCOL, null).orEmpty(),
                uriIpp = prefs.getString(KEY_IPP_URI, null),
                formatos = prefs.getStringSet(KEY_FORMATS, emptySet()).orEmpty().toSet(),
                medios = prefs.getStringSet(KEY_MEDIA, emptySet()).orEmpty().toSet(),
                resolucionesDpi = prefs.getStringSet(KEY_RESOLUTIONS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
                color = prefs.getBoolean(KEY_COLOR, true),
                duplex = prefs.getBoolean(KEY_DUPLEX, false),
                puerto9100Abierto = prefs.getBoolean(KEY_RAW_9100, false),
                rutaDirecta = prefs.getString(KEY_ROUTE, "").orEmpty(),
                forzarEscpr = prefs.getBoolean(KEY_FORCE_ESCPR, false)
            )
        }

        fun limpiar(prefs: SharedPreferences) {
            prefs.edit()
                .remove(KEY_NAME).remove(KEY_MODEL).remove(KEY_PROTOCOL)
                .remove(KEY_IPP_URI).remove(KEY_FORMATS).remove(KEY_MEDIA)
                .remove(KEY_RESOLUTIONS)
                .remove(KEY_COLOR).remove(KEY_DUPLEX).remove(KEY_RAW_9100).remove(KEY_ROUTE)
                .remove(KEY_FORCE_ESCPR)
                .apply()
        }
    }
}

object ProtocoloImpresion {
    private const val PDF_MIME = "application/pdf"

    fun analizar(impresora: ImpresoraEncontrada): DiagnosticoImpresora {
        var modelo = impresora.modeloAnunciado?.takeIf { it.isNotBlank() } ?: impresora.nombre
        var formatos = emptySet<String>()
        var medios = emptySet<String>()
        var resoluciones = emptySet<Int>()
        var aceptaTrabajos: Boolean? = null
        var color = true
        var duplex = false
        var errorIpp: String? = null
        val uri = impresora.ippUri

        if (uri != null) {
            try {
                val ipp = consultarIpp(uri)
                modelo = runCatching { ipp.makeAndModel.toString() }
                    .getOrDefault("").ifBlank { modelo }
                formatos = runCatching { ipp.documentFormatSupported.toSet() }.getOrDefault(emptySet())
                medios = runCatching { ipp.mediaSupported.toSet() }.getOrDefault(emptySet())
                resoluciones = runCatching {
                    ipp.attributes.getValuesOrNull<List<IppResolution>>("printer-resolution-supported")
                        .orEmpty()
                        .map { if (it.unit == IppResolution.Unit.DPI.code) it.x else (it.x * 2.54f).toInt() }
                        .filter { it in 72..2400 }
                        .toSet()
                }.getOrDefault(emptySet())
                aceptaTrabajos = runCatching { ipp.isAcceptingJobs }.getOrNull()
                color = runCatching { ipp.colorSupported }.getOrDefault(true)
                duplex = runCatching { ipp.sidesSupported.any { it.startsWith("two-sided", ignoreCase = true) } }
                    .getOrDefault(false)
            } catch (error: Exception) {
                errorIpp = error.localizedMessage ?: error.javaClass.simpleName
            }
        }

        return DiagnosticoImpresora(
            impresora = impresora,
            modelo = modelo,
            uriIpp = uri,
            formatos = formatos,
            medios = medios,
            resolucionesDpi = resoluciones,
            aceptaTrabajos = aceptaTrabajos,
            color = color,
            duplex = duplex,
            puerto9100Abierto = puertoAbierto(impresora.ip, 9100),
            detalleErrorIpp = errorIpp
        )
    }

    fun analizarIp(ip: String): DiagnosticoImpresora {
        val manual = ImpresoraEncontrada(
            nombre = "Impresora $ip",
            protocolo = "dirección manual",
            ip = ip,
            puerto = 631,
            ippUri = "ipp://$ip:631/ipp/print"
        )
        return analizar(manual)
    }

    fun imprimirPdf(
        destino: DestinoImpresion,
        archivo: File,
        progreso: (ImpresorEscprDirecto.Avance) -> Unit = {}
    ): String {
        require(destinoEsLocal(destino)) {
            "Para imprimir sin Internet, configurá la IP local de la impresora (por ejemplo, 192.168.1.x)."
        }
        require(archivo.isFile && archivo.length() > 0) { "El PDF no existe o está vacío." }

        if (destino.forzarEscpr) return imprimirEscpr(destino, archivo, progreso = progreso)

        val uri = destino.uriIpp
        if (uri != null) {
            val ipp = consultarIpp(uri)
            val formatosActuales = ipp.documentFormatSupported
            if (formatosActuales.any { it.equals(PDF_MIME, ignoreCase = true) }) {
                check(runCatching { ipp.isAcceptingJobs }.getOrDefault(true)) {
                    "La impresora IPP no está aceptando trabajos en este momento."
                }
                ipp.printJob(archivo, DocumentFormat.PDF)
                return "El servidor IPP aceptó ${archivo.name} en ${destino.nombre}. Esto confirma la recepción del trabajo, no la salida física."
            }
        }

        if (destino.esEpsonL4150) {
            return imprimirEscpr(destino, archivo, progreso = progreso)
        }

        throw IllegalStateException(
            "No se envió el trabajo: ${destino.nombre} no anunció compatibilidad IPP con PDF " +
                "y no se identificó un controlador específico para su modelo. Usá el diálogo de impresión de Android o instalá el complemento del fabricante."
        )
    }

    /** Envía el PDF como raster ESC/P-R al perfil Epson L4150 sin pasar por el selector Android. */
    fun imprimirEscpr(
        destino: DestinoImpresion,
        archivo: File,
        comprobarCancelacion: () -> Unit = {},
        progreso: (ImpresorEscprDirecto.Avance) -> Unit = {}
    ): String {
        require(esIpPrivadaLocal(destino.ip)) {
            "ESC/P-R directo solo puede enviar a una impresora de la red local. Revisá la IP guardada."
        }
        require(destino.esEpsonL4150) {
            "ESC/P-R directo solo está habilitado para el perfil Epson L4150 confirmado."
        }
        check(puertoAbierto(destino.ip, 9100, 2500)) {
            "La Epson L4150 no responde en TCP 9100 en ${destino.ip}."
        }
        return ImpresorEscprDirecto.imprimirHojas(
            ip = destino.ip,
            pdf = archivo,
            progreso = progreso,
            comprobarCancelacion = comprobarCancelacion
        )
    }

    fun puertoAbierto(host: String, port: Int, timeoutMs: Int = 700): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    private fun consultarIpp(uriTexto: String): IppPrinter {
        var ultimoError: Exception? = null
        for (version in listOf("2.0", "1.1")) {
            try { return crearCliente(uriTexto, version) }
            catch (error: Exception) { ultimoError = error }
        }
        throw ultimoError ?: IllegalStateException("No se pudo negociar IPP.")
    }

    /** Limits document data to private LAN printers and mDNS .local IPP hosts. */
    private fun destinoEsLocal(destino: DestinoImpresion): Boolean {
        if (!esIpPrivadaLocal(destino.ip)) return false
        val uri = destino.uriIpp ?: return true
        val host = runCatching { URI.create(uri).host }.getOrNull()?.trimEnd('.')?.lowercase() ?: return false
        return host == destino.ip || host.endsWith(".local")
    }

    private fun esIpPrivadaLocal(ip: String): Boolean {
        val octetos = ip.split('.').mapNotNull(String::toIntOrNull)
        if (octetos.size != 4 || octetos.any { it !in 0..255 }) return false
        val (a, b) = octetos
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    private fun crearCliente(uriTexto: String, version: String): IppPrinter {
        val uri = URI.create(uriTexto)
        val config = IppConfig(
            userName = "MARJHO Print Service",
            ippVersion = version,
            timeout = Duration.ofSeconds(5),
            userAgent = "MARJHO Print Service/1.1"
        )
        val cliente = IppClient(config).apply {
            defaultPrinterUri = uri
            saveMessages = false
            saveDocuments = false
        }
        return IppPrinter(uri, ippClient = cliente)
    }
}
