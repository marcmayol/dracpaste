package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.Protocolo

/**
 * Evita el bucle de eco entre los dos portapapeles (`docs/protocol.md` §6).
 *
 * El problema que resuelve: el PC envía un clip, el móvil lo escribe en su portapapeles,
 * el listener del móvil lo detecta como un cambio y lo devuelve al PC, que lo escribe en
 * el suyo, y así indefinidamente. Sin esto, copiar una vez deja los dos dispositivos
 * dándose el mismo texto para siempre.
 *
 * La regla es la misma en los dos lados: antes de escribir un clip recibido se anota su
 * `origin_id`; si el cambio local que llega después tiene ese mismo `origin_id`, es el
 * eco de lo que se acaba de escribir y no se reenvía.
 *
 * La marca **caduca**, y ese detalle importa: si no caducara, un usuario que vuelve a
 * copiar el mismo texto a mano media hora después vería que no se sincroniza y no
 * entendería por qué.
 */
class AntiEco(
    private val ventanaMs: Long = Protocolo.VENTANA_ANTIECO_MS,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private var ultimoOrigen: String? = null
    private var marcadoEn: Long = 0

    /** Se llama justo antes de escribir en el portapapeles un clip recibido. */
    fun marcarRecibido(originId: String) {
        ultimoOrigen = originId
        marcadoEn = reloj()
    }

    /**
     * ¿Hay que reenviar este cambio del portapapeles local?
     *
     * Consume la marca cuando reconoce el eco: si el usuario copia dos veces seguidas el
     * mismo texto a mano, la segunda sí viaja.
     */
    fun debeReenviar(originId: String): Boolean {
        val marcado = ultimoOrigen ?: return true

        if (reloj() - marcadoEn > ventanaMs) {
            ultimoOrigen = null
            return true
        }

        if (marcado == originId) {
            ultimoOrigen = null
            return false
        }

        return true
    }

    /** Al desconectar o desemparejar, la marca deja de tener sentido. */
    fun olvidar() {
        ultimoOrigen = null
        marcadoEn = 0
    }
}
