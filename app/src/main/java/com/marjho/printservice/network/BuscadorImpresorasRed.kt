package com.marjho.printservice.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.util.concurrent.Executor

data class ImpresoraEncontrada(
    val nombre: String,
    val protocolo: String,
    val ip: String,
    val puerto: Int,
    val ippUri: String? = null,
    val modeloAnunciado: String? = null,
    val tiposServicio: Set<String> = setOf(protocolo)
)

/** DNS-SD/mDNS discovery with bounded sessions and stale callback protection. */
class BuscadorImpresorasRed(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val tipos = listOf("_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.")
    private val encontrados = LinkedHashMap<String, ImpresoraEncontrada>()
    private var tiempoLimite: Runnable? = null
    private val duracionPorTipoMs = 2_400L
    private var indice = 0
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var generation = 0L
    private val serviceCallbacks = linkedMapOf<String, NsdManager.ServiceInfoCallback>()
    private val callbackExecutor = Executor { command -> main.post(command) }
    private var results: ((List<ImpresoraEncontrada>) -> Unit)? = null
    private var status: ((String) -> Unit)? = null

    fun buscar(onResults: (List<ImpresoraEncontrada>) -> Unit, onStatus: (String) -> Unit) {
        detener()
        encontrados.clear()
        indice = 0
        results = onResults
        status = onStatus
        val session = generation
        try {
            lock = wifi.createMulticastLock("MARJHO_mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }
            siguiente(session)
        } catch (error: Exception) {
            informar("No se pudo iniciar la búsqueda: ${error.localizedMessage}", session)
            publicar(session)
            cerrarRecursos()
        }
    }

    private fun siguiente(session: Long) {
        if (session != generation) return
        if (indice >= tipos.size) {
            informar("Búsqueda terminada. Encontradas: ${encontrados.size}", session)
            publicar(session)
            cerrarRecursos()
            return
        }
        val tipo = tipos[indice]
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (session != generation) return
                informar("Buscando $serviceType…", session)
                tiempoLimite?.let(main::removeCallbacks)
                tiempoLimite = Runnable { avanzar(session) }.also { main.postDelayed(it, duracionPorTipoMs) }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (session == generation && service.serviceType?.contains(tipo.removeSuffix(".")) == true) {
                    resolver(service, tipo, session)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post {
                    if (session != generation) return@post
                    val keys = encontrados.filterValues { it.nombre == service.serviceName }.keys
                    keys.forEach(encontrados::remove)
                    if (keys.isNotEmpty()) publicar(session)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (session != generation) return
                informar("No se pudo buscar $serviceType (código $errorCode)", session)
                avanzar(session)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                informar("No se pudo detener la búsqueda de $serviceType (código $errorCode).", session)
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            informar("Error en $tipo: ${error.localizedMessage}", session)
            avanzar(session)
        }
    }

    private fun resolver(info: NsdServiceInfo, tipo: String, session: Long) {
        if (session != generation) return
        if (Build.VERSION.SDK_INT >= 34) {
            main.post {
                if (session != generation) return@post
                val key = "${info.serviceName}|$tipo|${info.port}"
                if (key in serviceCallbacks) return@post
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        serviceCallbacks.remove(key)
                        informar("No se pudieron actualizar los datos de ${info.serviceName} (código $errorCode).", session)
                        resolverLegacy(info, tipo, session)
                    }

                    override fun onServiceInfoCallbackUnregistered() {
                        serviceCallbacks.remove(key)
                    }

                    override fun onServiceLost() {
                        serviceCallbacks.remove(key)
                        val keys = encontrados.filterValues { it.nombre == info.serviceName }.keys
                        keys.forEach(encontrados::remove)
                        if (keys.isNotEmpty()) publicar(session)
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        registrarResultado(serviceInfo, tipo, session)
                    }
                }
                serviceCallbacks[key] = callback
                try {
                    nsd.registerServiceInfoCallback(info, callbackExecutor, callback)
                } catch (error: Exception) {
                    serviceCallbacks.remove(key)
                    informar("No se pudieron actualizar los datos de ${info.serviceName}: ${error.localizedMessage}", session)
                    resolverLegacy(info, tipo, session)
                }
            }
            return
        }
        resolverLegacy(info, tipo, session)
    }

    @Suppress("DEPRECATION")
    private fun resolverLegacy(info: NsdServiceInfo, tipo: String, session: Long) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    informar("No se pudo resolver ${serviceInfo.serviceName} (código $errorCode).", session)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (session == generation) main.post { registrarResultado(serviceInfo, tipo, session) }
                }
            })
        } catch (error: Exception) {
            informar("Error resolviendo servicio: ${error.localizedMessage}", session)
        }
    }

    private fun registrarResultado(serviceInfo: NsdServiceInfo, tipo: String, session: Long) {
        if (session != generation) return
        val address = hostAddresses(serviceInfo).firstOrNull() ?: return
        val attributes = runCatching {
            serviceInfo.attributes.mapValues { (_, value) -> String(value, Charsets.UTF_8).trim() }
        }.getOrDefault(emptyMap())
        val service = tipo.removeSuffix(".")
        val protocol = when (service) {
            "_ipp._tcp" -> "IPP"
            "_ipps._tcp" -> "IPPS"
            "_printer._tcp" -> "LPR"
            "_pdl-datastream._tcp" -> "RAW/9100"
            else -> service
        }
        val route = attributes["rp"].orEmpty().trimStart('/').ifBlank { "ipp/print" }
        val host = address.hostAddress?.substringBefore('%') ?: return
        val hostForUri = if (address.address.size == 16) "[$host]" else host
        val ippUri = when (service) {
            "_ipp._tcp" -> "ipp://$hostForUri:${serviceInfo.port}/$route"
            "_ipps._tcp" -> "ipps://$hostForUri:${serviceInfo.port}/$route"
            else -> null
        }
        val item = ImpresoraEncontrada(
            nombre = serviceInfo.serviceName,
            protocolo = protocol,
            ip = address.hostAddress ?: return,
            puerto = serviceInfo.port,
            ippUri = ippUri,
            modeloAnunciado = attributes["ty"] ?: attributes["product"],
            tiposServicio = setOf(service)
        )
        val key = item.nombre.lowercase() + "|" + item.ip
        val previous = encontrados[key]
        encontrados[key] = if (previous == null) item else previous.copy(
            protocolo = (previous.tiposServicio + item.tiposServicio).joinToString(" + "),
            ippUri = previous.ippUri ?: item.ippUri,
            modeloAnunciado = previous.modeloAnunciado ?: item.modeloAnunciado,
            tiposServicio = previous.tiposServicio + item.tiposServicio
        )
        publicar(session)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun hostAddresses(info: NsdServiceInfo): List<InetAddress> {
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { info.hostAddresses }.getOrDefault(emptyList())
        } else emptyList()
        return (addresses + listOfNotNull(info.host)).distinct().sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    private fun avanzar(session: Long) {
        main.post {
            if (session != generation) return@post
            pararDiscovery()
            indice++
            siguiente(session)
        }
    }

    private fun pararDiscovery() {
        tiempoLimite?.let(main::removeCallbacks)
        tiempoLimite = null
        val current = discovery
        discovery = null
        if (current != null) runCatching { nsd.stopServiceDiscovery(current) }
    }

    /** Cancels the search and invalidates callbacks already queued by Android's NSD service. */
    fun detener() {
        generation++
        pararDiscovery()
        cerrarRecursos()
        results = null
        status = null
    }

    private fun cerrarRecursos() {
        if (Build.VERSION.SDK_INT >= 34) {
            serviceCallbacks.values.toList().forEach { callback ->
                runCatching { nsd.unregisterServiceInfoCallback(callback) }
            }
            serviceCallbacks.clear()
        }
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    private fun publicar(session: Long) {
        val copy = encontrados.values.toList()
        val callback = results ?: return
        main.post { if (session == generation) callback(copy) }
    }

    private fun informar(message: String, session: Long) {
        val callback = status ?: return
        main.post { if (session == generation) callback(message) }
    }
}
