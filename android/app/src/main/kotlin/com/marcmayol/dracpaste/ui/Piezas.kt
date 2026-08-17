package com.marcmayol.dracpaste.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.marcmayol.dracpaste.R

/**
 * Las piezas que el diseño usa en varias pantallas.
 *
 * La más importante es el **carril**: la app hace dos cosas que parecen la misma y no lo
 * son, y el dibujo es lo que lo cuenta sin que haya que leerse un párrafo. La dirección
 * que va sola es una línea continua; la que necesita un dedo es una línea que **se corta y
 * solo se completa atravesando un botón**. Nunca una flecha de doble punta: prometería que
 * las dos direcciones funcionan igual, y no es verdad.
 */

/** La cabecera de la app: la cabeza de Ladón y el nombre. */
@Composable
fun Cabecera(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ladon),
            contentDescription = null,
            // La cabeza viene dibujada en negro, y en tema oscuro se perdía sobre el
            // fondo: teñida con el color de tinta se invierte con el tema y siempre
            // contrasta. Es una silueta plana, así que no pierde nada al teñirse.
            colorFilter = ColorFilter.tint(ColoresDrac.tinta),
            modifier = Modifier.size(26.dp),
        )
        Text(
            "DRACPASTE",
            // El diseño pide Archivo Black. No se empaqueta la fuente todavía: el peso
            // más grueso de la del sistema con el interletrado cerrado se le acerca sin
            // añadir 200 KB al APK por una sola palabra.
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.3).sp,
            ),
        )
    }
}

/** Las promesas de la app, en chips, donde se leen antes de emparejar nada. */
@Composable
fun ChipsDePromesa(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("solo red local", "sin historial", "cifrado e2e").forEach { texto ->
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = ColoresDrac.papel,
                border = BorderStroke(1.5.dp, ColoresDrac.tinta),
            ) {
                Text(
                    texto,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = ColoresDrac.tinta,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Un carril: de dónde a dónde va el texto y si hace falta tocar algo.
 *
 * @param conBoton dibuja el tramo cortado y el botón en medio. Es la dirección manual.
 */
@Composable
fun Carril(
    origen: String,
    destino: String,
    nota: String,
    conBoton: Boolean,
    modifier: Modifier = Modifier,
) {
    val tinta = ColoresDrac.tinta
    val acento = ColoresDrac.acento
    val apagado = ColoresDrac.apagado

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            origen,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.width(42.dp),
        )

        Canvas(modifier = Modifier.size(width = 132.dp, height = 22.dp)) {
            val y = size.height / 2
            val grosor = 3.dp.toPx()
            val puntaAncho = 10.dp.toPx()
            val finLinea = size.width - puntaAncho

            if (conBoton) {
                val cajaIzq = size.width * 0.30f
                val cajaDer = size.width * 0.68f

                drawLine(
                    color = tinta,
                    start = Offset(0f, y),
                    end = Offset(cajaIzq, y),
                    strokeWidth = grosor,
                    // Discontinua: el trazo está roto porque el camino lo está.
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(5.dp.toPx(), 5.dp.toPx()),
                    ),
                )

                drawRect(
                    color = acento,
                    topLeft = Offset(cajaIzq + 2.dp.toPx(), y - 9.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        cajaDer - cajaIzq - 4.dp.toPx(),
                        18.dp.toPx(),
                    ),
                )

                drawLine(
                    color = tinta,
                    start = Offset(cajaDer, y),
                    end = Offset(finLinea, y),
                    strokeWidth = grosor,
                )
            } else {
                drawLine(
                    color = tinta,
                    start = Offset(0f, y),
                    end = Offset(finLinea, y),
                    strokeWidth = grosor,
                )
            }

            // La punta de flecha, siempre al final: la dirección importa.
            val punta = Path().apply {
                moveTo(finLinea, y - 6.dp.toPx())
                lineTo(size.width, y)
                lineTo(finLinea, y + 6.dp.toPx())
                close()
            }
            drawPath(punta, tinta)
        }

        Text(
            destino,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
        )

        Text(
            nota,
            style = MaterialTheme.typography.labelSmall,
            color = apagado,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Los dos carriles juntos: el modelo entero de la app en cuatro líneas. */
@Composable
fun DosCarriles(nombrePc: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Carril(
            origen = nombrePc ?: "PC",
            destino = "móvil",
            nota = "llega solo",
            conBoton = false,
        )
        Carril(
            origen = "móvil",
            destino = nombrePc ?: "PC",
            nota = "con un toque",
            conBoton = true,
        )
    }
}

/** La etiqueta sólida del PC que recibe los clips. */
@Composable
fun EtiquetaDestinoActivo(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = ColoresDrac.tinta,
    ) {
        Text(
            "DESTINO ACTIVO",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = ColoresDrac.papel,
            modifier = Modifier
                .background(ColoresDrac.tinta)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
