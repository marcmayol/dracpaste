package com.marcmayol.dracpaste.servicio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marcmayol.dracpaste.R
import com.marcmayol.dracpaste.datos.AlmacenIdentidad
import com.marcmayol.dracpaste.datos.Preferencias
import com.marcmayol.dracpaste.datos.RegistroPcs
import com.marcmayol.dracpaste.portapapeles.ActividadCaptura
import com.marcmayol.dracpaste.portapapeles.GestorPortapapeles
import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import com.marcmayol.dracpaste.protocolo.sesion.AntiEco
import com.marcmayol.dracpaste.protocolo.sesion.EstadoConexion
import com.marcmayol.dracpaste.red.ClienteDracPaste
import com.marcmayol.dracpaste.red.DescubridorNsd
import com.marcmayol.dracpaste.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * El servicio que mantiene viva la conexión con el PC.
 *
 * Es un foreground service de tipo `connectedDevice`: sin él, Android mataría el proceso
 * en cuanto la app dejara de estar en pantalla y el portapapeles solo se sincronizaría
 * con la app abierta, que no sirve de nada.
 *
 * La notificación persistente no es un peaje del sistema: es la única forma de que el
 * usuario sepa, de un vistazo, si su portapapeles está compartido o no.
 */
class ServicioDracPaste : LifecycleService() {

    private lateinit var registro: RegistroPcs
    private lateinit var cliente: ClienteDracPaste
    private lateinit var portapapeles: GestorPortapapeles
    private lateinit var vigilanteDeRed: VigilanteDeRed

    /**
     * Se lee en cada evento, no una vez al arrancar: el usuario puede pausar la
     * sincronización desde la app mientras el servicio ya está en marcha.
     */
    private val preferencias: Preferencias by lazy { Preferencias(this) }

    /**
     * Compartido con el resto de la app: la misma instancia que consultará la activity
     * de captura de la Fase 3 antes de reenviar lo que lea.
     */
    private val antiEco = AntiEco()

    private var ultimoEstado: EstadoConexion = EstadoConexion.SIN_EMPAREJAR
    private var ultimoDetalle: String? = null

    /** Clip que el sistema no dejó escribir y espera a que el usuario toque la acción. */
    private var clipPendienteDePegar: String? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()

        val identidad = AlmacenIdentidad(this).cargarOCrear()
        registro = RegistroPcs(this)
        portapapeles = GestorPortapapeles(this)
        cliente = ClienteDracPaste(identidad, DescubridorNsd(this), lifecycleScope)
        vigilanteDeRed = VigilanteDeRed(this).also { it.empezar() }

        cliente.alRecibirClip = { clip -> escribirClipRecibido(clip) }

        cliente.alLocalizarPc = { deviceId, ip, puerto ->
            registro.recordarDireccion(deviceId, ip, puerto)
        }

        cliente.alDesemparejar = { deviceId ->
            registro.olvidar(deviceId)
            cliente.detener()
            notificar(null)
        }

        lifecycleScope.launch {
            combine(cliente.estado, cliente.detalle) { estado, detalle -> estado to detalle }
                .collect { (estado, detalle) ->
                    ultimoEstado = estado
                    ultimoDetalle = detalle
                    notificar(null)
                }
        }

        // El tipo connectedDevice es obligatorio declararlo desde Android 14; sin él,
        // el sistema tira el servicio nada más arrancar.
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICACION,
            construirNotificacion(null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        conectarConElPcActivo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACCION_RECONECTAR -> cliente.despertar()
            ACCION_RED_CAMBIADA -> cliente.redCambiada()
            ACCION_RELEER_EMPAREJAMIENTO -> conectarConElPcActivo()
            ACCION_CLIP_PEGADO -> {
                // El usuario ya ha pegado el clip que el sistema no dejó escribir: la
                // notificación vuelve a su estado normal.
                clipPendienteDePegar = null
                notificar(null)
            }

            ACCION_ENVIAR_TEXTO -> {
                val texto = intent.getStringExtra(EXTRA_TEXTO)
                if (!texto.isNullOrEmpty()) {
                    enviarAlPc(texto)
                }
            }

            ACCION_RELEER_AJUSTES -> notificar(null)
        }

