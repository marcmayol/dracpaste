package com.marcmayol.dracpaste.red

import android.util.Log
import com.marcmayol.dracpaste.datos.Identidad
import com.marcmayol.dracpaste.datos.PcEmparejado
import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.mensajes.Bye
import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.mensajes.Mensaje
import com.marcmayol.dracpaste.protocolo.mensajes.Ping
import com.marcmayol.dracpaste.protocolo.mensajes.Pong
import com.marcmayol.dracpaste.protocolo.mensajes.Unpair
import com.marcmayol.dracpaste.protocolo.red.Framing
import com.marcmayol.dracpaste.protocolo.sesion.EstadoConexion
import com.marcmayol.dracpaste.protocolo.sesion.Evento
import com.marcmayol.dracpaste.protocolo.sesion.Handshake
import com.marcmayol.dracpaste.protocolo.sesion.MaquinaEstados
import com.marcmayol.dracpaste.protocolo.sesion.SesionEstablecida
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Mantiene la conexión del móvil con su PC activo: descubrir, conectar, autenticar,
 * mantener viva la sesión y reconectar cuando se cae.
 *
 * La política de reintentos está en [MaquinaEstados], en el módulo JVM puro, para poder
 * probarla sin un móvil. Aquí solo vive lo que necesita Android de verdad: sockets, NSD
 * y corrutinas.
 */
