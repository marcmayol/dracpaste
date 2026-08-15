package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import com.marcmayol.dracpaste.protocolo.cripto.ParDeClaves
import com.marcmayol.dracpaste.protocolo.red.ProtocoloException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emparejamiento completo sobre loopback, incluido el camino que sigue después: que la
 * sesión del día siguiente se abra con lo que quedó guardado.
 */
class EmparejamientoTest {

    private val idPc = "1111111111111111aaaaaaaaaaaaaaaa"
    private val idMovil = "2222222222222222bbbbbbbbbbbbbbbb"

    @Test
    fun `el emparejamiento completo deja la misma clave en los dos lados`() {
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()
        val token = Cripto.aleatorio(16)

        val (enElPc, enElMovil) = emparejar(pc, movil, token)

        assertArrayEquals(enElPc.clavePar, enElMovil.clavePar)
        assertEquals(enElPc.huella, enElMovil.huella)
        assertEquals(idMovil, enElPc.deviceIdRemoto)
        assertEquals(idPc, enElMovil.deviceIdRemoto)
        assertArrayEquals(movil.publica, enElPc.publicaRemota)
        assertArrayEquals(pc.publica, enElMovil.publicaRemota)
    }

    @Test
    fun `la huella que ve el usuario es la misma en las dos pantallas`() {
        // Es lo único que el usuario puede comparar a ojo para saber que no hay nadie en
        // medio. Si cada lado mostrara una distinta, la comprobación no valdría nada.
        val (enElPc, enElMovil) = emparejar(
            Cripto.generarParDeClaves(),
            Cripto.generarParDeClaves(),
            Cripto.aleatorio(16),
        )

        assertEquals(enElPc.huella, enElMovil.huella)
        assertTrue(enElPc.huella.matches(Regex("^[0-9A-F]{4}-[0-9A-F]{4}$")))
    }

    @Test
    fun `tras emparejar se puede abrir sesion con lo guardado`() {
        // El recorrido real: hoy se empareja, mañana la app arranca y solo tiene las
        // claves guardadas. Si la clave de par no se pudiera recalcular, el
        // emparejamiento no serviría de nada.
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()
        val (enElPc, _) = emparejar(pc, movil, Cripto.aleatorio(16))

        val clavePcRecalculada = Derivacion.clavePar(pc.privada, enElPc.publicaRemota)
        val claveMovilRecalculada = Derivacion.clavePar(movil.privada, pc.publica)
        assertArrayEquals(clavePcRecalculada, claveMovilRecalculada)

        conPar { par ->
            val sesionPc = enHilo {
                Handshake.aceptar(par.entradaPc, par.salidaPc, idPc) { clavePcRecalculada }
            }
            val sesionMovil = enHilo {
                Handshake.iniciar(par.entradaMovil, par.salidaMovil, idMovil, idPc, claveMovilRecalculada)
            }

            assertEquals(idMovil, sesionPc.get(10, TimeUnit.SECONDS).deviceIdRemoto)
            assertEquals(idPc, sesionMovil.get(10, TimeUnit.SECONDS).deviceIdRemoto)
        }
    }

    @Test
    fun `un token invalido corta el emparejamiento`() {
        // Alguien de la red que intenta emparejarse sin haber visto nunca el QR.
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()

        conPar { par ->
            val enElPc = enHilo {
                Emparejamiento.aceptar(par.entradaPc, par.salidaPc, pc.privada, idPc, "PC") { false }
            }
            val enElMovil = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel",
                    qr(pc, Cripto.aleatorio(16)),
                )
            }

