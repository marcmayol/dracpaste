package com.marcmayol.dracpaste.protocolo.red

import com.marcmayol.dracpaste.protocolo.Protocolo
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Framing del protocolo (`docs/protocol.md` §1): `[longitud uint32 BE][payload]`.
 *
 * Un socket TCP entrega bytes, no mensajes: sin este envoltorio, dos clips copiados
 * seguidos pueden llegar pegados en la misma lectura o partidos entre dos.
 */
object Framing {

    /** Escribe un frame completo. No cierra ni vacía el flujo. */
    fun escribir(salida: OutputStream, payload: ByteArray) {
        if (payload.isEmpty()) {
            throw ProtocoloException("No se envían frames vacíos")
        }
        if (payload.size > Protocolo.MAX_FRAME_BYTES) {
            throw ProtocoloException(
                "El frame ocupa ${payload.size} bytes y el máximo es ${Protocolo.MAX_FRAME_BYTES}",
            )
        }

        val cabecera = ByteArray(4)
        cabecera[0] = (payload.size ushr 24).toByte()
        cabecera[1] = (payload.size ushr 16).toByte()
        cabecera[2] = (payload.size ushr 8).toByte()
        cabecera[3] = payload.size.toByte()
        salida.write(cabecera)
        salida.write(payload)
    }

    /**
     * Lee un frame completo, bloqueando hasta tenerlo entero.
     *
     * La longitud se valida **antes** de reservar memoria: un `length` de 4 GB en la
     * cabecera no puede convertirse en una petición de 4 GB de RAM. Cualquiera en la
     * red puede abrir un socket y mandar esos cuatro bytes.
     */
    fun leer(entrada: InputStream): ByteArray {
        val cabecera = leerExactamente(entrada, 4)
        val longitud =
            ((cabecera[0].toInt() and 0xFF) shl 24) or
                ((cabecera[1].toInt() and 0xFF) shl 16) or
                ((cabecera[2].toInt() and 0xFF) shl 8) or
                (cabecera[3].toInt() and 0xFF)

        if (longitud <= 0) {
            throw ProtocoloException("Longitud de frame inválida: $longitud")
        }
        if (longitud > Protocolo.MAX_FRAME_BYTES) {
            throw ProtocoloException(
                "El frame anunciado ocupa $longitud bytes y el máximo es ${Protocolo.MAX_FRAME_BYTES}",
            )
        }

        return leerExactamente(entrada, longitud)
    }

    private fun leerExactamente(entrada: InputStream, cuantos: Int): ByteArray {
        val destino = ByteArray(cuantos)
        var leidos = 0
        while (leidos < cuantos) {
            val n = entrada.read(destino, leidos, cuantos - leidos)
            if (n < 0) {
                throw EOFException("La conexión se cerró tras $leidos de $cuantos bytes")
            }
            leidos += n
        }
        return destino
    }
}

/** Algo que no encaja con `docs/protocol.md`. Siempre acaba en cerrar la conexión. */
class ProtocoloException(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)
