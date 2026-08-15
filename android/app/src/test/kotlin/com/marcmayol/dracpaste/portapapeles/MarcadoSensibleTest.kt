package com.marcmayol.dracpaste.portapapeles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regla que impide que una contraseña salga del móvil.
 *
 * Es un criterio de aceptación explícito del plan (Fase 3): «un clip sensible de un gestor
 * de contraseñas NO se envía». Equivocarse en el nombre de una clave no daría ningún error
 * visible —el clip viajaría igual—, así que conviene tenerlo fijado por escrito.
 */
class MarcadoSensibleTest {

    private fun extras(vararg puestas: String): (String) -> Boolean =
        { clave -> clave in puestas }

    @Test
    fun `un clip normal no es sensible`() {
        assertFalse(MarcadoSensible.esSensible(extras()))
    }

    @Test
    fun `la marca de Android se respeta`() {
        assertTrue(MarcadoSensible.esSensible(extras("android.content.extra.IS_SENSITIVE")))
    }

    @Test
    fun `la marca de AndroidX tambien se respeta`() {
        // Hay gestores que usan esta variante en vez de la del sistema.
        assertTrue(MarcadoSensible.esSensible(extras("androidx.content.extra.IS_SENSITIVE")))
    }

    @Test
    fun `las dos claves a la vez siguen siendo sensibles`() {
        assertTrue(
            MarcadoSensible.esSensible(
                extras("android.content.extra.IS_SENSITIVE", "androidx.content.extra.IS_SENSITIVE"),
            ),
        )
    }

    @Test
    fun `otra clave parecida no cuenta`() {
        // Solo las dos cadenas exactas; no vale cualquier cosa que contenga "SENSITIVE".
        assertFalse(MarcadoSensible.esSensible(extras("com.otraapp.IS_SENSITIVE")))
        assertFalse(MarcadoSensible.esSensible(extras("IS_SENSITIVE")))
    }

    @Test
    fun `las claves son exactamente las que usan los gestores de contrasenas`() {
        // Fijadas por escrito: si alguien las cambiara por un typo, las contraseñas
        // empezarían a viajar sin que nada avisara.
        assertEquals("android.content.extra.IS_SENSITIVE", MarcadoSensible.EXTRA_ANDROID)
        assertEquals("androidx.content.extra.IS_SENSITIVE", MarcadoSensible.EXTRA_ANDROIDX)
        assertEquals(2, MarcadoSensible.CLAVES.size)
    }

    @Test
    fun `la clave de Android es la misma que ClipDescription EXTRA_IS_SENSITIVE`() {
        // La constante del SDK solo existe desde API 33 y el minSdk es 29. Se comprueba
        // el valor literal para que la regla valga también en Android 10, 11 y 12, que es
        // donde el sistema no la conoce y nadie más la va a mirar.
        assertEquals("android.content.extra.IS_SENSITIVE", MarcadoSensible.EXTRA_ANDROID)
    }
}
