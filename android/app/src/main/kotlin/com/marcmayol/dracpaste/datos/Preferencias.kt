package com.marcmayol.dracpaste.datos

import android.content.Context
import androidx.core.content.edit

/**
 * Los ajustes del usuario (`PLAN.md` §3.1.5).
 *
 * Se guardan con `SharedPreferences` y no con DataStore: son cuatro valores que el
 * servicio necesita leer de forma síncrona en mitad de un evento del portapapeles, y
 * montar corrutinas para eso sería más complicado sin ganar nada.
 */
class Preferencias(contexto: Context) {

    private val prefs = contexto.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /**
     * Con la sincronización pausada, nada sale ni entra. El usuario sigue viendo el
     * estado de la conexión, así que sabe que está pausado y no que se ha roto.
     */
    var pausado: Boolean
        get() = prefs.getBoolean(PAUSADO, false)
        set(valor) = prefs.edit { putBoolean(PAUSADO, valor) }

    /**
     * Respetar los clips marcados como sensibles.
     *
     * **Se puede ver pero no apagar**: aparece como un valor fijo en la interfaz. Una
     * app cuyo argumento es la privacidad no debería ofrecer un interruptor para mandar
     * contraseñas por la red, aunque el usuario lo pidiera.
     */
    val respetaSensibles: Boolean = true

    /**
     * Solo LAN. En v1 es siempre `true` y se muestra como un valor, no como una opción
     * (`PLAN.md` §3.1.5): no hay relay ni P2P que activar.
     */
    val soloLan: Boolean = true

    /** Avisar con un toast cada vez que llega un clip del PC. */
    var avisarAlRecibir: Boolean
        get() = prefs.getBoolean(AVISAR_AL_RECIBIR, false)
        set(valor) = prefs.edit { putBoolean(AVISAR_AL_RECIBIR, valor) }

    /** Si ya se explicó la guía del fabricante, para no insistir en cada arranque. */
    var guiaDeBateriaVista: Boolean
        get() = prefs.getBoolean(GUIA_VISTA, false)
        set(valor) = prefs.edit { putBoolean(GUIA_VISTA, valor) }

    private companion object {
        const val PAUSADO = "pausado"
        const val AVISAR_AL_RECIBIR = "avisar_al_recibir"
        const val GUIA_VISTA = "guia_bateria_vista"
    }
}
