package com.marcmayol.dracpaste.protocolo.red

import com.marcmayol.dracpaste.protocolo.cripto.Cripto

/**
 * El sobre cifrado que envuelve cada mensaje de sesión
 * (`docs/protocol.md` §2.4 y §2.5).
 *
 * El payload de un frame cifrado es `[contador uint64 BE][ciphertext || tag]`. El
 * contador viaja en claro porque el receptor lo necesita para reconstruir el nonce,
 * pero queda autenticado de forma implícita: si alguien lo cambia, el nonce cambia y
 * el tag deja de verificar.
 *
 * Esta clase **no es segura para varios hilos**: cada dirección de una conexión tiene
 * la suya y la usa un solo hilo.
 */
class SobreCifrado(private val clave: ByteArray) {

    /** Contador de lo que se ha enviado por esta dirección. */
    var contadorSalida: Long = 0
        private set

    /**
     * Último contador aceptado en esta dirección. Empieza en -1 porque el primer
     * mensaje legítimo lleva el 0.
     */
    var ultimoContadorAceptado: Long = -1
        private set

    /** Cifra un mensaje y consume un contador. */
    fun sellar(textoPlano: ByteArray): ByteArray {
        val contador = contadorSalida
        val nonce = Cripto.nonceDeContador(contador)
        val cifrado = Cripto.cifrar(clave, nonce, textoPlano)
        contadorSalida = contador + 1

        val salida = ByteArray(8 + cifrado.size)
        escribirContador(salida, contador)
        cifrado.copyInto(salida, 8)
        return salida
    }

    /**
     * Descifra un sobre y verifica que no sea una repetición.
     *
     * @throws ProtocoloException si el contador no avanza. Un contador repetido o hacia
     *   atrás es alguien reinyectando un mensaje anterior: el clip que ya se copió una
     *   vez volvería a aparecer en el portapapeles.
     */
    fun abrir(sobre: ByteArray): ByteArray {
        if (sobre.size < 8 + Cripto.TAM_TAG) {
            throw ProtocoloException("Sobre cifrado demasiado corto: ${sobre.size} bytes")
        }

        val contador = leerContador(sobre)
        if (contador < 0) {
            throw ProtocoloException("Contador de nonce negativo")
        }
        if (contador <= ultimoContadorAceptado) {
            throw ProtocoloException(
                "Contador repetido o retrocedido: $contador tras $ultimoContadorAceptado",
            )
        }

        val nonce = Cripto.nonceDeContador(contador)
        val textoPlano = Cripto.descifrar(clave, nonce, sobre.copyOfRange(8, sobre.size))
        ultimoContadorAceptado = contador
        return textoPlano
    }

    /** Borra la clave cuando la sesión termina. */
    fun limpiar() = Cripto.limpiar(clave)

    private fun escribirContador(destino: ByteArray, valor: Long) {
        for (i in 0 until 8) {
            destino[7 - i] = ((valor ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun leerContador(origen: ByteArray): Long {
        var valor = 0L
        for (i in 0 until 8) {
            valor = (valor shl 8) or (origen[i].toLong() and 0xFF)
        }
        return valor
    }
}
