package com.marcmayol.dracpaste.protocolo.cripto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Primitivas criptográficas del protocolo, tal y como las fija `docs/protocol.md` §2.
 *
 * Se usa la API de bajo nivel de Bouncy Castle en vez de registrar un proveedor JCE:
 * así el comportamiento es idéntico en el JVM de escritorio y en Android —donde el
 * proveedor por defecto es Conscrypt— y no depende de qué proveedor gane el sorteo.
 *
 * Todos los algoritmos son estándares con vectores públicos (X25519 en RFC 7748,
 * ChaCha20-Poly1305 IETF en RFC 8439, HKDF en RFC 5869), que es lo que permite que el
 * lado de Windows los implemente con libsodium y los dos lados se entiendan
 * (`docs/decisions.md` D-003).
 */
object Cripto {

    const val TAM_CLAVE = 32
    const val TAM_NONCE = 12
    const val TAM_TAG = 16
    const val TAM_RETO = 16

    private val aleatorio = SecureRandom()

    // ---------------------------------------------------------------- X25519

    /** Genera un par de claves X25519 nuevo. */
    fun generarParDeClaves(): ParDeClaves {
        val privada = ByteArray(TAM_CLAVE)
        aleatorio.nextBytes(privada)
        return ParDeClaves(privada, clavePublicaDe(privada))
    }

    /** Deriva la clave pública que corresponde a una privada. */
    fun clavePublicaDe(privada: ByteArray): ByteArray {
        require(privada.size == TAM_CLAVE) { "La clave privada debe tener $TAM_CLAVE bytes" }
        val publica = ByteArray(TAM_CLAVE)
        X25519.scalarMultBase(privada, 0, publica, 0)
        return publica
    }

    /**
     * Secreto compartido X25519.
     *
     * Si la clave pública del otro es un punto de orden pequeño, X25519 produce un
     * resultado de ceros: eso no es un secreto, es un intento de forzar una clave
     * conocida. Se rechaza en vez de seguir adelante.
     */
    fun secretoCompartido(privada: ByteArray, publicaDelOtro: ByteArray): ByteArray {
        require(privada.size == TAM_CLAVE) { "La clave privada debe tener $TAM_CLAVE bytes" }
        require(publicaDelOtro.size == TAM_CLAVE) { "La clave pública debe tener $TAM_CLAVE bytes" }
        val secreto = ByteArray(TAM_CLAVE)
        X25519.scalarMult(privada, 0, publicaDelOtro, 0, secreto, 0)

        // scalarMult no avisa de este caso, así que se comprueba aquí: si el resultado
        // es todo ceros, la pública del otro era un punto de orden pequeño y el
        // "secreto" sería un valor que cualquiera puede predecir.
        if (esTodoCeros(secreto)) {
            secreto.fill(0)
            throw ClaveInvalidaException("La clave pública recibida es un punto de orden pequeño")
        }
        return secreto
    }

    // ------------------------------------------------------------------ HKDF

    /** HKDF-SHA256 (RFC 5869), extract + expand en un paso. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, longitud: Int = TAM_CLAVE): ByteArray {
        val generador = HKDFBytesGenerator(SHA256Digest())
        generador.init(HKDFParameters(ikm, salt, info))
        val salida = ByteArray(longitud)
        generador.generateBytes(salida, 0, longitud)
        return salida
    }

    // ------------------------------------------------- ChaCha20-Poly1305 AEAD

    /**
     * Cifra y autentica. Devuelve `ciphertext || tag`.
     *
     * El AAD va vacío por decisión del protocolo (§2.4): todo lo que hay que proteger
     * viaja dentro del texto plano, y el contador queda autenticado de forma implícita
     * porque forma parte del nonce.
     */
    fun cifrar(clave: ByteArray, nonce: ByteArray, textoPlano: ByteArray): ByteArray {
        require(clave.size == TAM_CLAVE) { "La clave debe tener $TAM_CLAVE bytes" }
        require(nonce.size == TAM_NONCE) { "El nonce debe tener $TAM_NONCE bytes" }

        val motor = ChaCha20Poly1305()
        motor.init(true, AEADParameters(KeyParameter(clave), TAM_TAG * 8, nonce))
        val salida = ByteArray(motor.getOutputSize(textoPlano.size))
        var escritos = motor.processBytes(textoPlano, 0, textoPlano.size, salida, 0)
        escritos += motor.doFinal(salida, escritos)
        return if (escritos == salida.size) salida else salida.copyOf(escritos)
    }

