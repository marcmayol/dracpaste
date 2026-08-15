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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marcmayol.dracpaste.R
import com.marcmayol.dracpaste.datos.AlmacenIdentidad
import com.marcmayol.dracpaste.datos.RegistroPcs
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

    private var ultimoEstado: EstadoConexion = EstadoConexion.SIN_EMPAREJAR
    private var ultimoDetalle: String? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()

        val identidad = AlmacenIdentidad(this).cargarOCrear()
        registro = RegistroPcs(this)
        cliente = ClienteDracPaste(identidad, DescubridorNsd(this), lifecycleScope)

        cliente.alRecibirClip = { clip ->
            // La escritura en el portapapeles llega en la Fase 2. De momento se enseña
            // en la notificación, que es lo que valida el túnel de la Fase 1.
            notificar(resumir(clip.texto()))
        }

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
        }

        // START_STICKY: si el sistema mata el proceso por memoria, que lo vuelva a
        // levantar. Es lo que hace que la sincronización se recupere sola.
        return START_STICKY
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

    private fun construirNotificacion(mensajeExtra: String?): Notification {
        val nombrePc = registro.activo()?.nombre

        val titulo = when (ultimoEstado) {
            EstadoConexion.SIN_EMPAREJAR -> "Sin emparejar"
            EstadoConexion.BUSCANDO -> "Buscando ${nombrePc ?: "tu PC"}"
            EstadoConexion.CONECTANDO -> "Conectando con ${nombrePc ?: "tu PC"}"
            EstadoConexion.CONECTADO -> "Conectado con ${nombrePc ?: "tu PC"}"
            EstadoConexion.RECONECTANDO -> "Reconectando con ${nombrePc ?: "tu PC"}"
        }

        val texto = mensajeExtra
            ?: ultimoDetalle
            ?: when (ultimoEstado) {
                EstadoConexion.SIN_EMPAREJAR -> "Toca para emparejar un PC"
                EstadoConexion.CONECTADO -> "Lo que copies en el PC llegará aquí"
                else -> "Sin conexión con el PC"
            }

        val abrir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val constructor = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

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

        fun arrancar(contexto: Context, accion: String? = null) {
            val intent = Intent(contexto, ServicioDracPaste::class.java).apply {
                if (accion != null) this.action = accion
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
