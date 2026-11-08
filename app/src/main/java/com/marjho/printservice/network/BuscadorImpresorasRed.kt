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
    val puerto: Int
)

class BuscadorImpresorasRed(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val tipos = listOf("_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.")
    private val encontrados = LinkedHashMap<String, ImpresoraEncontrada>()
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
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType?.contains(tipo.removeSuffix(".")) == true) resolver(service, tipo)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val key = service.serviceName
                if (encontrados.remove(key) != null) publicar()
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                informar("No se pudo buscar $serviceType (código $errorCode)")
                avanzar()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { indice++; siguiente() }
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
                    val item = ImpresoraEncontrada(
                        serviceInfo.serviceName,
                        tipo.removeSuffix("."),
                        address.hostAddress ?: return,
                        serviceInfo.port
                    )
                    main.post {
                        encontrados[item.nombre + item.ip] = item
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
