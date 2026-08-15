package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import com.marcmayol.dracpaste.protocolo.mensajes.Auth
import com.marcmayol.dracpaste.protocolo.mensajes.AuthOk
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.mensajes.Hello
import com.marcmayol.dracpaste.protocolo.mensajes.ServerHello
import com.marcmayol.dracpaste.protocolo.red.Framing
import com.marcmayol.dracpaste.protocolo.red.ProtocoloException
import com.marcmayol.dracpaste.protocolo.red.SobreCifrado
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * El handshake de sesión (`docs/protocol.md` §4).
 *
 * Está separado del socket a propósito: recibe flujos cualesquiera, así que se puede
 * probar entero sobre loopback, sin móvil ni WiFi.
 *
 * Al terminar, los dos extremos saben que el otro posee la clave de par, y cada uno
 * tiene su pareja de sobres cifrados con los contadores a cero.
 *
 * Las operaciones son bloqueantes: en Android se llaman desde `Dispatchers.IO`.
 */
object Handshake {

    /**
     * Lado del móvil: abre la sesión contra un PC ya emparejado.
     *
     * @param deviceIdEsperado `device_id` del PC activo. Si contesta otro, se corta: es
     *   justo lo que haría un impostor de la red anunciando el mismo servicio mDNS.
     */
    fun iniciar(
        entrada: InputStream,
        salida: OutputStream,
        miDeviceId: String,
        deviceIdEsperado: String,
        clavePar: ByteArray,
    ): SesionEstablecida {
        val retoMovil = Cripto.aleatorio(Cripto.TAM_RETO)
        Framing.escribir(
            salida,
            CodecMensajes.codificar(
                Hello(deviceId = miDeviceId, challenge = Base64.getEncoder().encodeToString(retoMovil)),
            ),
        )
        salida.flush()

        val serverHello = CodecMensajes.decodificar(Framing.leer(entrada)) as? ServerHello
            ?: throw ProtocoloException("Se esperaba un SERVER_HELLO")

        if (serverHello.v != Protocolo.VERSION) {
            throw ProtocoloException("Versión de protocolo no soportada: ${serverHello.v}")
        }
        if (serverHello.deviceId != deviceIdEsperado) {
            throw ProtocoloException(
                "Contestó ${serverHello.deviceId} y se esperaba $deviceIdEsperado",
            )
        }

        val retoPc = decodificarReto(serverHello.challenge, "del PC")
        val claves = Derivacion.clavesDeSesion(clavePar, retoMovil, retoPc)
        val entranteCifrado = SobreCifrado(claves.paraRecibir(soyElMovil = true))
        val salienteCifrado = SobreCifrado(claves.paraEnviar(soyElMovil = true))

        Framing.escribir(
            salida,
            salienteCifrado.sellar(
                CodecMensajes.codificar(Auth(echo = Base64.getEncoder().encodeToString(retoPc))),
            ),
        )
        salida.flush()

        val respuesta = CodecMensajes.decodificar(entranteCifrado.abrir(Framing.leer(entrada)))
        val authOk = respuesta as? AuthOk
            ?: throw ProtocoloException("Se esperaba un AUTH_OK y llegó ${respuesta.t}")

        verificarEco(authOk.echo, retoMovil)

        return SesionEstablecida(serverHello.deviceId, entranteCifrado, salienteCifrado)
    }

    /**
     * Lado del PC. En Android no se usa —el móvil siempre es el cliente— pero existe
     * para poder probar el handshake completo de punta a punta sobre loopback.
     *
     * @param buscarClavePar devuelve la clave de par de ese `device_id`, o `null` si no
     *   está emparejado.
     */
    fun aceptar(
        entrada: InputStream,
        salida: OutputStream,
        miDeviceId: String,
        buscarClavePar: (String) -> ByteArray?,
    ): SesionEstablecida {
        val hello = CodecMensajes.decodificar(Framing.leer(entrada)) as? Hello
            ?: throw ProtocoloException("Se esperaba un HELLO para abrir la sesión")

        if (hello.v != Protocolo.VERSION) {
            throw ProtocoloException("Versión de protocolo no soportada: ${hello.v}")
        }

        val clavePar = buscarClavePar(hello.deviceId)
            ?: throw ProtocoloException("El dispositivo ${hello.deviceId} no está emparejado")

        val retoMovil = decodificarReto(hello.challenge, "del móvil")
        val retoPc = Cripto.aleatorio(Cripto.TAM_RETO)

        Framing.escribir(
            salida,
            CodecMensajes.codificar(
                ServerHello(deviceId = miDeviceId, challenge = Base64.getEncoder().encodeToString(retoPc)),
            ),
        )
        salida.flush()

        val claves = Derivacion.clavesDeSesion(clavePar, retoMovil, retoPc)
        val entranteCifrado = SobreCifrado(claves.paraRecibir(soyElMovil = false))
        val salienteCifrado = SobreCifrado(claves.paraEnviar(soyElMovil = false))

        val mensaje = CodecMensajes.decodificar(entranteCifrado.abrir(Framing.leer(entrada)))
        val auth = mensaje as? Auth
            ?: throw ProtocoloException("Se esperaba un AUTH y llegó ${mensaje.t}")

        verificarEco(auth.echo, retoPc)

        Framing.escribir(
            salida,
            salienteCifrado.sellar(
                CodecMensajes.codificar(AuthOk(echo = Base64.getEncoder().encodeToString(retoMovil))),
            ),
        )
        salida.flush()

        return SesionEstablecida(hello.deviceId, entranteCifrado, salienteCifrado)
    }

    private fun decodificarReto(base64: String, dueno: String): ByteArray {
        val reto = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw ProtocoloException("El reto $dueno no es base64 válido", e)
        }
        if (reto.size != Cripto.TAM_RETO) {
            throw ProtocoloException(
                "El reto $dueno tiene ${reto.size} bytes y debe tener ${Cripto.TAM_RETO}",
            )
        }
        return reto
    }

    private fun verificarEco(ecoBase64: String, esperado: ByteArray) {
        val eco = try {
            Base64.getDecoder().decode(ecoBase64)
        } catch (e: IllegalArgumentException) {
            throw ProtocoloException("El eco del reto no es base64 válido", e)
        }
        if (!Cripto.igualesEnTiempoConstante(eco, esperado)) {
            throw ProtocoloException("El eco del reto no coincide")
        }
    }
}

/** Una sesión autenticada, con sus dos sobres listos. */
class SesionEstablecida(
    val deviceIdRemoto: String,
    val entrante: SobreCifrado,
    val saliente: SobreCifrado,
) {
    fun limpiar() {
        entrante.limpiar()
        saliente.limpiar()
    }
}
