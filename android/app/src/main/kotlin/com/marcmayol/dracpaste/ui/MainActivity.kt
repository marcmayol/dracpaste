package com.marcmayol.dracpaste.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marcmayol.dracpaste.datos.AlmacenIdentidad
import com.marcmayol.dracpaste.datos.EmparejarConPc
import com.marcmayol.dracpaste.datos.PcEmparejado
import com.marcmayol.dracpaste.datos.RegistroPcs
import com.marcmayol.dracpaste.servicio.ServicioDracPaste
import kotlinx.coroutines.launch

/**
 * La pantalla principal.
 *
 * En la Fase 1 sirve para emparejar pegando el texto que muestra el PC. El escáner de QR
 * llega en la Fase 4 y ocupará el sitio del campo de texto sin cambiar nada más.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Pantalla()
                }
            }
        }
    }
}

@Composable
private fun Pantalla() {
    val contexto = LocalContext.current
    val ambito = rememberCoroutineScope()

    val registro = remember { RegistroPcs(contexto) }
    val almacen = remember { AlmacenIdentidad(contexto) }
    val emparejador = remember { EmparejarConPc(almacen, registro) }

    var pcs by remember { mutableStateOf(registro.todos()) }
    var texto by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val pedirNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            ServicioDracPaste.arrancar(contexto)
        } else {
            // Sin permiso no puede haber notificación persistente, y sin ella el sistema
            // no deja tener el servicio en primer plano: la sincronización moriría en
            // cuanto la app saliera de pantalla.
            aviso = "Sin permiso de notificaciones, DracPaste no puede mantener la conexión en segundo plano."
        }
    }

    LaunchedEffect(Unit) {
        if (pcs.isNotEmpty()) {
            arrancarServicioSiSePuede(contexto, pedirNotificaciones::launch)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DracPaste", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tu portapapeles compartido con el PC, sin nube y sin cuenta.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (pcs.isEmpty()) {
            Text("Ningún PC emparejado", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("PCs emparejados", style = MaterialTheme.typography.titleMedium)
            pcs.forEach { pc ->
                TarjetaPc(pc) {
                    registro.marcarActivo(pc.deviceId)
                    pcs = registro.todos()
                    ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RELEER_EMPAREJAMIENTO)
                }
            }
        }

        Text("Emparejar un PC", style = MaterialTheme.typography.titleMedium)
        Text(
            "En el PC, abre DracPaste desde la bandeja y pulsa «Emparejar un móvil». " +
                "Pega aquí el texto que aparece.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Texto del PC") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = !trabajando,
        )

        Button(
            onClick = {
                trabajando = true
                aviso = null
                ambito.launch {
                    when (val resultado = emparejador.emparejar(texto)) {
                        is EmparejarConPc.Resultado.Emparejado -> {
                            texto = ""
                            pcs = registro.todos()
                            aviso = "Emparejado con ${resultado.pc.nombre}. " +
                                "Comprueba que el PC muestra la misma huella: ${resultado.pc.huella}"
                            arrancarServicioSiSePuede(contexto, pedirNotificaciones::launch)
                        }

                        is EmparejarConPc.Resultado.Fallo -> aviso = resultado.motivo
                    }
                    trabajando = false
                }
            },
            enabled = texto.isNotBlank() && !trabajando,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (trabajando) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (trabajando) "Emparejando…" else "Emparejar")
        }

        aviso?.let { mensaje ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SelectionContainer { Text(mensaje, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = { aviso = null }) { Text("Entendido") }
                }
            }
        }
    }
}

@Composable
private fun TarjetaPc(pc: PcEmparejado, alSeleccionar: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(pc.nombre, style = MaterialTheme.typography.titleSmall)
            Text(
                "Huella ${pc.huella}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (pc.activo) {
                Text("Destino activo", style = MaterialTheme.typography.labelMedium)
            } else {
                TextButton(onClick = alSeleccionar) { Text("Usar este PC") }
            }
        }
    }
}

/**
 * Arranca el servicio, pidiendo antes el permiso de notificaciones si hace falta.
 *
 * En Android 13+ el permiso es obligatorio para mostrar la notificación persistente, y
 * sin notificación persistente no se puede tener un foreground service.
 */
private fun arrancarServicioSiSePuede(
    contexto: android.content.Context,
    pedirPermiso: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        pedirPermiso(Manifest.permission.POST_NOTIFICATIONS)
        return
    }

    ServicioDracPaste.arrancar(contexto)
}
