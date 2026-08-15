package com.marcmayol.dracpaste.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * La identidad visual de DracPaste: **verde dragón y oro**, la misma de su icono y la de la
 * familia Drac.
 *
 * No se usa color dinámico (el que toma la paleta del fondo de pantalla) a propósito: en la
 * bandeja del PC y en la notificación del móvil el usuario ve siempre el mismo verde, y que
 * la app cambiara de color según el fondo de pantalla rompería esa correspondencia.
 */

private val VerdeDragon = Color(0xFF0F3A2E)
private val VerdeClaro = Color(0xFF2E6B56)
private val Oro = Color(0xFFD9A441)
private val OroClaro = Color(0xFFF2D28C)
private val Pergamino = Color(0xFFFBF8F1)
private val TintaOscura = Color(0xFF141A17)

private val Claro = lightColorScheme(
    primary = VerdeDragon,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE7DC),
    onPrimaryContainer = Color(0xFF06231A),

    secondary = Color(0xFF8A6A20),
    onSecondary = Color.White,
    secondaryContainer = OroClaro,
    onSecondaryContainer = Color(0xFF2B1F00),

    tertiary = VerdeClaro,
    onTertiary = Color.White,

    background = Pergamino,
    onBackground = TintaOscura,
    surface = Pergamino,
    onSurface = TintaOscura,
    surfaceVariant = Color(0xFFE6E2D8),
    onSurfaceVariant = Color(0xFF474A44),

    outline = Color(0xFF787B74),
    error = Color(0xFF8E2C21),
    onError = Color.White,
)

private val Oscuro = darkColorScheme(
    primary = Color(0xFF8FD3B8),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF14513E),
    onPrimaryContainer = Color(0xFFAAF0D4),

    secondary = Oro,
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF5C4400),
    onSecondaryContainer = OroClaro,

    tertiary = Color(0xFF9FD0BC),
    onTertiary = Color(0xFF00382A),

    background = Color(0xFF0D1310),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF0D1310),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF3F4A44),
    onSurfaceVariant = Color(0xFFBFCBC3),

    outline = Color(0xFF899590),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680B03),
)

@Composable
fun TemaDracPaste(
    oscuro: Boolean = isSystemInDarkTheme(),
    contenido: @Composable () -> Unit,
) {
    val esquema = if (oscuro) Oscuro else Claro
    val vista = LocalView.current

    if (!vista.isInEditMode) {
        SideEffect {
            val ventana = (vista.context as Activity).window
            ventana.statusBarColor = esquema.background.toArgb()
            // Los iconos de la barra de estado se invierten según el fondo: sin esto, en
            // tema claro quedan blancos sobre pergamino y no se ven.
            WindowCompat.getInsetsController(ventana, vista).isAppearanceLightStatusBars = !oscuro
        }
    }

    MaterialTheme(
        colorScheme = esquema,
        content = contenido,
    )
}