        // START_STICKY: si el sistema mata el proceso por memoria, que lo vuelva a
        // levantar. Es lo que hace que la sincronización se recupere sola.
        return START_STICKY
    }

    /**
     * Escribe en el portapapeles un clip que llega del PC.
     *
     * Si el fabricante lo impide desde segundo plano —pasa en algunos OEM—, no se pierde
     * el clip: se guarda y la notificación ofrece «Toca para pegar», que abre la activity
     * invisible y lo escribe con el foco puesto (`PLAN.md` §7).
     */
    private fun escribirClipRecibido(clip: Clip) {
        if (preferencias.pausado) {
            // Pausado significa pausado en las dos direcciones: lo que llega tampoco se
            // escribe. El anti-eco tampoco se toca, porque no ha habido escritura que
            // pueda rebotar.
            return
        }

        // Se anota **antes** de escribir: en cuanto se escriba, el portapapeles cambia, y
        // si la marca no estuviera ya puesta, el siguiente envío devolvería este mismo
        // clip al PC y empezaría el bucle.
        antiEco.marcarRecibido(clip.originId)

        val texto = clip.texto()
        if (portapapeles.escribir(texto)) {
            clipPendienteDePegar = null
            val nombrePc = registro.activo()?.nombre ?: "el PC"
            notificar("Recibido de $nombrePc")
            if (preferencias.avisarAlRecibir) {
                Toast.makeText(this, "Copiado de $nombrePc", Toast.LENGTH_SHORT).show()
            }
        } else {
            clipPendienteDePegar = texto
            notificar(null)
        }
    }

    /**
     * Envía al PC un texto que el usuario ha capturado o compartido.
     *
     * **Sin cola** (`docs/protocol.md` §8): si no hay conexión, no se guarda nada
     * pendiente y se dice claramente. Guardar clips para "más tarde" significaría que
     * algo copiado hace horas aparezca de pronto en el PC cuando el usuario ya no se
     * acuerda, que es peor que no enviarlo.
     */
    private fun enviarAlPc(texto: String) {
        if (preferencias.pausado) {
            Toast.makeText(this, "La sincronización está pausada", Toast.LENGTH_SHORT).show()
            return
        }

        // El anti-eco es el mismo objeto que usa la recepción: si esto es el eco de un
        // clip que el PC acaba de mandar, no vuelve.
        if (!antiEco.debeReenviar(Clip.origenDe(texto))) {
            return
        }

        // El tamaño se comprueba aquí y no en el envío para poder decir la verdad: un
        // clip demasiado grande no es un problema de conexión, y avisar de lo segundo
        // mandaría al usuario a mirar el WiFi para nada.
        val bytes = texto.toByteArray(Charsets.UTF_8).size
        if (bytes > Protocolo.MAX_CLIP_BYTES) {
            val aviso = "Ese texto ocupa demasiado (${bytes / 1024} KB) y no se ha enviado"
            notificar(aviso)
            Toast.makeText(this, aviso, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val nombrePc = registro.activo()?.nombre ?: "el PC"

            val enviado = try {
                cliente.enviarClip(texto)
            } catch (e: Exception) {
                false
            }

            val aviso = if (enviado) "Enviado a $nombrePc" else "Sin conexión con $nombrePc"
            notificar(aviso)
            Toast.makeText(this@ServicioDracPaste, aviso, Toast.LENGTH_SHORT).show()
        }
    }

    private fun conectarConElPcActivo() {
        val identidad = AlmacenIdentidad(this).cargarOCrear()
        val pc = registro.activo()

        if (pc == null) {
            cliente.detener()
            notificar(null)
            return
        }

        cliente.arrancar(pc, registro.claveParDe(pc, identidad))
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        vigilanteDeRed.parar()
        cliente.detener()
        super.onDestroy()
    }

    // --------------------------------------------------------- Notificación

    private fun crearCanal() {
        val gestor = getSystemService(NotificationManager::class.java)
        val canal = NotificationChannel(
            CANAL,
            "Conexión con el PC",
            // IMPORTANCE_LOW: la notificación tiene que estar ahí para informar, pero
            // sin sonar ni vibrar cada vez que cambia el estado.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Muestra si el portapapeles está compartido con tu PC"
            setShowBadge(false)
        }
        gestor.createNotificationChannel(canal)
    }

    private fun notificar(mensajeExtra: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(ID_NOTIFICACION, construirNotificacion(mensajeExtra))
    }

    /** El estado de la conexión, tal como lo lee el usuario. */
    private fun tituloDelEstado(nombrePc: String?): String = when (ultimoEstado) {
        EstadoConexion.SIN_EMPAREJAR -> "Sin emparejar"
        EstadoConexion.BUSCANDO -> "Buscando ${nombrePc ?: "tu PC"}"
        EstadoConexion.CONECTANDO -> "Conectando con ${nombrePc ?: "tu PC"}"
        EstadoConexion.CONECTADO -> "Conectado con ${nombrePc ?: "tu PC"}"
        EstadoConexion.RECONECTANDO -> "Reconectando con ${nombrePc ?: "tu PC"}"
    }

    private fun construirNotificacion(mensajeExtra: String?): Notification {
        val nombrePc = registro.activo()?.nombre

        val titulo = when {
            // La pausa manda sobre el estado de red: el usuario tiene que ver que no
            // sincroniza porque él lo ha pedido, no porque algo esté roto.
            preferencias.pausado -> "Sincronización en pausa"
            else -> tituloDelEstado(nombrePc)
        }

        val texto = mensajeExtra
            ?: ultimoDetalle
            ?: when {
                preferencias.pausado -> "Nada sale ni entra hasta que la reanudes"
                ultimoEstado == EstadoConexion.SIN_EMPAREJAR -> "Toca para emparejar un PC"
                // Se nombra el gesto: decir solo que lo del PC llega aquí hace pensar que
                // la otra dirección también es automática, y entonces uno copia en el
                // móvil, no pasa nada y parece que la app está rota.
                ultimoEstado == EstadoConexion.CONECTADO ->
                    "Lo del PC llega solo · para enviar lo tuyo, despliega y toca Enviar"
                else -> "Sin conexión con el PC"
            }

        val abrir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val textoFinal = if (clipPendienteDePegar != null) resumir(clipPendienteDePegar!!) else texto

        val constructor = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(if (clipPendienteDePegar != null) "Clip recibido, toca para pegarlo" else titulo)
            .setContentText(textoFinal)
            // Colapsada, la notificación corta el texto por donde le cabe; con BigText se
            // lee entero al desplegarla, que es justo cuando aparece el botón de enviar.
            .setStyle(NotificationCompat.BigTextStyle().bigText(textoFinal))
            .setContentIntent(abrir)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // El botón que resuelve la asimetría de Android: como la app no puede leer el
        // portapapeles sin foco, el usuario da un toque y una ventana invisible lo hace
        // por él. Solo tiene sentido si hay un PC al que enviar.
        if (registro.activo() != null) {
            constructor.addAction(
                R.drawable.ic_notificacion,
                "Enviar portapapeles",
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, ActividadCaptura::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        // Solo aparece cuando el sistema no ha dejado escribir en segundo plano: en el
        // caso normal, el texto ya está en el portapapeles y este botón sobraría.
        if (clipPendienteDePegar != null) {
            constructor.addAction(
                R.drawable.ic_notificacion,
                "Pegar",
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, ActividadPegar::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra(ActividadPegar.EXTRA_TEXTO, clipPendienteDePegar)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        return constructor.build()
    }

    private fun resumir(texto: String): String {
        val unaLinea = texto.replace(Regex("\\s+"), " ").trim()
        return if (unaLinea.length <= 60) "Recibido: $unaLinea" else "Recibido: ${unaLinea.take(57)}…"
    }

    companion object {
        private const val CANAL = "conexion"
        private const val ID_NOTIFICACION = 1

        const val ACCION_RECONECTAR = "com.marcmayol.dracpaste.RECONECTAR"
        const val ACCION_RED_CAMBIADA = "com.marcmayol.dracpaste.RED_CAMBIADA"
        const val ACCION_RELEER_EMPAREJAMIENTO = "com.marcmayol.dracpaste.RELEER_EMPAREJAMIENTO"
        const val ACCION_CLIP_PEGADO = "com.marcmayol.dracpaste.CLIP_PEGADO"
        const val ACCION_ENVIAR_TEXTO = "com.marcmayol.dracpaste.ENVIAR_TEXTO"
        const val ACCION_RELEER_AJUSTES = "com.marcmayol.dracpaste.RELEER_AJUSTES"

        const val EXTRA_TEXTO = "texto"

        fun arrancar(contexto: Context, accion: String? = null, texto: String? = null) {
            val intent = Intent(contexto, ServicioDracPaste::class.java).apply {
                if (accion != null) this.action = accion
                if (texto != null) putExtra(EXTRA_TEXTO, texto)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contexto.startForegroundService(intent)
            } else {
                contexto.startService(intent)
            }
        }

        fun parar(contexto: Context) {
            contexto.stopService(Intent(contexto, ServicioDracPaste::class.java))
        }
    }
}
