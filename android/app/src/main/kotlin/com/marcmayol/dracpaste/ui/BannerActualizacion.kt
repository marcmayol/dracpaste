package com.marcmayol.dracpaste.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.marcm.actualizador.EstadoActualizacion

/**
 * Aviso de actualización, arriba de la pantalla principal.
 *
 * Solo habla cuando hay algo que hacer o algo en marcha. Ni los errores ni el «estás al
 * día» aparecen aquí: eso vive en Ajustes, donde el usuario ha pedido la comprobación a
 * mano y sí espera una respuesta.
 */
@Composable
fun BannerActualizacion(
    estado: EstadoActualizacion,
    onActualizar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = estado is EstadoActualizacion.Disponible ||
        estado is EstadoActualizacion.Descargando ||
        estado is EstadoActualizacion.Verificando ||
        estado is EstadoActualizacion.PidiendoPermiso ||
        estado is EstadoActualizacion.Instalando

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (estado) {
                    is EstadoActualizacion.Disponible -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "DracPaste ${estado.info.versionName} disponible",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (estado.info.notas.isNotBlank()) {
                                Text(estado.info.notas, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = onActualizar) { Text("Actualizar") }
                    }

                    is EstadoActualizacion.Descargando -> Progreso(
                        titulo = "Descargando la actualización",
                        detalle = "${estado.porcentaje} %",
                        fraccion = estado.porcentaje / 100f,
                    )

                    EstadoActualizacion.Verificando -> Progreso(
                        titulo = "Comprobando el archivo",
                        // No es un tecnicismo de adorno: si el hash no cuadra, el APK se
                        // borra y no se instala nada.
                        detalle = "Verificando que la descarga es la que se publicó",
                        fraccion = null,
                    )

                    EstadoActualizacion.PidiendoPermiso -> Progreso(
                        titulo = "Falta un permiso",
                        detalle = "Autoriza a DracPaste a instalar aplicaciones y volvemos aquí",
                        fraccion = null,
                    )

                    EstadoActualizacion.Instalando -> Progreso(
                        titulo = "Instalando",
                        detalle = "DracPaste se reiniciará al terminar",
                        fraccion = null,
                    )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Progreso(titulo: String, detalle: String, fraccion: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleMedium)
        Text(detalle, style = MaterialTheme.typography.bodySmall)

        if (fraccion != null) {
            LinearProgressIndicator(
                progress = { fraccion.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
        }
    }
}
