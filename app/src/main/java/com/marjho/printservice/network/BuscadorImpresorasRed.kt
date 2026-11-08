package com.marjho.printservice.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

data class ImpresoraEncontrada(
    val nombre: String,
    val protocolo: String,
    val ip: String,
    val puerto: Int,
    val ippUri: String? = null,
    val modeloAnunciado: String? = null,
    val tiposServicio: Set<String> = setOf(protocolo)
)

class BuscadorImpresorasRed(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val tipos = listOf("_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.")
    private val encontrados = LinkedHashMap<String, ImpresoraEncontrada>()
    private val timeoutBusqueda = Runnable { avanzar() }
    private val duracionPorTipoMs = 1_500L
    private var indice = 0
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var activo = false
    private var results: ((List<ImpresoraEncontrada>) -> Unit)? = null
    private var status: ((String) -> Unit)? = null

    fun buscar(onResults: (List<ImpresoraEncontrada>) -> Unit, onStatus: (String) -> Unit) {
        detener()
        encontrados.clear()
        indice = 0
        results = onResults
        status = onStatus
        try {
            lock = wifi.createMulticastLock("MARJHO_mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }
            siguiente()
        } catch (e: Exception) {
            informar("No se pudo iniciar la búsqueda: ${e.localizedMessage}")
            detener()
        }
    }

    private fun siguiente() {
        if (indice >= tipos.size) {
            informar("Búsqueda terminada. Encontradas: ${encontrados.size}")
            publicar()
            detener()
            return
        }
        val tipo = tipos[indice]
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                activo = true
                informar("Buscando $serviceType…")
                main.removeCallbacks(timeoutBusqueda)
                main.postDelayed(timeoutBusqueda, duracionPorTipoMs)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType?.contains(tipo.removeSuffix(".")) == true) resolver(service, tipo)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val keys = encontrados.filterValues { it.nombre == service.serviceName }.keys
                keys.forEach(encontrados::remove)
                if (keys.isNotEmpty()) publicar()
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                informar("No se pudo buscar $serviceType (código $errorCode)")
                avanzar()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                informar("No se pudo detener la búsqueda de $serviceType (código $errorCode).")
            }
        }
        discovery = l
        try {
            nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            informar("Error en $tipo: ${e.localizedMessage}")
            avanzar()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolver(info: NsdServiceInfo, tipo: String) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    informar("No se pudo resolver ${serviceInfo.serviceName}")
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val address: InetAddress = serviceInfo.host ?: return
                    val atributos = runCatching {
                        serviceInfo.attributes.mapValues { (_, value) -> String(value, Charsets.UTF_8).trim() }
                    }.getOrDefault(emptyMap())
                    val servicio = tipo.removeSuffix(".")
                    val protocolo = when (servicio) {
                        "_ipp._tcp" -> "IPP"
                        "_ipps._tcp" -> "IPPS"
                        "_printer._tcp" -> "LPR"
                        "_pdl-datastream._tcp" -> "RAW/9100"
                        else -> servicio
                    }
                    val ruta = atributos["rp"].orEmpty().trimStart('/').ifBlank { "ipp/print" }
                    val hostUri = address.hostAddress?.substringBefore('%') ?: return
                    val hostUriSeguro = if (address.address.size == 16) "[$hostUri]" else hostUri
                    val ippUri = when (servicio) {
                        "_ipp._tcp" -> "ipp://$hostUriSeguro:${serviceInfo.port}/$ruta"
                        "_ipps._tcp" -> "ipps://$hostUriSeguro:${serviceInfo.port}/$ruta"
                        else -> null
                    }
                    val item = ImpresoraEncontrada(
                        serviceInfo.serviceName,
                        protocolo,
                        address.hostAddress ?: return,
                        serviceInfo.port,
                        ippUri,
                        atributos["ty"] ?: atributos["product"],
                        setOf(protocolo)
                    )
                    main.post {
                        val key = item.nombre.lowercase() + "|" + item.ip
                        val anterior = encontrados[key]
                        encontrados[key] = if (anterior == null) item else anterior.copy(
                            protocolo = (anterior.tiposServicio + item.tiposServicio).joinToString(" + "),
                            ippUri = anterior.ippUri ?: item.ippUri,
                            modeloAnunciado = anterior.modeloAnunciado ?: item.modeloAnunciado,
                            tiposServicio = anterior.tiposServicio + item.tiposServicio
                        )
                        publicar()
                    }
                }
            })
        } catch (e: Exception) {
            informar("Error resolviendo servicio: ${e.localizedMessage}")
        }
    }

    private fun avanzar() {
        main.post {
            pararDiscovery()
            indice++
            siguiente()
        }
    }

    private fun pararDiscovery() {
        main.removeCallbacks(timeoutBusqueda)
        val current = discovery
        discovery = null
        if (current != null) runCatching { nsd.stopServiceDiscovery(current) }
    }

    fun detener() {
        pararDiscovery()
        activo = false
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    private fun publicar() {
        val copy = encontrados.values.toList()
        main.post { results?.invoke(copy) }
    }
    private fun informar(message: String) = main.post { status?.invoke(message) }
}
