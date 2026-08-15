package com.marcmayol.dracpaste

import android.app.Application
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.ActualizadorConfig

/**
 * DracPaste se distribuye fuera de Play Store, así que se actualiza sola: el mismo módulo
 * `:actualizador` que llevan Kuse, el Grimorio y Building My Future.
 *
 * Consulta un manifiesto en GitHub Pages, compara el `versionCode` con el suyo y, si hay
 * novedad, descarga el APK de la Release, **verifica su SHA-256** y lo instala. El hash no
 * es un detalle: es lo único que garantiza que el APK que se instala es el que se publicó.
 */
class DracPasteApp : Application() {

    val actualizador: Actualizador by lazy {
        Actualizador(
            app = this,
            config = ActualizadorConfig(
                manifiestoUrl = MANIFIESTO_URL,
                versionCodeActual = BuildConfig.VERSION_CODE,
                checkHorasPorDefecto = 24,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Comprobación periódica con WorkManager, solo cuando hay red.
        actualizador.programarPeriodica()
    }

    companion object {
        const val MANIFIESTO_URL = "https://marcmayol.com/dracpaste/updates.json"
    }
}
