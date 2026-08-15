package com.marcmayol.dracpaste.datos

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.mensajes.Unpair
import com.marcmayol.dracpaste.protocolo.red.Framing
import com.marcmayol.dracpaste.protocolo.sesion.Handshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Desemparejar un PC desde el móvil.
 *
 * Se intenta avisar al PC con un `UNPAIR` para que borre su clave también, pero **el
 * emparejamiento se borra de este lado pase lo que pase**. Si el PC está apagado o en otra
 * red, no puede quedar en un limbo: el usuario ha dicho que ya no quiere ese PC, y que la
 * app dependiera de alcanzarlo para obedecerle sería absurdo.
 *
 * El PC que no reciba el aviso se enterará al reconectar, cuando su clave deje de servir.
 */
class DesemparejarPc(
    private val almacen: AlmacenIdentidad,
    private val registro: RegistroPcs,
) {

    /** @return `true` si además se pudo avisar al PC. */
    suspend fun desemparejar(pc: PcEmparejado): Boolean {
        val avisado = intentarAvisar(pc)
        registro.olvidar(pc.deviceId)
        return avisado
    }

    private suspend fun intentarAvisar(pc: PcEmparejado): Boolean = withContext(Dispatchers.IO) {
        val ip = pc.ultimaIp ?: return@withContext false
        if (pc.ultimoPuerto <= 0) return@withContext false

        try {
            val identidad = almacen.cargarOCrear()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, pc.ultimoPuerto), TIMEOUT_MS)
                socket.soTimeout = Protocolo.TIMEOUT_HANDSHAKE_MS.toInt()

                // Hay que abrir sesión para poder cifrar el UNPAIR: un mensaje en claro
                // permitiría a cualquiera de la red desemparejar dispositivos ajenos.
                val sesion = Handshake.iniciar(
                    entrada = socket.getInputStream(),
                    salida = socket.getOutputStream(),
                    miDeviceId = identidad.deviceId,
                    deviceIdEsperado = pc.deviceId,
                    clavePar = registro.claveParDe(pc, identidad),
                )

                val salida = socket.getOutputStream()
                Framing.escribir(salida, sesion.saliente.sellar(CodecMensajes.codificar(Unpair())))
                salida.flush()
                sesion.limpiar()
            }
            true
        } catch (e: Exception) {
            // El PC no está o no contesta. No importa: el olvido local sigue adelante.
            false
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000
    }
}
