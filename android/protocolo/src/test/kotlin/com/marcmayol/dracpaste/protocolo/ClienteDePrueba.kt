package com.marcmayol.dracpaste.protocolo

import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.aHex
import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.red.Framing
import com.marcmayol.dracpaste.protocolo.sesion.DatosQr
import com.marcmayol.dracpaste.protocolo.sesion.Emparejamiento
import com.marcmayol.dracpaste.protocolo.sesion.Handshake
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente de prueba que hace de móvil contra el servidor **real** de Windows.
 *
 * Esto es lo que ningún test unitario puede demostrar: que la implementación Kotlin
 * (Bouncy Castle) y la de C# (libsodium) se entienden **por un socket de verdad**, con
 * sus bytes reales, no con vectores copiados a mano en los dos lados.
 *
 * Lo lanza `scripts/prueba-cruzada.ps1`, que arranca el servidor C#, le pasa a este
 * cliente el JSON del QR y comprueba que los dos textos cruzan.
 *
 *   ./gradlew :protocolo:clienteDePrueba --args="<json del QR>"
 *
 * Sale con 0 si todo va bien y con 1 si algo falla, para que el script lo note.
 */
object ClienteDePrueba {

    private const val TEXTO_DE_IDA = "desde-kotlin-àéî-🐉"
    private const val TEXTO_DE_VUELTA = "desde-csharp-àéî-🐉"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Falta el QR (JSON en base64)")
            kotlin.system.exitProcess(1)
        }

        // El QR llega en base64 y no como JSON tal cual: al pasar --args a Gradle, las
        // comillas dobles se pierden por el camino y el JSON llega roto.
        val json = String(java.util.Base64.getDecoder().decode(args[0]), Charsets.UTF_8)

        try {
            ejecutar(DatosQr.leer(json))
            println("RESULTADO: OK")
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            System.err.println("RESULTADO: FALLO -> ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    private fun ejecutar(qr: DatosQr) {
        val identidad = Cripto.generarParDeClaves()
        val deviceId = Cripto.aleatorio(16).aHex()

        // ---------------------------------------------------------- Emparejar
        val emparejado = Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(qr.ip, qr.port), 5_000)
            socket.soTimeout = 10_000

            Emparejamiento.iniciar(
                entrada = socket.getInputStream(),
                salida = socket.getOutputStream(),
                miPrivada = identidad.privada,
                miDeviceId = deviceId,
                miNombre = "Cliente Kotlin de prueba",
                qr = qr,
            )
        }

        println("EMPAREJADO con ${emparejado.nombreRemoto}")
        println("HUELLA: ${emparejado.huella}")

        // ------------------------------------------------------- Abrir sesión
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(qr.ip, qr.port), 5_000)
            socket.soTimeout = 15_000

            val entrada = socket.getInputStream()
            val salida = socket.getOutputStream()

            val sesion = Handshake.iniciar(
                entrada = entrada,
                salida = salida,
                miDeviceId = deviceId,
                deviceIdEsperado = qr.deviceId,
                clavePar = emparejado.clavePar,
            )
            println("SESION ABIERTA con ${sesion.deviceIdRemoto}")

            // Kotlin -> C#
            Framing.escribir(salida, sesion.saliente.sellar(CodecMensajes.codificar(Clip.deTexto(TEXTO_DE_IDA))))
            salida.flush()
            println("ENVIADO: $TEXTO_DE_IDA")

            // C# -> Kotlin. Puede llegar antes algún PING del servidor: se contesta y se
            // sigue esperando, igual que hace el cliente de verdad.
            while (true) {
                val mensaje = try {
                    CodecMensajes.decodificar(sesion.entrante.abrir(Framing.leer(entrada)))
                } catch (e: EOFException) {
                    error("El servidor cerró la conexión antes de devolver el clip")
                }

                when (mensaje) {
                    is Clip -> {
                        check(mensaje.texto() == TEXTO_DE_VUELTA) {
                            "El clip de vuelta llegó cambiado: '${mensaje.texto()}'"
                        }
                        check(mensaje.originId == Clip.origenDe(TEXTO_DE_VUELTA)) {
                            "El origin_id no coincide: el anti-eco no funcionaría entre los dos lados"
                        }
                        println("RECIBIDO: ${mensaje.texto()}")
                        return
                    }

                    is com.marcmayol.dracpaste.protocolo.mensajes.Ping -> {
                        Framing.escribir(
                            salida,
                            sesion.saliente.sellar(
                                CodecMensajes.codificar(
                                    com.marcmayol.dracpaste.protocolo.mensajes.Pong(seq = mensaje.seq),
                                ),
                            ),
                        )
                        salida.flush()
                        println("PONG a ${mensaje.seq}")
                    }

                    else -> println("IGNORADO: ${mensaje.t}")
                }
            }
        }
    }
}
