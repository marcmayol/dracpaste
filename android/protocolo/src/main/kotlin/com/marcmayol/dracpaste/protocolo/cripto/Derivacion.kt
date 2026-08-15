package com.marcmayol.dracpaste.protocolo.cripto

/**
 * Derivación de claves del protocolo (`docs/protocol.md` §2.2, §2.3 y §2.6).
 *
 * Todo lo de aquí es determinista: con las mismas entradas, el móvil y el PC obtienen
 * exactamente los mismos bytes. Eso es lo que se comprueba con los vectores de prueba
 * de §7 sin necesidad de conectar los dos dispositivos.
 */
object Derivacion {

    private val SALT_PAR = "DracPaste/v1/pair".toByteArray(Charsets.US_ASCII)
    private val INFO_M2P = "DracPaste/v1/m2p".toByteArray(Charsets.US_ASCII)
    private val INFO_P2M = "DracPaste/v1/p2m".toByteArray(Charsets.US_ASCII)
    private val PREFIJO_RETOS_EMPAREJAMIENTO =
        "DracPaste/v1/pairing".toByteArray(Charsets.US_ASCII)

    /**
     * Clave de par: se calcula una vez al emparejar y se puede recalcular siempre desde
     * las claves guardadas. Nunca cifra un mensaje; solo es el material del que salen
     * las claves de sesión.
     *
     * Las dos públicas se ordenan byte a byte para que los dos lados lleguen al mismo
     * resultado sin depender de quién es cliente y quién servidor.
     */
    fun clavePar(privadaPropia: ByteArray, publicaDelOtro: ByteArray): ByteArray {
        val publicaPropia = Cripto.clavePublicaDe(privadaPropia)
        val secreto = Cripto.secretoCompartido(privadaPropia, publicaDelOtro)
        return try {
            Cripto.hkdf(
                ikm = secreto,
                salt = SALT_PAR,
                info = ordenar(publicaPropia, publicaDelOtro),
            )
        } finally {
            Cripto.limpiar(secreto)
        }
    }

    /**
     * Claves de sesión, una por dirección, derivadas de los dos retos que se
     * intercambian al conectar.
     *
     * Se derivan en cada conexión a propósito. Con ChaCha20-Poly1305, repetir el par
     * (clave, nonce) rompe la confidencialidad; si se cifrara con la clave de par, cada
     * reconexión —y hay muchas: cambios de red, suspensión, Doze— reiniciaría el
     * contador y reutilizaría combinaciones ya usadas.
     */
    fun clavesDeSesion(clavePar: ByteArray, retoMovil: ByteArray, retoPc: ByteArray): ClavesDeSesion {
        require(retoMovil.size == Cripto.TAM_RETO) { "El reto del móvil debe tener ${Cripto.TAM_RETO} bytes" }
        require(retoPc.size == Cripto.TAM_RETO) { "El reto del PC debe tener ${Cripto.TAM_RETO} bytes" }

        val salt = retoMovil + retoPc
        return ClavesDeSesion(
            movilAPc = Cripto.hkdf(ikm = clavePar, salt = salt, info = INFO_M2P),
            pcAMovil = Cripto.hkdf(ikm = clavePar, salt = salt, info = INFO_P2M),
        )
    }

    /**
     * Retos del emparejamiento. No se intercambian: los dos lados los derivan del token
     * del QR, que ya es aleatorio y de un solo uso, así que el emparejamiento se ahorra
     * una vuelta de mensajes (§3.2 paso 2).
     */
    fun retosDeEmparejamiento(token: ByteArray): Pair<ByteArray, ByteArray> {
        val semilla = Cripto.sha256(PREFIJO_RETOS_EMPAREJAMIENTO + token)
        return semilla.copyOfRange(0, 16) to semilla.copyOfRange(16, 32)
    }

    /**
     * Huella corta para que el usuario compare a ojo lo que ve en el móvil y en el PC.
     * Los dos dispositivos de un par muestran siempre la misma.
     */
    fun huella(publicaA: ByteArray, publicaB: ByteArray): String {
        val resumen = Cripto.sha256(ordenar(publicaA, publicaB))
        val hex = resumen.copyOfRange(0, 4).aHex().uppercase()
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }

    /** `pk_lo || pk_hi`, comparando byte a byte como enteros sin signo. */
    private fun ordenar(a: ByteArray, b: ByteArray): ByteArray =
        if (comparar(a, b) <= 0) a + b else b + a

    private fun comparar(a: ByteArray, b: ByteArray): Int {
        val minimo = minOf(a.size, b.size)
        for (i in 0 until minimo) {
            val diferencia = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diferencia != 0) return diferencia
        }
        return a.size - b.size
    }
}

/** Las dos claves de una sesión. Cada dirección lleva la suya. */
class ClavesDeSesion(val movilAPc: ByteArray, val pcAMovil: ByteArray) {
    /** Clave con la que cifra quien está en este extremo. */
    fun paraEnviar(soyElMovil: Boolean): ByteArray = if (soyElMovil) movilAPc else pcAMovil

    /** Clave con la que se descifra lo que llega. */
    fun paraRecibir(soyElMovil: Boolean): ByteArray = if (soyElMovil) pcAMovil else movilAPc

    fun limpiar() = Cripto.limpiar(movilAPc, pcAMovil)

    override fun toString(): String = "ClavesDeSesion(...)"
}
