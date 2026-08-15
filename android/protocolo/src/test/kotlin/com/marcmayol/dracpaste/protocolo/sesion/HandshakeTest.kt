package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.desdeHex
import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.red.Framing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Handshake completo sobre loopback. No hace falta móvil, ni WiFi, ni emparejar nada:
 * dos sockets en 127.0.0.1 recorren exactamente el mismo camino que recorrerán el móvil
 * y el PC.
 */
class HandshakeTest {

    private val idPc = "1111111111111111aaaaaaaaaaaaaaaa"
    private val idMovil = "2222222222222222bbbbbbbbbbbbbbbb"
    private val clavePar =
        "7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74".desdeHex()

    @Test
    fun `el handshake completo establece la sesion en los dos lados`() {
        conPar { par ->
            val (enElPc, enElMovil) = handshakeSobre(par)

            assertEquals(idMovil, enElPc.deviceIdRemoto)
            assertEquals(idPc, enElMovil.deviceIdRemoto)
        }
    }

    @Test
    fun `los contadores empiezan donde los dejo el handshake`() {
        // El handshake gasta el contador 0 en cada dirección (AUTH y AUTH_OK). El primer
        // clip debe llevar el 1: si alguno se reiniciara, se repetiría un nonce.
        conPar { par ->
            val (enElPc, enElMovil) = handshakeSobre(par)

            assertEquals(1L, enElPc.saliente.contadorSalida)
            assertEquals(0L, enElPc.entrante.ultimoContadorAceptado)
            assertEquals(1L, enElMovil.saliente.contadorSalida)
            assertEquals(0L, enElMovil.entrante.ultimoContadorAceptado)
        }
    }

    @Test
    fun `un clip viaja cifrado en las dos direcciones`() {
        conPar { par ->
            val (enElPc, enElMovil) = handshakeSobre(par)

            // Del PC al móvil.
            Framing.escribir(
                par.salidaPc,
                enElPc.saliente.sellar(CodecMensajes.codificar(Clip.deTexto("copiado en el PC"))),
            )
            par.salidaPc.flush()
            val enMovil = CodecMensajes.decodificar(
                enElMovil.entrante.abrir(Framing.leer(par.entradaMovil)),
            ) as Clip
            assertEquals("copiado en el PC", enMovil.texto())

            // Y del móvil al PC.
            Framing.escribir(
                par.salidaMovil,
                enElMovil.saliente.sellar(CodecMensajes.codificar(Clip.deTexto("copiado en el móvil"))),
            )
            par.salidaMovil.flush()
            val enPc = CodecMensajes.decodificar(
                enElPc.entrante.abrir(Framing.leer(par.entradaPc)),
            ) as Clip
            assertEquals("copiado en el móvil", enPc.texto())
        }
    }

    @Test
    fun `el texto no viaja legible por el cable`() {
        // Lo que confirmaría Wireshark, comprobado aquí: los bytes que salen del socket
        // no contienen el texto del clip.
        conPar { par ->
            val (enElPc, _) = handshakeSobre(par)
            val secreto = "esto-no-debe-verse-en-la-red"
            val sellado = enElPc.saliente.sellar(CodecMensajes.codificar(Clip.deTexto(secreto)))

            val comoTexto = String(sellado, Charsets.ISO_8859_1)
            assertFalse(comoTexto.contains(secreto))
            assertFalse(comoTexto.contains("CLIP"))
        }
    }

    @Test
    fun `un movil desconocido no entra`() {
        conPar { par ->
            val enElPc = enHilo {
                Handshake.aceptar(par.entradaPc, par.salidaPc, idPc) { null }
            }
            val enElMovil = enHilo {
                Handshake.iniciar(par.entradaMovil, par.salidaMovil, idMovil, idPc, clavePar)
            }

            assertFallan(enElPc, enElMovil)
        }
    }

    @Test
    fun `un PC con otra clave no consigue autenticarse`() {
        // El impostor de la LAN: anuncia el mismo servicio mDNS y acepta la conexión,
        // pero no tiene la clave del par.
        conPar { par ->
            val claveDelImpostor = Cripto.aleatorio(32)
            val enElPc = enHilo {
                Handshake.aceptar(par.entradaPc, par.salidaPc, idPc) { claveDelImpostor }
            }
            val enElMovil = enHilo {
                Handshake.iniciar(par.entradaMovil, par.salidaMovil, idMovil, idPc, clavePar)
            }

            assertFallan(enElPc, enElMovil)
        }
    }

