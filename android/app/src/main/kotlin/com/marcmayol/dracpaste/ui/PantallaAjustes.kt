package com.marcmayol.dracpaste.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Con la flecha arriba: el enlace «Volver» estaba al final del todo, así que para
        // salir de aquí había que bajar por toda la pantalla.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { alVolver() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", style = MaterialTheme.typography.headlineMedium)
            Text("Ajustes y batería", style = MaterialTheme.typography.headlineSmall)
        }

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

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (exento) ColoresDrac.tarjeta else ColoresDrac.papel,
            // En rojo solo cuando hay algo que arreglar. Cuando está bien no hace falta
            // llamar la atención: se dice y punto.
            border = BorderStroke(2.dp, if (exento) ColoresDrac.linea else ColoresDrac.peligro),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (exento) "Batería: sin restricciones ✓" else "Batería: con restricciones",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (exento) ColoresDrac.tinta else ColoresDrac.peligro,
                )
                Text(
                    // Una frase, y la que duele. El párrafo anterior explicaba el consumo
                    // de batería antes de decir qué se rompe, y para cuando llegabas a lo
                    // importante ya habías dejado de leer.
                    if (exento) {
                        "Android no matará la conexión por ahorro de batería."
                    } else {
                        "Android puede matar la conexión cuando apagues la pantalla."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (!exento) {
                    Button(
                        onClick = {
                            pedirExencionDeBateria(contexto)
                            exento = tieneExencionDeBateria(contexto)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColoresDrac.peligro,
                            contentColor = ColoresDrac.papel,
                        ),
                        shape = RoundedCornerShape(99.dp),
                    ) {
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

        HorizontalDivider(color = ColoresDrac.linea)

        // Estas cinco no son ajustes apagados: son las reglas de la app, y no hay forma de
        // tocarlas. Antes se veían igual que los interruptores de arriba —mismo título en
        // negrita, misma explicación debajo— y se leían como opciones desactivadas.
        Text(
            "LAS REGLAS DE LA CASA · NO SE PUEDEN TOCAR",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            ),
            color = ColoresDrac.apagado,
        )

        Regla("Solo red local", "v1 no tiene servidores ni relay: si no estáis en la misma red, no hay sincronización.")
        Regla("Cifrado de extremo a extremo", "Siempre, también dentro de tu red. Claves nuevas en cada conexión.")
        Regla("Clips sensibles", "Nunca se sincronizan. No hay opción para activarlo. A propósito.")
        Regla("Sin historial", "DracPaste no guarda lo que copias en ningún sitio.")
        Regla("Sin analíticas", "No hay nada que enviar porque no hay a dónde enviarlo.")

        Text(
            "DracPaste no envía nada fuera de tu red local, no guarda historial de clips y " +
                "no recoge ninguna estadística.",
            style = MaterialTheme.typography.bodySmall,
            color = ColoresDrac.apagado,
        )
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

/**
 * Una regla de la casa: prosa corrida con un cuadrado delante, para que no se confunda con
 * el par título/subtítulo de los ajustes de arriba.
 */
@Composable
private fun Regla(titulo: String, explicacion: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(ColoresDrac.tinta),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(titulo) }
                append(" — ")
                append(explicacion)
            },
            style = MaterialTheme.typography.bodySmall,
        )
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