    /**
     * Descifra y verifica el tag.
     *
     * @throws AutenticacionFallidaException si el tag no cuadra. No se distingue entre
     *   "clave equivocada", "nonce equivocado" y "alguien ha manipulado los bytes":
     *   para quien llama son el mismo problema y contestar cuál es filtra información.
     */
    fun descifrar(clave: ByteArray, nonce: ByteArray, cifrado: ByteArray): ByteArray {
        require(clave.size == TAM_CLAVE) { "La clave debe tener $TAM_CLAVE bytes" }
        require(nonce.size == TAM_NONCE) { "El nonce debe tener $TAM_NONCE bytes" }
        if (cifrado.size < TAM_TAG) {
            throw AutenticacionFallidaException("El mensaje cifrado es más corto que su propio tag")
        }

        val motor = ChaCha20Poly1305()
        motor.init(false, AEADParameters(KeyParameter(clave), TAM_TAG * 8, nonce))
        val salida = ByteArray(motor.getOutputSize(cifrado.size))
        return try {
            var escritos = motor.processBytes(cifrado, 0, cifrado.size, salida, 0)
            escritos += motor.doFinal(salida, escritos)
            if (escritos == salida.size) salida else salida.copyOf(escritos)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw AutenticacionFallidaException("El mensaje no supera la verificación de integridad", e)
        }
    }

    /**
     * Construye el nonce del protocolo: 4 bytes a cero y el contador en 8 bytes
     * big-endian (§2.4).
     */
    fun nonceDeContador(contador: Long): ByteArray {
        require(contador >= 0) { "El contador de nonces no puede ser negativo" }
        val nonce = ByteArray(TAM_NONCE)
        for (i in 0 until 8) {
            nonce[TAM_NONCE - 1 - i] = ((contador ushr (8 * i)) and 0xFF).toByte()
        }
        return nonce
    }

    // ----------------------------------------------------------------- Varios

    /** SHA-256, del JVM: está en todas partes y no necesita Bouncy Castle. */
    fun sha256(datos: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(datos)

    /** Bytes aleatorios criptográficamente seguros. */
    fun aleatorio(cuantos: Int): ByteArray = ByteArray(cuantos).also { aleatorio.nextBytes(it) }

    /**
     * Comparación en tiempo constante.
     *
     * Comparar retos o huellas con `contentEquals` deja escapar por el tiempo de
     * ejecución cuántos bytes iniciales ha acertado quien lo intenta.
     */
    fun igualesEnTiempoConstante(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diferencia = 0
        for (i in a.indices) {
            diferencia = diferencia or (a[i].toInt() xor b[i].toInt())
        }
        return diferencia == 0
    }

    /** En tiempo constante, para no filtrar por dónde falla. */
    private fun esTodoCeros(datos: ByteArray): Boolean {
        var acumulado = 0
        for (b in datos) acumulado = acumulado or b.toInt()
        return acumulado == 0
    }

    /** Borra una clave de memoria en cuanto deja de hacer falta. */
    fun limpiar(vararg secretos: ByteArray) {
        secretos.forEach { it.fill(0) }
    }
}

/** Par de claves X25519. La privada nunca sale del dispositivo. */
class ParDeClaves(val privada: ByteArray, val publica: ByteArray) {
    init {
        require(privada.size == Cripto.TAM_CLAVE) { "Clave privada de tamaño incorrecto" }
        require(publica.size == Cripto.TAM_CLAVE) { "Clave pública de tamaño incorrecto" }
    }

    /** No se imprime la privada ni por accidente en un log. */
    override fun toString(): String = "ParDeClaves(publica=${publica.aHex()})"
}

class ClaveInvalidaException(mensaje: String) : Exception(mensaje)

class AutenticacionFallidaException(mensaje: String, causa: Throwable? = null) :
    Exception(mensaje, causa)

/** Hex en minúscula, que es como el protocolo representa los identificadores. */
fun ByteArray.aHex(): String {
    val digitos = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digitos[v ushr 4]).append(digitos[v and 0x0F])
    }
    return sb.toString()
}

/** Inverso de [aHex]. */
fun String.desdeHex(): ByteArray {
    require(length % 2 == 0) { "Una cadena hexadecimal debe tener un número par de dígitos" }
    val salida = ByteArray(length / 2)
    for (i in salida.indices) {
        val alto = Character.digit(this[i * 2], 16)
        val bajo = Character.digit(this[i * 2 + 1], 16)
        require(alto >= 0 && bajo >= 0) { "Carácter no hexadecimal en la posición ${i * 2}" }
        salida[i] = ((alto shl 4) or bajo).toByte()
    }
    return salida
}
