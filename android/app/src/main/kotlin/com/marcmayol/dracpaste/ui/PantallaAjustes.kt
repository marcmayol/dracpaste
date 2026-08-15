package com.marcmayol.dracpaste.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marcmayol.dracpaste.datos.Preferencias
import com.marcmayol.dracpaste.servicio.ServicioDracPaste

/**
 * Los ajustes del móvil (`PLAN.md` §3.1.5).
 *
 * Dos de ellos —solo LAN y el respeto a los clips sensibles— se muestran como **valores,
 * no como interruptores**. No es un descuido: en v1 no hay relay que activar, y una app
 * cuyo argumento es la privacidad no debería ofrecer un botón para mandar contraseñas por
 * la red aunque alguien lo pidiera. Enseñarlos igualmente sirve para que el usuario sepa
 * qué hace la app, que es de lo que va todo esto.
 */
@Composable
fun PantallaAjustes(alVolver: () -> Unit) {
    val contexto = LocalContext.current
    val preferencias = remember { Preferencias(contexto) }

    var pausado by remember { mutableStateOf(preferencias.pausado) }
    var avisar by remember { mutableStateOf(preferencias.avisarAlRecibir) }
    var exento by remember { mutableStateOf(tieneExencionDeBateria(contexto)) }
    var mostrandoGuia by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)

        AjusteConInterruptor(
            titulo = "Pausar la sincronización",
            explicacion = "Mientras esté pausada, no sale ni entra nada. La conexión con el PC se mantiene.",
            valor = pausado,
            alCambiar = {
                pausado = it
                preferencias.pausado = it
                ServicioDracPaste.arrancar(contexto, ServicioDracPaste.ACCION_RELEER_AJUSTES)
            },
        )

        AjusteConInterruptor(
            titulo = "Avisar al recibir un clip",
            explicacion = "Muestra un aviso breve cada vez que llega algo del PC.",
            valor = avisar,
            alCambiar = {
                avisar = it
                preferencias.avisarAlRecibir = it
            },
        )

        HorizontalDivider()

        Text("Que el servicio no muera", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (exento) "Batería: sin restricciones ✓" else "Batería: con restricciones",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (exento) {
                        "Android no matará la conexión por ahorro de batería."
                    } else {
                        "Android puede cortar la conexión con la pantalla apagada. " +
                            "DracPaste necesita esta excepción para que el portapapeles siga " +
                            "sincronizado en segundo plano; el consumo real es mínimo porque no " +
                            "hace nada mientras no copies."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                if (!exento) {
                    TextButton(onClick = {
                        pedirExencionDeBateria(contexto)
                        exento = tieneExencionDeBateria(contexto)
                    }) {
                        Text("Quitar las restricciones")
                    }
                }

                if (GuiaDeFabricante.necesitaPasosExtra()) {
                    Text(
                        "Tu móvil (${GuiaDeFabricante.paraEsteDispositivo().fabricante}) tiene además " +
                            "su propio sistema para cerrar apps, aparte del de Android.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { mostrandoGuia = !mostrandoGuia }) {
                        Text(if (mostrandoGuia) "Ocultar los pasos" else "Ver qué hay que tocar")
                    }
                }

                if (mostrandoGuia) {
                    val guia = GuiaDeFabricante.paraEsteDispositivo()
                    guia.pasos.forEachIndexed { indice, paso ->
                        Text("${indice + 1}. $paso", style = MaterialTheme.typography.bodySmall)
                    }
                    guia.intent?.let { intent ->
                        TextButton(onClick = { abrirSiSePuede(contexto, intent) }) {
                            Text("Abrir esos ajustes")
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Text("Cómo funciona", style = MaterialTheme.typography.titleMedium)
        ValorFijo("Solo red local", "v1 no tiene servidores ni relay: si no estáis en la misma red, no hay sincronización.")
        ValorFijo("Cifrado de extremo a extremo", "Siempre, también dentro de tu red. Claves nuevas en cada conexión.")
        ValorFijo("Clips sensibles", "Nunca se sincronizan. No hay forma de activarlo, y es a propósito.")
        ValorFijo("Sin historial", "DracPaste no guarda lo que copias en ningún sitio.")
        ValorFijo("Sin analíticas", "No hay nada que enviar porque no hay a dónde enviarlo.")

        TextButton(onClick = alVolver) { Text("Volver") }
    }
}

@Composable
private fun AjusteConInterruptor(
    titulo: String,
    explicacion: String,
    valor: Boolean,
    alCambiar: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall)
            Text(explicacion, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = valor, onCheckedChange = alCambiar)
    }
}

@Composable
private fun ValorFijo(titulo: String, explicacion: String) {
    Column {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        Text(explicacion, style = MaterialTheme.typography.bodySmall)
    }
}

private fun tieneExencionDeBateria(contexto: Context): Boolean {
    val gestor = contexto.getSystemService(Context.POWER_SERVICE) as PowerManager
    return gestor.isIgnoringBatteryOptimizations(contexto.packageName)
}

/**
 * Abre el diálogo del sistema para pedir la exención.
 *
 * Se usa el intent que pregunta al usuario, no el que la concede en silencio: el segundo
 * está prohibido por las políticas de Play y, sobre todo, quitarle a alguien una
 * protección de batería sin decírselo no es aceptable en una app que le pide confianza.
 */
private fun pedirExencionDeBateria(contexto: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${contexto.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    if (!abrirSiSePuede(contexto, intent)) {
        // Algunas ROM no tienen esa pantalla: se cae a los ajustes de la app.
        abrirSiSePuede(
            contexto,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${contexto.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Las pantallas de ajustes de los fabricantes cambian de nombre entre versiones. Si no
 * existe, el usuario vería un cierre inesperado en lugar de una guía.
 */
private fun abrirSiSePuede(contexto: Context, intent: Intent): Boolean = try {
    contexto.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}
