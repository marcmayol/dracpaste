package com.marcmayol.dracpaste.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * La identidad visual de DracPaste: **papel y tinta con terracota**.
 *
 * No se usa color dinámico (el que toma la paleta del fondo de pantalla) a propósito. La
 * razón no es de gusto: la pantalla de la huella tiene que verse igual en el móvil, en el
 * PC y en cualquier captura que alguien mande pidiendo ayuda, porque comparar dos huellas
 * es lo único que impide que un tercero se cuele en el emparejamiento. Si la app se tiñe
 * del fondo de pantalla de cada uno, esa correspondencia se rompe.
 *
 * El terracota **solo significa acción** —enviar, escanear, actualizar—; nunca estado. Los
 * estados se dicen con forma y con palabras, porque el icono de la barra y el de la
 * bandeja son monocromos y ahí el color no existe.
 */

/** Tokens del sistema, para lo que Material 3 no cubre: chips, bordes gruesos, carriles. */
data class ColoresDracPaste(
    val tinta: Color,
    val papel: Color,
    val tarjeta: Color,
    val apagado: Color,
    val acento: Color,
    val peligro: Color,
    val linea: Color,
    /** El texto que va encima del acento. En oscuro es tinta, no blanco. */
    val sobreAcento: Color,
)

private val TokensClaro = ColoresDracPaste(
    tinta = Color(0xFF1A1A1A),
    papel = Color(0xFFFAF8F4),
    tarjeta = Color(0xFFFFFFFF),
    apagado = Color(0xFF5C554E),
    acento = Color(0xFFC2521E),
    peligro = Color(0xFF8C2F10),
    linea = Color(0xFFDDD6CD),
    sobreAcento = Color(0xFFFFFFFF),
)

private val TokensOscuro = ColoresDracPaste(
    tinta = Color(0xFFECE5DC),
    papel = Color(0xFF171310),
    tarjeta = Color(0xFF211C17),
    apagado = Color(0xFFA89F93),
    acento = Color(0xFFE07A4A),
    peligro = Color(0xFFF0996E),
    linea = Color(0xFF3A332C),
    // Sobre el naranja claro del tema oscuro, el blanco se queda en 2,4:1. La tinta
    // cálida da 5,1:1 y además es lo que pide el diseño.
    sobreAcento = Color(0xFF1A1208),
)

val LocalColoresDracPaste = staticCompositionLocalOf { TokensClaro }

/** Atajo para leer los tokens: `ColoresDrac.acento`. */
val ColoresDrac: ColoresDracPaste
    @Composable get() = LocalColoresDracPaste.current

private val Claro = lightColorScheme(
    primary = TokensClaro.acento,
    onPrimary = TokensClaro.sobreAcento,
    primaryContainer = TokensClaro.acento,
    onPrimaryContainer = TokensClaro.sobreAcento,

    secondary = TokensClaro.tinta,
    onSecondary = TokensClaro.papel,

    background = TokensClaro.papel,
    onBackground = TokensClaro.tinta,
    surface = TokensClaro.tarjeta,
    onSurface = TokensClaro.tinta,
    surfaceVariant = TokensClaro.tarjeta,
    onSurfaceVariant = TokensClaro.apagado,

    outline = TokensClaro.tinta,
    outlineVariant = TokensClaro.linea,
    error = TokensClaro.peligro,
    onError = Color.White,
)

private val Oscuro = darkColorScheme(
    primary = TokensOscuro.acento,
    onPrimary = TokensOscuro.sobreAcento,
    primaryContainer = TokensOscuro.acento,
    onPrimaryContainer = TokensOscuro.sobreAcento,

    secondary = TokensOscuro.tinta,
    onSecondary = TokensOscuro.papel,

    background = TokensOscuro.papel,
    onBackground = TokensOscuro.tinta,
    surface = TokensOscuro.tarjeta,
    onSurface = TokensOscuro.tinta,
    surfaceVariant = TokensOscuro.tarjeta,
    onSurfaceVariant = TokensOscuro.apagado,

    outline = TokensOscuro.tinta,
    outlineVariant = TokensOscuro.linea,
    error = TokensOscuro.peligro,
    onError = TokensOscuro.sobreAcento,
)

@Composable
fun TemaDracPaste(
    oscuro: Boolean = isSystemInDarkTheme(),
    contenido: @Composable () -> Unit,
) {
    val esquema = if (oscuro) Oscuro else Claro
    val tokens = if (oscuro) TokensOscuro else TokensClaro
    val vista = LocalView.current

    if (!vista.isInEditMode) {
        SideEffect {
            val ventana = (vista.context as Activity).window
            // Los iconos de la barra de estado se invierten según el fondo: sin esto, en
            // tema claro quedan blancos sobre papel y no se ven.
            WindowCompat.getInsetsController(ventana, vista).isAppearanceLightStatusBars = !oscuro
        }
    }

    CompositionLocalProvider(LocalColoresDracPaste provides tokens) {
        MaterialTheme(
            colorScheme = esquema,
            content = contenido,
        )
    }
}