            assertFallan(enElPc, enElMovil)
        }
    }

    @Test
    fun `el token se consume una sola vez`() {
        // Aunque el mismo token llegue dos veces, la segunda no puede emparejar: el
        // consumo y la comprobación son el mismo paso.
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()
        val token = Cripto.aleatorio(16)
        val usos = AtomicInteger(0)
        val consumir: (ByteArray) -> Boolean = { usos.incrementAndGet() == 1 }

        conPar { par ->
            val a = enHilo {
                try {
                    Emparejamiento.aceptar(par.entradaPc, par.salidaPc, pc.privada, idPc, "PC", consumir)
                } finally {
                    par.cerrarLadoPc()
                }
            }
            val b = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel", qr(pc, token),
                )
            }
            a.get(10, TimeUnit.SECONDS)
            b.get(10, TimeUnit.SECONDS)
        }

        conPar { par ->
            val a = enHilo {
                Emparejamiento.aceptar(par.entradaPc, par.salidaPc, pc.privada, idPc, "PC", consumir)
            }
            val b = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel", qr(pc, token),
                )
            }
            assertFallan(a, b)
        }
    }

    @Test
    fun `un PC que finge ser otro no pasa la comprobacion de huella`() {
        // El QR dice una clave pública y contesta un PC con otra: el móvil deriva su
        // clave con la del QR y ni siquiera consigue descifrar lo que le llega.
        val pcDelQr = Cripto.generarParDeClaves()
        val impostor = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()

        conPar { par ->
            val enElImpostor = enHilo {
                Emparejamiento.aceptar(
                    par.entradaPc, par.salidaPc, impostor.privada, idPc, "PC falso",
                ) { true }
            }
            val enElMovil = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel",
                    qr(pcDelQr, Cripto.aleatorio(16)),
                )
            }

            assertFallan(enElMovil, enElImpostor)
        }
    }

    @Test
    fun `el movil no da por bueno el emparejamiento hasta que el PC cierra`() {
        // El PAIR_ACK es el último mensaje: su emisor no sabría si llegó. Como el PC
        // cierra solo después de guardar, ese cierre es el acuse. Aquí el PC recibe el
        // ACK pero se queda callado sin cerrar —el equivalente a quedarse sin disco al
        // guardar— y el móvil no debe declararse emparejado.
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()
        val token = Cripto.aleatorio(16)

        conPar { par ->
            val enElPc = enHilo {
                Emparejamiento.aceptar(
                    par.entradaPc, par.salidaPc, pc.privada, idPc, "PC",
                ) { t -> t.contentEquals(token) }
            }
            val enElMovil = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel", qr(pc, token),
                )
            }

            // El PC completa su parte pero no cierra.
            enElPc.get(10, TimeUnit.SECONDS)

            try {
                enElMovil.get(2, TimeUnit.SECONDS)
                fail("El móvil no debe darse por emparejado sin el cierre del PC")
            } catch (esperado: Exception) {
                assertTrue(true)
            }
        }
    }

    @Test
    fun `si el PC contesta en vez de cerrar se aborta`() {
        // Un PC que no sigue el protocolo: manda algo después del PAIR_ACK.
        val pc = Cripto.generarParDeClaves()
        val movil = Cripto.generarParDeClaves()
        val token = Cripto.aleatorio(16)

        conPar { par ->
            val enElPc = enHilo {
                val resultado = Emparejamiento.aceptar(
                    par.entradaPc, par.salidaPc, pc.privada, idPc, "PC",
                ) { t -> t.contentEquals(token) }
                com.marcmayol.dracpaste.protocolo.red.Framing.escribir(
                    par.salidaPc, "sorpresa".toByteArray(),
                )
                par.salidaPc.flush()
                resultado
            }
            val enElMovil = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel", qr(pc, token),
                )
            }

            enElPc.get(10, TimeUnit.SECONDS)
            try {
                enElMovil.get(10, TimeUnit.SECONDS)
                fail("Se esperaba el rechazo")
            } catch (esperado: Exception) {
                assertTrue(
                    "Motivo inesperado: ${esperado.cause?.message}",
                    esperado.cause?.message?.contains("cerrar la conexión") == true,
                )
            }
        }
    }

    @Test
    fun `el QR va y vuelve`() {
        val original = DatosQr(
            pk = Base64.getEncoder().encodeToString(Cripto.generarParDeClaves().publica),
            ip = "192.168.1.40",
            port = 47653,
            token = Base64.getEncoder().encodeToString(Cripto.aleatorio(16)),
            name = "PC-DESPACHO",
            deviceId = idPc,
        )

        assertEquals(original, DatosQr.leer(original.aSerializar()))
    }

    @Test
    fun `un QR de otra version se rechaza con un mensaje entendible`() {
        val json = """{"v":99,"pk":"AA==","ip":"1.2.3.4","port":1,"token":"AA==","name":"x","device_id":"y"}"""

        try {
            DatosQr.leer(json)
            fail("Se esperaba el rechazo por versión")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("versión"))
        }
    }

    @Test
    fun `un QR de otra cosa se rechaza`() {
        // El usuario apunta la cámara a cualquier otro QR del mundo.
        try {
            DatosQr.leer("https://ejemplo.com")
            fail("Se esperaba el rechazo")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("DracPaste"))
        }
    }

    // ------------------------------------------------------------------ Apoyo

    private fun qr(pc: ParDeClaves, token: ByteArray) = DatosQr(
        pk = Base64.getEncoder().encodeToString(pc.publica),
        ip = "127.0.0.1",
        port = 47653,
        token = Base64.getEncoder().encodeToString(token),
        name = "PC",
        deviceId = idPc,
    )

    private fun emparejar(
        pc: ParDeClaves,
        movil: ParDeClaves,
        token: ByteArray,
    ): Pair<ResultadoEmparejamiento, ResultadoEmparejamiento> {
        lateinit var resultado: Pair<ResultadoEmparejamiento, ResultadoEmparejamiento>
        conPar { par ->
            // El PC cierra su socket tras guardar, como hace el servidor real: ese
            // cierre es lo que el móvil espera como acuse del PAIR_ACK
            // (docs/protocol.md §3.2 paso 6).
            val enElPc = enHilo {
                try {
                    Emparejamiento.aceptar(
                        par.entradaPc, par.salidaPc, pc.privada, idPc, "PC-DESPACHO",
                    ) { t -> t.contentEquals(token) }
                } finally {
                    par.cerrarLadoPc()
                }
            }
            val enElMovil = enHilo {
                Emparejamiento.iniciar(
                    par.entradaMovil, par.salidaMovil, movil.privada, idMovil, "Pixel", qr(pc, token),
                )
            }
            resultado = enElPc.get(10, TimeUnit.SECONDS) to enElMovil.get(10, TimeUnit.SECONDS)
        }
        return resultado
    }

    private fun assertFallan(vararg futuros: Future<*>) {
        for (futuro in futuros) {
            try {
                futuro.get(10, TimeUnit.SECONDS)
                fail("Se esperaba que el emparejamiento fallara")
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

            servidor.use { s -> cliente.use { c -> bloque(ParDeSockets(s, c)) } }
        }
    }

    private class ParDeSockets(private val pc: Socket, movil: Socket) {
        val entradaPc = pc.getInputStream()
        val salidaPc = pc.getOutputStream()
        val entradaMovil = movil.getInputStream()
        val salidaMovil = movil.getOutputStream()

        /** Lo que hace el servidor real al terminar de emparejar. */
        fun cerrarLadoPc() = pc.close()
    }

    private companion object {
        val ejecutor = Executors.newCachedThreadPool { tarea ->
            Thread(tarea).apply { isDaemon = true }
        }
    }
}
