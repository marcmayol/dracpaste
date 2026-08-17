package com.marcmayol.dracpaste.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.Modo
import com.marcmayol.dracpaste.DracPasteApp
import com.marcmayol.dracpaste.datos.AlmacenIdentidad
import com.marcmayol.dracpaste.datos.DesemparejarPc
import com.marcmayol.dracpaste.datos.EmparejarConPc
import com.marcmayol.dracpaste.datos.PcEmparejado
import com.marcmayol.dracpaste.datos.RegistroPcs
import com.marcmayol.dracpaste.portapapeles.GestorPortapapeles
import com.marcmayol.dracpaste.servicio.ServicioDracPaste
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val actualizador get() = (application as DracPasteApp).actualizador

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // De borde a borde, pero con los márgenes puestos abajo: sin esto el título
        // quedaba pisando el reloj de la barra de estado.
        enableEdgeToEdge()

        lifecycleScope.launch {
            actualizador.comprobar(Modo.AUTOMATICO)
        }

        setContent {
            TemaDracPaste {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Pantalla(actualizador)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de los ajustes del sistema, quizá el usuario acaba de conceder el
        // permiso de instalar aplicaciones: hay que retomar donde se quedó.
        actualizador.onPermisoQuizaConcedido()
    }
}

@Composable
private fun Pantalla(actualizador: Actualizador) {
    val contexto = LocalContext.current
    val ambito = rememberCoroutineScope()
    val estadoActualizacion by actualizador.estado.collectAsStateWithLifecycle()

    val registro = remember { RegistroPcs(contexto) }
    val portapapeles = remember { GestorPortapapeles(contexto) }
    val almacen = remember { AlmacenIdentidad(contexto) }
    val emparejador = remember { EmparejarConPc(almacen, registro) }
    val desemparejador = remember { DesemparejarPc(almacen, registro) }

    var pcs by remember { mutableStateOf(registro.todos()) }
    var escaneando by remember { mutableStateOf(false) }
    var textoManual by remember { mutableStateOf("") }
    var mostrandoTextoManual by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var aDesemparejar by remember { mutableStateOf<PcEmparejado?>(null) }
    var recienEmparejado by remember { mutableStateOf<PcEmparejado?>(null) }
    var enAjustes by remember { mutableStateOf(false) }

    val pedirNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            ServicioDracPaste.arrancar(contexto)
        } else {
            aviso = "Sin permiso de notificaciones, DracPaste no puede mantener la conexión " +
                "en segundo plano ni ofrecerte el botón de enviar."
        }
    }

    val pedirCamara = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            escaneando = true
        } else {
            mostrandoTextoManual = true
            aviso = "Sin cámara puedes emparejar igualmente pegando el texto que muestra el PC."
        }
    }

    fun emparejarCon(texto: String) {
        trabajando = true
        escaneando = false
        aviso = null
        ambito.launch {
            when (val resultado = emparejador.emparejar(texto)) {
                is EmparejarConPc.Resultado.Emparejado -> {
                    textoManual = ""
                    mostrandoTextoManual = false
                    pcs = registro.todos()

                    // No es un aviso que se despacha con «Entendido»: la huella es lo
                    // único que impide que alguien se haya colado en medio, y si no
                    // coincide hay que poder deshacerlo desde aquí mismo.
                    recienEmparejado = resultado.pc

                    // Con la acción de releer, no a secas: si el servicio ya estaba en
                    // marcha —lo normal, porque se arranca al abrir la app—, un
                    // startService sin acción no le dice nada y se queda como estaba.
                    // El resultado era emparejar y que no pasara nada: la notificación
                    // seguía diciendo «Sin emparejar» y nunca llegaba a conectar.
                    arrancarServicioSiSePuede(
                        contexto,
                        pedirNotificaciones::launch,
                        ServicioDracPaste.ACCION_RELEER_EMPAREJAMIENTO,
                    )
                }

                is EmparejarConPc.Resultado.Fallo -> aviso = resultado.motivo
            }
            trabajando = false
        }
    }

    /**
     * Lee el portapapeles y lo manda al PC activo.
     *
     * Se llama desde la pantalla y no desde el servicio a propósito: leer el portapapeles
     * exige foco de pantalla (Android 10+), y aquí lo hay.
     */
    fun enviarLoCopiado() {
        when (val leido = portapapeles.leer()) {
            is GestorPortapapeles.ResultadoLectura.Texto ->
                ServicioDracPaste.arrancar(
                    contexto,
                    ServicioDracPaste.ACCION_ENVIAR_TEXTO,
                    leido.contenido,
                )

            GestorPortapapeles.ResultadoLectura.Vacio ->
                aviso = "No hay nada copiado que enviar. Copia algo primero y vuelve."

            // No se envía ni se pregunta: es la regla de la casa, y decirlo aquí explica
            // por qué la app no hace lo que el usuario acaba de pedirle.
            GestorPortapapeles.ResultadoLectura.Sensible ->
                aviso = "Eso lo ha copiado un gestor de contraseñas y DracPaste no lo " +
                    "sincroniza nunca, ni siquiera cifrado."
        }
    }

    LaunchedEffect(Unit) {
        if (pcs.isNotEmpty()) {
            arrancarServicioSiSePuede(contexto, pedirNotificaciones::launch)
        }
    }

    if (enAjustes) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
        ) {
            PantallaAjustes(alVolver = { enAjustes = false })
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // El padding de los insets va antes del scroll y antes del margen propio: si
            // se pone después, el contenido se desplaza pero el fondo no llega al borde.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DracPaste", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tu portapapeles compartido con el PC, sin nube y sin cuenta.",
            style = MaterialTheme.typography.bodyMedium,
        )

        BannerActualizacion(
            estado = estadoActualizacion,
            onActualizar = { actualizador.actualizarAhora() },
        )

        if (escaneando) {
            SeccionEscaner(
                alLeer = ::emparejarCon,
                alCancelar = { escaneando = false },
            )
        } else {
            // La pieza que resuelve la asimetría: aquí la app está en primer plano, así
            // que **sí** puede leer el portapapeles. El botón de la notificación tenía que
            // abrir una activity invisible para conseguir el foco; desde esta pantalla no
            // hace falta nada de eso.
            pcs.firstOrNull { it.activo }?.let { activo ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Enviar lo copiado al PC", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Lo que copies en el PC llega solo. Al revés no: Android no deja " +
                                "leer el portapapeles a una app que no estás mirando, así que " +
                                "lo tuyo sale con un toque.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { enviarLoCopiado() },
                            enabled = !trabajando,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Enviar a ${activo.nombre}")
                        }
                    }
                }
            }

            SeccionPcs(
                pcs = pcs,
                alActivar = { pc ->
                    registro.marcarActivo(pc.deviceId)
                    pcs = registro.todos()
                    ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RELEER_EMPAREJAMIENTO)
                },
                alDesemparejar = { aDesemparejar = it },
            )

            HorizontalDivider()

            Text("Emparejar un PC", style = MaterialTheme.typography.titleMedium)
            Text(
                "En el PC, abre DracPaste desde la bandeja y pulsa «Emparejar un móvil». " +
                    "Después, apunta con la cámara al código que aparece.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = { pedirCamaraOEscanear(contexto, pedirCamara::launch) { escaneando = true } },
                enabled = !trabajando,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Escanear el código del PC")
            }

            TextButton(
                onClick = { mostrandoTextoManual = !mostrandoTextoManual },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (mostrandoTextoManual) "Ocultar el texto" else "La cámara no funciona: pegar el texto")
            }

            if (mostrandoTextoManual) {
                OutlinedTextField(
                    value = textoManual,
                    onValueChange = { textoManual = it },
                    label = { Text("Texto del PC") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    enabled = !trabajando,
                )

                Button(
                    onClick = { emparejarCon(textoManual) },
                    enabled = textoManual.isNotBlank() && !trabajando,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Emparejar con este texto")
                }
            }
        }

        if (trabajando) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("  Emparejando…")
            }
        }

        aviso?.let { mensaje ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SelectionContainer { Text(mensaje, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = { aviso = null }) { Text("Entendido") }
                }
            }
        }

        HorizontalDivider()

        TextButton(onClick = { enAjustes = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Ajustes y batería")
        }

        Text(
            "DracPaste no envía nada fuera de tu red local, no guarda historial y nunca " +
                "sincroniza lo que copies desde un gestor de contraseñas.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    recienEmparejado?.let { pc ->
        AlertDialog(
            // Sin descarte al tocar fuera: hay que decir si coinciden o no.
            onDismissRequest = { },
            title = { Text("Emparejado con ${pc.nombre}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Último paso: mira la pantalla del PC. Las dos huellas tienen que ser exactamente esta:")
                    SelectionContainer {
                        Text(
                            pc.huella,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { recienEmparejado = null }) { Text("Coinciden") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val objetivo = pc
                    recienEmparejado = null
                    ambito.launch {
                        desemparejador.desemparejar(objetivo)
                        pcs = registro.todos()
                        ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RELEER_EMPAREJAMIENTO)
                        aviso = "Se ha deshecho el emparejamiento con ${objetivo.nombre}. " +
                            "Si las huellas no coincidían, alguien pudo meterse en medio: " +
                            "vuelve a intentarlo con un código nuevo."
                    }
                }) {
                    Text("No coinciden")
                }
            },
        )
    }

    aDesemparejar?.let { pc ->
        AlertDialog(
            onDismissRequest = { aDesemparejar = null },
            title = { Text("¿Desemparejar ${pc.nombre}?") },
            text = {
                Text(
                    "Este móvil borrará su clave y dejará de conectarse. Si el PC está " +
                        "encendido, borrará la suya también.\n\n" +
                        "Para volver a usarlo habrá que emparejarlo de nuevo con un código.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val objetivo = pc
                    aDesemparejar = null
                    ambito.launch {
                        desemparejador.desemparejar(objetivo)
                        pcs = registro.todos()
                        ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RELEER_EMPAREJAMIENTO)
                        aviso = "${objetivo.nombre} ya no está emparejado."
                    }
                }) {
                    Text("Desemparejar")
                }
            },
            dismissButton = {
                TextButton(onClick = { aDesemparejar = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun SeccionEscaner(alLeer: (String) -> Unit, alCancelar: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Apunta al código del PC", style = MaterialTheme.typography.titleMedium)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            EscanerQr(modifier = Modifier.fillMaxSize(), alLeer = alLeer)
        }

        OutlinedButton(onClick = alCancelar, modifier = Modifier.fillMaxWidth()) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun SeccionPcs(
    pcs: List<PcEmparejado>,
    alActivar: (PcEmparejado) -> Unit,
    alDesemparejar: (PcEmparejado) -> Unit,
) {
    if (pcs.isEmpty()) {
        Text("Ningún PC emparejado", style = MaterialTheme.typography.titleMedium)
        return
    }

    Text("PCs emparejados", style = MaterialTheme.typography.titleMedium)

    if (pcs.size > 1) {
        Text(
            "Los clips van solo al PC activo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    pcs.forEach { pc ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(pc.nombre, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Huella ${pc.huella}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pc.activo) {
                        Text("Destino activo", style = MaterialTheme.typography.labelMedium)
                    } else {
                        TextButton(onClick = { alActivar(pc) }) { Text("Usar este PC") }
                    }
                    TextButton(onClick = { alDesemparejar(pc) }) { Text("Desemparejar") }
                }
            }
        }
    }
}

/**
 * Arranca el servicio, pidiendo antes el permiso de notificaciones si hace falta.
 *
 * En Android 13+ el permiso es obligatorio para mostrar la notificación persistente, y sin
 * notificación persistente no puede haber foreground service.
 */
private fun arrancarServicioSiSePuede(
    contexto: Context,
    pedirPermiso: (String) -> Unit,
    accion: String? = null,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        pedirPermiso(Manifest.permission.POST_NOTIFICATIONS)
        return
    }

    ServicioDracPaste.arrancar(contexto, accion)
}

private fun pedirCamaraOEscanear(
    contexto: Context,
    pedirPermiso: (String) -> Unit,
    escanear: () -> Unit,
) {
    if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        escanear()
    } else {
        pedirPermiso(Manifest.permission.CAMERA)
    }
}
