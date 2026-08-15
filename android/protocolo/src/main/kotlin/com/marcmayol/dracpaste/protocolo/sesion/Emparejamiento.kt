package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.mensajes.PairAck
import com.marcmayol.dracpaste.protocolo.mensajes.PairConfirm
import com.marcmayol.dracpaste.protocolo.mensajes.PairRequest
import com.marcmayol.dracpaste.protocolo.red.Framing
import com.marcmayol.dracpaste.protocolo.red.ProtocoloException
import com.marcmayol.dracpaste.protocolo.red.SobreCifrado
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * El emparejamiento (`docs/protocol.md` §3), que ocurre una vez por par de dispositivos.
 *
 * Lo único que impide que cualquiera de la red local se empareje por su cuenta es el
 * token del QR: demuestra que quien se empareja ha tenido delante la pantalla del PC.
 * Por eso el token es de un solo uso y caduca en dos minutos.
 */
object Emparejamiento {

    /** Lado del móvil: se empareja con el PC del QR. */
    fun iniciar(
        entrada: InputStream,
        salida: OutputStream,
        miPrivada: ByteArray,
        miDeviceId: String,
        miNombre: String,
        qr: DatosQr,
    ): ResultadoEmparejamiento {
        val publicaDelPc = decodificarBase64(qr.pk, "la clave pública del PC")
        val tokenQr = decodificarBase64(qr.token, "el token del QR")

        Framing.escribir(
            salida,
            CodecMensajes.codificar(
                PairRequest(
                    pk = Base64.getEncoder().encodeToString(Cripto.clavePublicaDe(miPrivada)),
                    deviceId = miDeviceId,
                    name = miNombre,
                    token = qr.token,
                ),
            ),
        )
        salida.flush()

        val clavePar = Derivacion.clavePar(miPrivada, publicaDelPc)
        val huella = Derivacion.huella(Cripto.clavePublicaDe(miPrivada), publicaDelPc)

        val (retoMovil, retoPc) = Derivacion.retosDeEmparejamiento(tokenQr)
        val claves = Derivacion.clavesDeSesion(clavePar, retoMovil, retoPc)
        val entranteCifrado = SobreCifrado(claves.paraRecibir(soyElMovil = true))
        val salienteCifrado = SobreCifrado(claves.paraEnviar(soyElMovil = true))

        val mensaje = CodecMensajes.decodificar(entranteCifrado.abrir(Framing.leer(entrada)))
        val confirmacion = mensaje as? PairConfirm
            ?: throw ProtocoloException("Se esperaba un PAIR_CONFIRM y llegó ${mensaje.t}")

        if (confirmacion.fingerprint != huella) {
            throw ProtocoloException("Las huellas no coinciden")
        }

        Framing.escribir(salida, salienteCifrado.sellar(CodecMensajes.codificar(PairAck(fingerprint = huella))))
        salida.flush()

        esperarAlCierreDelPc(entrada)

        return ResultadoEmparejamiento(
            deviceIdRemoto = confirmacion.deviceId,
            nombreRemoto = confirmacion.name,
            publicaRemota = publicaDelPc,
            clavePar = clavePar,
            huella = huella,
        )
    }

