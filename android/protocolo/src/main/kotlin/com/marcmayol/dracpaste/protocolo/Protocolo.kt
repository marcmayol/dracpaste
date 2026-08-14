package com.marcmayol.dracpaste.protocolo

/**
 * Constantes del protocolo DracPaste v1.
 *
 * Todo lo que hay aquí está fijado en `docs/protocol.md`. Si un valor cambia, cambia
 * primero el documento y después este fichero y su equivalente en C#, en el mismo cambio.
 */
object Protocolo {
    /** Versión del protocolo que habla esta implementación. */
    const val VERSION = 1

    /** Tipo de servicio mDNS que publica el PC y busca el móvil. */
    const val SERVICIO_MDNS = "_dracpaste._tcp"

    /** Puerto preferido. Si está ocupado, el PC toma otro y lo anuncia por mDNS. */
    const val PUERTO_PREFERIDO = 47653

    /** Máximo de un frame completo. Un `length` mayor se trata como error de protocolo. */
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** Máximo del texto de un clip antes de codificar. */
    const val MAX_CLIP_BYTES = 256 * 1024

    /** Cada cuánto se envía un PING. */
    const val INTERVALO_PING_MS = 15_000L

    /** Sin PONG en este plazo, la conexión se da por muerta. */
    const val TIMEOUT_PONG_MS = 10_000L

    /** Un handshake que no termina en este plazo se aborta. */
    const val TIMEOUT_HANDSHAKE_MS = 10_000L

    /** Espera inicial entre reintentos de conexión. */
    const val BACKOFF_INICIAL_MS = 1_000L

    /** Tope de la espera entre reintentos. */
    const val BACKOFF_MAXIMO_MS = 30_000L

    /** Cuánto vale el token del QR de emparejamiento. */
    const val VALIDEZ_TOKEN_MS = 120_000L

    /** Ventana en la que un clip recién recibido no se reenvía (anti-eco). */
    const val VENTANA_ANTIECO_MS = 5_000L

    /** Cuánto se retiene en memoria un envío que falló por no haber conexión. */
    const val RETENCION_ENVIO_FALLIDO_MS = 60_000L

    /** Único tipo de contenido que v1 sabe transportar. */
    const val TIPO_TEXTO = "text/plain"

    /** Claves de los registros TXT del anuncio mDNS. */
    const val TXT_VERSION = "v"
    const val TXT_ID = "id"
    const val TXT_NOMBRE = "name"
}