class ClienteDracPaste(
    private val identidad: Identidad,
    private val descubridor: DescubridorNsd,
    private val ambito: CoroutineScope,
) {
    private val maquina = MaquinaEstados()
    private val turnoDeEscritura = Mutex()

    private val _estado = MutableStateFlow(EstadoConexion.SIN_EMPAREJAR)

    /** Estado de la conexión, para reflejarlo en la notificación. */
    val estado: StateFlow<EstadoConexion> = _estado.asStateFlow()

    private val _detalle = MutableStateFlow<String?>(null)

    /** Motivo del último problema, si lo hubo. */
    val detalle: StateFlow<String?> = _detalle.asStateFlow()

    private var pcActivo: PcEmparejado? = null
    private var clavePar: ByteArray? = null
    private var socket: Socket? = null
    private var sesion: SesionEstablecida? = null
    private var trabajo: Job? = null

    /** Se llama al recibir un clip del PC. */
    var alRecibirClip: ((Clip) -> Unit)? = null

    /** Se llama si el PC manda UNPAIR. */
    var alDesemparejar: ((String) -> Unit)? = null

    /**
     * Se llama al localizar el PC por mDNS, para que quien corresponda guarde la
     * dirección. Es lo que permite atacar la última IP conocida en el siguiente arranque
     * sin esperar a que mDNS conteste.
     */
    var alLocalizarPc: ((deviceId: String, ip: String, puerto: Int) -> Unit)? = null

    /** Arranca el ciclo de conexión contra este PC. */
    fun arrancar(pc: PcEmparejado, clavePar: ByteArray) {
        detener()
        this.pcActivo = pc
        this.clavePar = clavePar

        aplicar(Evento.Emparejado)
        trabajo = ambito.launch(Dispatchers.IO) { bucleDeConexion(pc) }
    }

    /**
     * El bucle de vida de la conexión.
     *
     * Mientras no haya sesión, se hacen dos cosas a la vez, como manda §8: reintentar
     * contra la última IP conocida con backoff, y tener mDNS activo por si la IP cambió.
     * Gana lo primero que funcione.
     */
    private suspend fun bucleDeConexion(pc: PcEmparejado) {
        var ultimaIp = pc.ultimaIp
        var ultimoPuerto = pc.ultimoPuerto

        descubridor.buscar(pc.deviceId) { ip, puerto ->
            ultimaIp = ip
            ultimoPuerto = puerto
            alLocalizarPc?.invoke(pc.deviceId, ip, puerto)
        }

        while (ambito.isActive && trabajo?.isCancelled != true) {
            val ip = ultimaIp
            val puerto = ultimoPuerto

            if (ip == null || puerto <= 0) {
                // Todavía no se ha visto al PC. Modo pasivo: solo escuchar mDNS, sin
                // reintentos, para no gastar batería llamando a nadie.
                aplicar(Evento.Reintentar)
                delay(ESPERA_SIN_DIRECCION_MS)
                continue
            }

            aplicar(Evento.PcLocalizado(ip, puerto))

            try {
                conectarYAtender(pc, ip, puerto)
                // Si vuelve de aquí sin excepción, la sesión terminó de forma ordenada.
                aplicar(Evento.ConexionPerdida("La sesión se cerró"))
            } catch (e: Exception) {
                Log.d(TAG, "Conexión fallida contra $ip:$puerto", e)
                aplicar(Evento.ConexionFallida(motivoLegible(e)))
            } finally {
                cerrarSocket()
            }

            delay(maquina.esperaSiguienteIntento())
        }
    }

    private suspend fun conectarYAtender(pc: PcEmparejado, ip: String, puerto: Int) {
        val nuevoSocket = Socket()
        socket = nuevoSocket

        withContext(Dispatchers.IO) {
            nuevoSocket.tcpNoDelay = true // Un clip es pequeño: esperar a llenar el buffer solo añade latencia.
            nuevoSocket.connect(InetSocketAddress(ip, puerto), TIMEOUT_CONEXION_MS)
            // Mientras dura el handshake hay un plazo; después se quita, porque una
            // sesión sana puede estar horas sin recibir nada y quien decide que ha
            // muerto es el PING, no una lectura sin datos.
            nuevoSocket.soTimeout = Protocolo.TIMEOUT_HANDSHAKE_MS.toInt()
        }

        val entrada = nuevoSocket.getInputStream()
        val salida = nuevoSocket.getOutputStream()

        val establecida = withContext(Dispatchers.IO) {
            Handshake.iniciar(
                entrada = entrada,
                salida = salida,
                miDeviceId = identidad.deviceId,
                deviceIdEsperado = pc.deviceId,
                clavePar = clavePar ?: error("Sin clave de par"),
            )
        }

        sesion = establecida
        nuevoSocket.soTimeout = 0
        aplicar(Evento.SesionEstablecida)

        val latido = ambito.launch(Dispatchers.IO) { latido() }
        try {
            bucleDeLectura(entrada)
        } finally {
            latido.cancel()
            establecida.limpiar()
            sesion = null
        }
    }

    private suspend fun bucleDeLectura(entrada: java.io.InputStream) {
        val actual = sesion ?: return

        while (ambito.isActive) {
            val frame = withContext(Dispatchers.IO) { Framing.leer(entrada) }
            val mensaje = CodecMensajes.decodificar(actual.entrante.abrir(frame))

            when (mensaje) {
                is Clip -> if (mensaje.esTexto()) {
                    alRecibirClip?.invoke(mensaje)
                }
                // Un tipo que esta versión no transporta (imágenes, en v2) se ignora y
                // la sesión sigue.

                is Ping -> enviar(Pong(seq = mensaje.seq))

                is Pong -> Unit

                is Bye -> return

                is Unpair -> {
                    alDesemparejar?.invoke(actual.deviceIdRemoto)
                    return
                }

                else -> Unit // Mensaje de una versión más nueva: se ignora.
            }
        }
    }

    /**
     * PING cada 15 s. Sin PONG en 10 s, la conexión se da por muerta.
     *
     * Hace falta porque un socket TCP puede quedarse "abierto" varios minutos después de
     * que el PC haya desaparecido de la red: en ese tiempo, los clips que el usuario
     * copiara se perderían sin que nada lo indicara.
     */
    private suspend fun latido() {
        var seq = 0L
        var esperandoDesde = 0L

        while (ambito.isActive) {
            delay(Protocolo.INTERVALO_PING_MS)

            if (esperandoDesde != 0L &&
                System.currentTimeMillis() - esperandoDesde > Protocolo.TIMEOUT_PONG_MS
            ) {
                cerrarSocket()
                return
            }

            try {
                esperandoDesde = System.currentTimeMillis()
                enviar(Ping(seq = ++seq))
            } catch (e: Exception) {
                cerrarSocket()
                return
            }
        }
    }

    /** Envía un mensaje al PC. Devuelve si salió. */
    suspend fun enviar(mensaje: Mensaje): Boolean {
        val actual = sesion ?: return false
        val salida = socket?.getOutputStream() ?: return false

        return try {
            turnoDeEscritura.withLock {
                withContext(Dispatchers.IO) {
                    // El contador del sobre saliente no es seguro entre hilos: dos
                    // envíos a la vez repetirían un nonce y arruinarían la sesión.
                    Framing.escribir(salida, actual.saliente.sellar(CodecMensajes.codificar(mensaje)))
                    salida.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo enviar", e)
            cerrarSocket()
            false
        }
    }

    /** Envía un clip si hay conexión. Sin cola: si no la hay, se pierde y se avisa (§8). */
    suspend fun enviarClip(texto: String): Boolean {
        if (!maquina.puedeEnviar()) return false
        return enviar(Clip.deTexto(texto))
    }

    /** El móvil ha cambiado de red: lo que hubiera ya no sirve. */
    fun redCambiada() {
        aplicar(Evento.RedCambiada)
        cerrarSocket()
    }

    /** La pantalla se ha encendido: buen momento para reintentar (mitiga Doze). */
    fun despertar() {
        if (maquina.estado == EstadoConexion.RECONECTANDO || maquina.estado == EstadoConexion.BUSCANDO) {
            aplicar(Evento.Reintentar)
        }
    }

    fun detener() {
        trabajo?.cancel()
        trabajo = null
        descubridor.detener()
        cerrarSocket()
        aplicar(Evento.Desemparejado)
    }

    private fun cerrarSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Cerrar un socket ya muerto no es un problema.
        }
        socket = null
    }

    private fun aplicar(evento: Evento) {
        _estado.value = maquina.procesar(evento)
        _detalle.value = maquina.ultimoMotivo
    }

    /** Traduce la excepción a algo que el usuario pueda leer en la notificación. */
    private fun motivoLegible(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "El PC no contesta"
        is java.net.ConnectException -> "El PC rechazó la conexión"
        is java.net.NoRouteToHostException -> "No se llega al PC en esta red"
        is java.io.EOFException -> "El PC cerró la conexión"
        else -> e.message ?: "Fallo de conexión"
    }

    private companion object {
        const val TAG = "DracPaste.Cliente"
        const val TIMEOUT_CONEXION_MS = 5_000
        const val ESPERA_SIN_DIRECCION_MS = 3_000L
    }
}