    @Test
    fun `un PC que no es el activo se rechaza`() {
        // Otro PC de la casa, emparejado también, pero que no es el destino activo.
        conPar { par ->
            val otroPc = "9999999999999999cccccccccccccccc"
            val enElPc = enHilo {
                Handshake.aceptar(par.entradaPc, par.salidaPc, otroPc) { clavePar }
            }
            val enElMovil = enHilo {
                Handshake.iniciar(par.entradaMovil, par.salidaMovil, idMovil, idPc, clavePar)
            }

            assertFallan(enElPc, enElMovil)
        }
    }

    @Test
    fun `cada sesion usa claves distintas`() {
        // Es lo que hace seguro reiniciar los contadores a cero en cada reconexión: los
        // retos son nuevos, así que las claves también.
        val primera = selladoDelPrimerClip()
        val segunda = selladoDelPrimerClip()

        assertNotEquals(primera, segunda)
    }

    @Test
    fun `un clip del movil llega al PC con el mismo origin_id`() {
        // Si el origin_id cambiara por el camino, el anti-eco del otro lado no lo
        // reconocería y el clip rebotaría de vuelta.
        conPar { par ->
            val (enElPc, enElMovil) = handshakeSobre(par)
            val original = Clip.deTexto("texto compartido")

            Framing.escribir(par.salidaMovil, enElMovil.saliente.sellar(CodecMensajes.codificar(original)))
            par.salidaMovil.flush()
            val recibido = CodecMensajes.decodificar(
                enElPc.entrante.abrir(Framing.leer(par.entradaPc)),
            ) as Clip

            assertEquals(original.originId, recibido.originId)
            assertEquals(Clip.origenDe("texto compartido"), recibido.originId)
        }
    }

    // ------------------------------------------------------------------ Apoyo

    private fun selladoDelPrimerClip(): String {
        var resultado = ""
        conPar { par ->
            val (enElPc, _) = handshakeSobre(par)
            resultado = enElPc.saliente
                .sellar(CodecMensajes.codificar(Clip.deTexto("mismo texto", 1)))
                .joinToString("") { "%02x".format(it) }
        }
        return resultado
    }

    private fun handshakeSobre(par: ParDeSockets): Pair<SesionEstablecida, SesionEstablecida> {
        val enElPc = enHilo {
            Handshake.aceptar(par.entradaPc, par.salidaPc, idPc) { id ->
                if (id == idMovil) clavePar else null
            }
        }
        val enElMovil = enHilo {
            Handshake.iniciar(par.entradaMovil, par.salidaMovil, idMovil, idPc, clavePar)
        }
        return enElPc.get(10, TimeUnit.SECONDS) to enElMovil.get(10, TimeUnit.SECONDS)
    }

    private fun assertFallan(vararg futuros: Future<SesionEstablecida>) {
        for (futuro in futuros) {
            try {
                futuro.get(10, TimeUnit.SECONDS)
                fail("Se esperaba que el handshake fallara")
            } catch (esperado: Exception) {
                assertTrue(true)
            }
        }
    }

    private fun <T> enHilo(bloque: () -> T): Future<T> = ejecutor.submit(bloque)

    private fun conPar(bloque: (ParDeSockets) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { escucha ->
            val cliente = Socket()
            val aceptando = ejecutor.submit<Socket> { escucha.accept() }
            cliente.connect(escucha.localSocketAddress, 5_000)
            val servidor = aceptando.get(10, TimeUnit.SECONDS)

            servidor.use { s ->
                cliente.use { c ->
                    bloque(ParDeSockets(s, c))
                }
            }
        }
    }

    private class ParDeSockets(pc: Socket, movil: Socket) {
        val entradaPc = pc.getInputStream()
        val salidaPc = pc.getOutputStream()
        val entradaMovil = movil.getInputStream()
        val salidaMovil = movil.getOutputStream()
    }

    private companion object {
        val ejecutor = Executors.newCachedThreadPool { tarea ->
            Thread(tarea).apply { isDaemon = true }
        }
    }
}
