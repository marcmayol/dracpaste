package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.Protocolo

/**
 * Estados de la conexión del móvil (`docs/protocol.md` §8 y `PLAN.md` §4.3).
 *
 * La notificación persistente refleja siempre uno de estos estados: el usuario tiene que
 * poder mirar el móvil y saber si su portapapeles está compartido o no.
 */
enum class EstadoConexion {
    /** No hay ningún PC emparejado todavía. */
    SIN_EMPAREJAR,

    /** Hay un PC activo, pero no se le ve en la red. Descubrimiento mDNS en marcha. */
    BUSCANDO,

    /** Se ha encontrado y se está abriendo el socket y haciendo el handshake. */
    CONECTANDO,

    /** Sesión establecida y autenticada. Los clips viajan. */
    CONECTADO,

    /** Se ha caído la conexión. Reintentos con backoff, en paralelo con mDNS. */
    RECONECTANDO,
}

/** Lo que le puede pasar a la conexión. */
sealed interface Evento {
    /** Se ha completado un emparejamiento. */
    data object Emparejado : Evento

    /** Se ha desemparejado (por el usuario o por un UNPAIR del PC). */
    data object Desemparejado : Evento

    /** mDNS ha visto al PC activo, o se recuerda su última IP. */
    data class PcLocalizado(val direccion: String, val puerto: Int) : Evento

    /** El handshake ha terminado bien. */
    data object SesionEstablecida : Evento

    /** El intento de conexión ha fallado. */
    data class ConexionFallida(val motivo: String) : Evento

    /** La sesión se ha caído: socket cerrado, sin PONG, o error de protocolo. */
    data class ConexionPerdida(val motivo: String) : Evento

    /** El móvil ha cambiado de red. Lo que hubiera ya no sirve. */
    data object RedCambiada : Evento

    /** Toca reintentar: lo dispara el backoff, encender la pantalla o arrancar. */
    data object Reintentar : Evento
}

/**
 * Máquina de estados de la conexión.
 *
 * Está aquí, en el módulo JVM puro, en vez de repartida por el servicio de Android, para
 * poder probar todas las transiciones sin un móvil: los cambios de red, las caídas y el
 * backoff son justo lo que no se puede reproducir a mano de forma fiable.
 *
 * No es segura para varios hilos: el servicio la maneja desde un único hilo.
 */
class MaquinaEstados(
    estadoInicial: EstadoConexion = EstadoConexion.SIN_EMPAREJAR,
    private val backoffInicialMs: Long = Protocolo.BACKOFF_INICIAL_MS,
    private val backoffMaximoMs: Long = Protocolo.BACKOFF_MAXIMO_MS,
) {
    var estado: EstadoConexion = estadoInicial
        private set

    /** Última dirección conocida del PC. Se reintenta contra ella mientras mDNS busca. */
    var ultimaDireccion: String? = null
        private set

    var ultimoPuerto: Int = 0
        private set

    /** Cuántos intentos fallidos seguidos llevamos. Solo lo usa el backoff. */
    var intentosFallidos: Int = 0
        private set

    /** Motivo del último problema, para poder enseñarlo en la notificación. */
    var ultimoMotivo: String? = null
        private set

    /**
     * Espera antes del siguiente intento: 1 s, 2 s, 4 s… hasta 30 s.
     *
     * El tope existe para que un PC apagado toda la noche no acabe reintentando cada
     * varias horas: cuando el usuario lo encienda, el móvil debe reaccionar en menos de
     * medio minuto.
     */
    fun esperaSiguienteIntento(): Long {
        if (intentosFallidos <= 0) return backoffInicialMs
        val exponente = minOf(intentosFallidos - 1, 30)
        val espera = backoffInicialMs shl exponente
        return if (espera <= 0 || espera > backoffMaximoMs) backoffMaximoMs else espera
    }

    /** Aplica un evento y devuelve el estado resultante. */
    fun procesar(evento: Evento): EstadoConexion {
        estado = when (evento) {
            is Evento.Emparejado -> {
                reiniciarIntentos()
                EstadoConexion.BUSCANDO
            }

            is Evento.Desemparejado -> {
                reiniciarIntentos()
                ultimaDireccion = null
                ultimoPuerto = 0
                EstadoConexion.SIN_EMPAREJAR
            }

            is Evento.PcLocalizado -> {
                if (estado == EstadoConexion.SIN_EMPAREJAR) {
                    // Un anuncio mDNS no puede resucitar un emparejamiento que ya no
                    // existe. Es justo lo que intentaría un impostor de la red.
                    EstadoConexion.SIN_EMPAREJAR
                } else {
                    ultimaDireccion = evento.direccion
                    ultimoPuerto = evento.puerto
                    EstadoConexion.CONECTANDO
                }
            }

            is Evento.SesionEstablecida -> {
                if (estado == EstadoConexion.SIN_EMPAREJAR) {
                    EstadoConexion.SIN_EMPAREJAR
                } else {
                    reiniciarIntentos()
                    EstadoConexion.CONECTADO
                }
            }

            is Evento.ConexionFallida -> {
                if (estado == EstadoConexion.SIN_EMPAREJAR) {
                    EstadoConexion.SIN_EMPAREJAR
                } else {
                    intentosFallidos++
                    ultimoMotivo = evento.motivo
                    EstadoConexion.RECONECTANDO
                }
            }

            is Evento.ConexionPerdida -> {
                if (estado == EstadoConexion.SIN_EMPAREJAR) {
                    EstadoConexion.SIN_EMPAREJAR
                } else {
                    intentosFallidos++
                    ultimoMotivo = evento.motivo
                    EstadoConexion.RECONECTANDO
                }
            }

            is Evento.RedCambiada -> {
                if (estado == EstadoConexion.SIN_EMPAREJAR) {
                    EstadoConexion.SIN_EMPAREJAR
                } else {
                    // La IP que se recordaba pertenece a la red anterior: reintentar
                    // contra ella solo gasta batería. Se vuelve a descubrir desde cero.
                    reiniciarIntentos()
                    ultimaDireccion = null
                    ultimoPuerto = 0
                    EstadoConexion.BUSCANDO
                }
            }

            is Evento.Reintentar -> when (estado) {
                EstadoConexion.SIN_EMPAREJAR -> EstadoConexion.SIN_EMPAREJAR
                EstadoConexion.CONECTADO -> EstadoConexion.CONECTADO
                EstadoConexion.CONECTANDO -> EstadoConexion.CONECTANDO
                // Con una IP conocida se ataca directamente; si no, hay que descubrir.
                EstadoConexion.BUSCANDO, EstadoConexion.RECONECTANDO ->
                    if (ultimaDireccion != null) EstadoConexion.CONECTANDO else EstadoConexion.BUSCANDO
            }
        }
        return estado
    }

    /** ¿Los clips pueden viajar ahora mismo? */
    fun puedeEnviar(): Boolean = estado == EstadoConexion.CONECTADO

    private fun reiniciarIntentos() {
        intentosFallidos = 0
        ultimoMotivo = null
    }
}
