package com.marcmayol.dracpaste.servicio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Los tres avisos que despiertan la reconexión (`docs/protocol.md` §8).
 *
 * Sin ellos, el móvil solo reintentaría con el backoff, que llega a esperar 30 segundos.
 * Con ellos, la reconexión ocurre en el instante en que vuelve a ser posible:
 *
 * - **Cambio de red**: al pasar del WiFi a datos o al revés, el socket anterior está
 *   muerto aunque no lo parezca, y la IP que se recordaba pertenece a otra red.
 * - **Pantalla encendida**: es el momento en que el usuario va a usar el móvil, y además
 *   sirve para mitigar Doze sin pedir un `WakeLock` ni mantener nada despierto.
 * - **Arranque**: el servicio tiene que volver solo tras reiniciar.
 */
class VigilanteDeRed(private val contexto: Context) {

    private val conectividad =
        contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callbackDeRed: ConnectivityManager.NetworkCallback? = null
    private var receptorDePantalla: BroadcastReceiver? = null

    fun empezar() {
        vigilarLaRed()
        vigilarLaPantalla()
    }

    private fun vigilarLaRed() {
        val peticion = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(red: Network) {
                // Hay red nueva. El socket que hubiera pertenece a la anterior y no va a
                // dar ningún error hasta que expire por su cuenta, que puede tardar
                // minutos: durante ese rato los clips se perderían en silencio.
                ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RED_CAMBIADA)
            }

            override fun onLost(red: Network) {
                // No se hace nada: quien decide que la conexión ha muerto es el PING. Un
                // salto entre puntos de acceso levanta y tira redes en un instante, y
                // cortar la sesión en cada parpadeo daría más problemas que soluciones.
            }
        }

        try {
            conectividad.registerNetworkCallback(peticion, callback)
            callbackDeRed = callback
        } catch (e: SecurityException) {
            // Sin ACCESS_NETWORK_STATE. La app sigue funcionando con el backoff.
        }
    }

    private fun vigilarLaPantalla() {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(contextoRecibido: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RECONECTAR)
                }
            }
        }

        // Este intent solo se puede recibir registrándolo en código: declararlo en el
        // manifiesto no funciona desde Android 8.
        contexto.registerReceiver(receptor, IntentFilter(Intent.ACTION_SCREEN_ON))
        receptorDePantalla = receptor
    }

    fun parar() {
        callbackDeRed?.let {
            try {
                conectividad.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // Ya estaba fuera.
            }
        }
        callbackDeRed = null

        receptorDePantalla?.let {
            try {
                contexto.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Ya estaba fuera.
            }
        }
        receptorDePantalla = null
    }
}

/**
 * Levanta el servicio al arrancar el móvil.
 *
 * Solo si hay algún PC emparejado: arrancar un servicio en primer plano —con su
 * notificación permanente— en un móvil donde la app aún no se ha configurado sería
 * molesto y no serviría de nada.
 */
class ReceptorDeArranque : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val hayPcs = com.marcmayol.dracpaste.datos.RegistroPcs(contexto).todos().isNotEmpty()
        if (hayPcs) {
            ServicioDracPaste.arrancar(contexto)
        }
    }
}
