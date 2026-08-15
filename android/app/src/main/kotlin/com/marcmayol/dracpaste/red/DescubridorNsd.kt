package com.marcmayol.dracpaste.red

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.marcmayol.dracpaste.protocolo.Protocolo
import java.net.InetAddress

/**
 * Descubrimiento del PC por mDNS (`docs/protocol.md` §9).
 *
 * Es lo que permite que el usuario nunca tenga que escribir una IP, y que la app siga
 * encontrando su PC cuando el router le cambie la dirección.
 *
 * Solo interesa **un** anuncio: el del PC activo, identificado por su `device_id` en los
 * registros TXT. Cualquier otro se descarta sin resolver siquiera, incluido el de alguien
 * que publique el mismo servicio a propósito para hacerse pasar por él. Aunque colara, el
 * handshake lo pararía después: sin la clave de par no hay sesión.
 */
class DescubridorNsd(contexto: Context) {

    private val nsd = contexto.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var escucha: NsdManager.DiscoveryListener? = null
    private var deviceIdBuscado: String? = null
    private var alEncontrar: ((String, Int) -> Unit)? = null

    /** Si el descubrimiento está activo ahora mismo. */
    @Volatile
    var buscando: Boolean = false
        private set

    /**
     * Empieza a buscar al PC indicado.
     *
     * @param alEncontrar se llama con la IP y el puerto cuando aparece. Puede llamarse
     *   varias veces: cada anuncio nuevo del mismo PC lo dispara otra vez.
     */
    fun buscar(deviceId: String, alEncontrar: (String, Int) -> Unit) {
        detener()
        deviceIdBuscado = deviceId
        this.alEncontrar = alEncontrar

        val escuchaNueva = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(tipo: String) {
                buscando = true
            }

            override fun onServiceFound(servicio: NsdServiceInfo) {
                // El nombre de instancia no basta para saber si es el PC que buscamos:
                // hay que resolver para leer los TXT. Se resuelve todo lo que aparezca
                // con este tipo de servicio, que en una red doméstica son uno o dos.
                resolver(servicio)
            }

            override fun onServiceLost(servicio: NsdServiceInfo) {
                // No se hace nada: quien decide que la conexión ha muerto es el PING, no
                // la desaparición del anuncio. Un mDNS que se pierde un instante por una
                // interferencia no debe cortar una sesión que funciona.
            }

            override fun onDiscoveryStopped(tipo: String) {
                buscando = false
            }

            override fun onStartDiscoveryFailed(tipo: String, codigo: Int) {
                buscando = false
                Log.w(TAG, "No se pudo iniciar el descubrimiento (código $codigo)")
            }

            override fun onStopDiscoveryFailed(tipo: String, codigo: Int) {
                buscando = false
            }
        }

        escucha = escuchaNueva
        try {
            nsd.discoverServices(Protocolo.SERVICIO_MDNS, NsdManager.PROTOCOL_DNS_SD, escuchaNueva)
        } catch (e: IllegalArgumentException) {
            escucha = null
            buscando = false
            Log.w(TAG, "El descubrimiento mDNS no está disponible", e)
        }
    }

    private fun resolver(servicio: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolverModerno(servicio)
        } else {
            resolverAntiguo(servicio)
        }
    }

    /**
     * En Android 14+ `resolveService` está obsoleto en favor de
     * `registerServiceInfoCallback`, que además avisa de los cambios de dirección sin
     * volver a resolver.
     */
    @Suppress("DEPRECATION")
    private fun resolverModerno(servicio: NsdServiceInfo) {
        try {
            nsd.registerServiceInfoCallback(
                servicio,
                { it.run() },
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val direccion = info.hostAddresses.firstOrNull() ?: return
                        comprobarYAvisar(info, direccion)
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackRegistrationFailed(codigo: Int) {
                        Log.w(TAG, "No se pudo seguir el servicio (código $codigo)")
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                },
            )
        } catch (e: Exception) {
            resolverAntiguo(servicio)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolverAntiguo(servicio: NsdServiceInfo) {
        nsd.resolveService(
            servicio,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val direccion = info.host ?: return
                    comprobarYAvisar(info, direccion)
                }

                override fun onResolveFailed(info: NsdServiceInfo, codigo: Int) {
                    // Pasa cuando dos resoluciones se pisan. El siguiente anuncio lo
                    // reintentará; no hace falta hacer nada.
                }
            },
        )
    }

    private fun comprobarYAvisar(info: NsdServiceInfo, direccion: InetAddress) {
        val id = leerTxt(info, Protocolo.TXT_ID) ?: return
        if (id != deviceIdBuscado) {
            // Otro PC de la red, o alguien anunciando el mismo servicio. No es el
            // nuestro: se descarta sin más.
            return
        }

        val puerto = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.port
        } else {
            @Suppress("DEPRECATION")
            info.port
        }

        if (puerto <= 0) return

        alEncontrar?.invoke(direccion.hostAddress ?: return, puerto)
    }

    private fun leerTxt(info: NsdServiceInfo, clave: String): String? =
        info.attributes[clave]?.let { String(it, Charsets.UTF_8) }

    fun detener() {
        escucha?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: IllegalArgumentException) {
                // Ya estaba parado.
            }
        }
        escucha = null
        buscando = false
    }

    private companion object {
        const val TAG = "DracPaste.Nsd"
    }
}
