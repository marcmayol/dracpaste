package com.marcmayol.dracpaste.datos

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.sesion.DatosQr
import com.marcmayol.dracpaste.protocolo.sesion.Emparejamiento
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Emparejar este móvil con un PC a partir del contenido de su QR.
 *
 * El diálogo en sí vive en `:protocolo`; aquí solo está lo que hace falta en Android:
 * abrir el socket, poner un plazo y guardar el resultado.
 */
class EmparejarConPc(
    private val almacen: AlmacenIdentidad,
    private val registro: RegistroPcs,
) {

    /**
     * @param textoDelQr el JSON que muestra el PC, escaneado o pegado.
     * @return el PC emparejado, o el motivo del fallo.
     */
    suspend fun emparejar(textoDelQr: String): Resultado = withContext(Dispatchers.IO) {
        val qr = try {
            DatosQr.leer(textoDelQr.trim())
        } catch (e: Exception) {
            return@withContext Resultado.Fallo(e.message ?: "Ese código no es de DracPaste")
        }

        val identidad = almacen.cargarOCrear()

        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(qr.ip, qr.port), TIMEOUT_CONEXION_MS)
                socket.soTimeout = Protocolo.TIMEOUT_HANDSHAKE_MS.toInt()

                val resultado = Emparejamiento.iniciar(
                    entrada = socket.getInputStream(),
                    salida = socket.getOutputStream(),
                    miPrivada = identidad.par.privada,
                    miDeviceId = identidad.deviceId,
                    miNombre = identidad.nombre,
                    qr = qr,
                )

                val pc = PcEmparejado(
                    deviceId = resultado.deviceIdRemoto,
                    nombre = resultado.nombreRemoto,
                    publicaBase64 = android.util.Base64.encodeToString(
                        resultado.publicaRemota,
                        android.util.Base64.NO_WRAP,
                    ),
                    huella = resultado.huella,
                    ultimaIp = qr.ip,
                    ultimoPuerto = qr.port,
                )

                registro.guardar(pc)
                Resultado.Emparejado(pc)
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Un timeout con el PC visible en la red casi siempre es el firewall: los
            // paquetes llegan y nadie contesta. Decir solo "¿estáis en la misma red?"
            // manda al usuario a revisar el WiFi, que suele estar perfectamente.
            Resultado.Fallo(
                "El PC no responde en ${qr.ip}:${qr.port}.\n\n" +
                    "Si DracPaste está abierto en el PC y los dos estáis en la misma red, " +
                    "casi seguro lo está bloqueando el firewall de Windows: abre el menú de " +
                    "DracPaste en el PC y pulsa «Permitir en el firewall de Windows…».",
            )
        } catch (e: java.net.ConnectException) {
            Resultado.Fallo(
                "El PC rechaza la conexión en ${qr.ip}:${qr.port}. " +
                    "¿Sigue abierto DracPaste en el PC?",
            )
        } catch (e: Exception) {
            // El caso más común aquí es un token caducado: el PC corta sin contestar
            // para no confirmarle nada a quien no ha visto el QR.
            Resultado.Fallo(
                e.message ?: "El emparejamiento falló. Vuelve a abrir la ventana en el PC e inténtalo otra vez.",
            )
        }
    }

    sealed interface Resultado {
        data class Emparejado(val pc: PcEmparejado) : Resultado
        data class Fallo(val motivo: String) : Resultado
    }

    private companion object {
        const val TIMEOUT_CONEXION_MS = 5_000
    }
}