    /**
     * Lado del PC. En Android no se usa, pero existe para poder probar el
     * emparejamiento completo de punta a punta sobre loopback.
     *
     * @param consumirToken comprueba el token y lo invalida en el mismo paso. Que las
     *   dos cosas sean atómicas evita que dos peticiones simultáneas aprovechen el
     *   mismo token.
     */
    fun aceptar(
        entrada: InputStream,
        salida: OutputStream,
        miPrivada: ByteArray,
        miDeviceId: String,
        miNombre: String,
        consumirToken: (ByteArray) -> Boolean,
    ): ResultadoEmparejamiento {
        val peticion = CodecMensajes.decodificar(Framing.leer(entrada)) as? PairRequest
            ?: throw ProtocoloException("Se esperaba un PAIR_REQUEST")

        if (peticion.v != Protocolo.VERSION) {
            throw ProtocoloException("Versión de protocolo no soportada: ${peticion.v}")
        }

        val tokenRecibido = decodificarBase64(peticion.token, "el token")
        if (!consumirToken(tokenRecibido)) {
            // No se contesta nada ni se explica el motivo: a quien lo intenta sin haber
            // visto el QR no se le confirma siquiera que este PC hable el protocolo.
            throw ProtocoloException("Token de emparejamiento no válido")
        }

        val publicaDelMovil = decodificarBase64(peticion.pk, "la clave pública")
        if (publicaDelMovil.size != Cripto.TAM_CLAVE) {
            throw ProtocoloException(
                "La clave pública tiene ${publicaDelMovil.size} bytes y debe tener ${Cripto.TAM_CLAVE}",
            )
        }

        val clavePar = Derivacion.clavePar(miPrivada, publicaDelMovil)
        val huella = Derivacion.huella(Cripto.clavePublicaDe(miPrivada), publicaDelMovil)

        val (retoMovil, retoPc) = Derivacion.retosDeEmparejamiento(tokenRecibido)
        val claves = Derivacion.clavesDeSesion(clavePar, retoMovil, retoPc)
        val entranteCifrado = SobreCifrado(claves.paraRecibir(soyElMovil = false))
        val salienteCifrado = SobreCifrado(claves.paraEnviar(soyElMovil = false))

        Framing.escribir(
            salida,
            salienteCifrado.sellar(
                CodecMensajes.codificar(
                    PairConfirm(deviceId = miDeviceId, name = miNombre, fingerprint = huella),
                ),
            ),
        )
        salida.flush()

        val respuesta = CodecMensajes.decodificar(entranteCifrado.abrir(Framing.leer(entrada)))
        val ack = respuesta as? PairAck
            ?: throw ProtocoloException("Se esperaba un PAIR_ACK y llegó ${respuesta.t}")

        if (ack.fingerprint != huella) {
            throw ProtocoloException("Las huellas no coinciden")
        }

        return ResultadoEmparejamiento(
            deviceIdRemoto = peticion.deviceId,
            nombreRemoto = peticion.name,
            publicaRemota = publicaDelMovil,
            clavePar = clavePar,
            huella = huella,
        )
    }

    /**
     * Espera a que el PC cierre la conexión, que es su forma de acusar recibo del
     * `PAIR_ACK` (`docs/protocol.md` §3.2 paso 6).
     *
     * El `PAIR_ACK` es el último mensaje, así que quien lo envía no sabría si llegó.
     * Como el PC cierra solo después de haber guardado, ese cierre es la única señal de
     * que el emparejamiento existe en los dos lados. Sin esto, un PC que falle al
     * guardar dejaría al móvil creyendo que está emparejado mientras el PC lo rechaza
     * en cada conexión, y el usuario vería "emparejado" en una pantalla y "sin
     * emparejar" en la otra sin ninguna pista de por qué.
     */
    private fun esperarAlCierreDelPc(entrada: InputStream) {
        try {
            Framing.leer(entrada)
        } catch (esperado: EOFException) {
            // Es lo que se espera: el PC guardó y cerró.
            return
        } catch (e: IOException) {
            throw ProtocoloException("El PC cortó la conexión sin confirmar el emparejamiento", e)
        }

        // Si en vez de cerrar manda algo, no está siguiendo este protocolo.
        throw ProtocoloException("El PC respondió al PAIR_ACK en vez de cerrar la conexión")
    }

    private fun decodificarBase64(valor: String, que: String): ByteArray = try {
        Base64.getDecoder().decode(valor)
    } catch (e: IllegalArgumentException) {
        throw ProtocoloException("$que no es base64 válido", e)
    }
}

/** Lo que queda tras emparejarse: con esto se puede abrir sesión mañana. */
class ResultadoEmparejamiento(
    val deviceIdRemoto: String,
    val nombreRemoto: String,
    val publicaRemota: ByteArray,
    /**
     * Se guarda la pública, no esto: la clave de par se recalcula siempre desde las dos
     * públicas y la privada propia.
     */
    val clavePar: ByteArray,
    val huella: String,
)

/**
 * El contenido del QR (`docs/protocol.md` §3.1). Es el único mensaje del protocolo que
 * no viaja por el socket, sino por la pantalla y la cámara.
 */
@Serializable
data class DatosQr(
    val v: Int = Protocolo.VERSION,
    val pk: String,
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    @SerialName("device_id") val deviceId: String,
) {
    fun aSerializar(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun leer(texto: String): DatosQr {
            val datos = try {
                json.decodeFromString(serializer(), texto)
            } catch (e: Exception) {
                throw ProtocoloException("El QR no contiene un emparejamiento de DracPaste", e)
            }

            if (datos.v != Protocolo.VERSION) {
                throw ProtocoloException(
                    "El QR es de la versión ${datos.v} y esta app habla la ${Protocolo.VERSION}",
                )
            }
            return datos
        }
    }
}
