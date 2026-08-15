package com.marcmayol.dracpaste.portapapeles

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * Acceso al portapapeles de Android.
 *
 * **Leer y escribir no son simétricos**, y esa asimetría es la que da forma a toda la
 * app. Escribir se puede hacer desde el servicio en cualquier momento; **leer** solo es
 * posible con el foco de pantalla desde Android 10, y de ahí sale la activity invisible
 * de la Fase 3.
 */
class GestorPortapapeles(private val contexto: Context) {

    private val gestor: ClipboardManager
        get() = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Escribe un texto que llega del PC.
     *
     * @return `false` si el sistema lo ha impedido. Algunos fabricantes bloquean la
     *   escritura desde segundo plano, y en ese caso hay que ofrecer al usuario la
     *   alternativa de tocar la notificación (`PLAN.md` §7).
     */
    fun escribir(texto: String): Boolean = try {
        val clip = ClipData.newPlainText(ETIQUETA, texto)

        // Se marca como propio para poder reconocerlo si alguna vez se lee de vuelta.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_PROPIO, true)
            }
        }

        gestor.setPrimaryClip(clip)
        true
    } catch (e: Exception) {
        // No se distingue el motivo porque para quien llama todos acaban igual: hay que
        // pedirle al usuario que toque la notificación.
        Log.w(TAG, "El sistema no dejó escribir en el portapapeles", e)
        false
    }

    /**
     * Lee el portapapeles. **Solo funciona con el foco de pantalla** (Android 10+): desde
     * el servicio devuelve siempre `null`, y no es un fallo que se pueda arreglar.
     *
     * @return el texto, o `null` si no hay nada, no es texto, o es un clip sensible.
     */
    fun leer(): ResultadoLectura {
        val clip = gestor.primaryClip ?: return ResultadoLectura.Vacio

        if (esSensible(clip.description)) {
            // Un gestor de contraseñas marca así lo que copia. No se sincroniza nunca,
            // ni siquiera cifrado: la contraseña no tiene por qué salir del móvil.
            return ResultadoLectura.Sensible
        }

        if (clip.itemCount == 0) {
            return ResultadoLectura.Vacio
        }

        val texto = clip.getItemAt(0).coerceToText(contexto)?.toString()
        return if (texto.isNullOrEmpty()) ResultadoLectura.Vacio else ResultadoLectura.Texto(texto)
    }

    /**
     * Un clip marcado como sensible por quien lo copió: contraseñas, códigos de un solo
     * uso, tarjetas. La regla vive en [MarcadoSensible], que se puede probar sin
     * dispositivo.
     */
    private fun esSensible(descripcion: ClipDescription?): Boolean {
        val extras = descripcion?.extras ?: return false
        return MarcadoSensible.esSensible { clave -> extras.getBoolean(clave, false) }
    }

    sealed interface ResultadoLectura {
        data class Texto(val contenido: String) : ResultadoLectura
        data object Vacio : ResultadoLectura
        data object Sensible : ResultadoLectura
    }

    private companion object {
        const val TAG = "DracPaste.Portapapeles"
        const val ETIQUETA = "DracPaste"
        const val EXTRA_PROPIO = "com.marcmayol.dracpaste.PROPIO"
    }
}
