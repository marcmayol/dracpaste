package com.marcmayol.dracpaste.portapapeles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.marcmayol.dracpaste.servicio.ServicioDracPaste

/**
 * DracPaste como destino del menú «Compartir» de Android.
 *
 * Es la vía más limpia de las dos: el texto llega en el propio intent, así que **no pasa
 * por el portapapeles** en ningún momento. No hace falta foco, no hay que leer nada, y lo
 * que el usuario tuviera copiado se queda como estaba.
 *
 * Funciona desde cualquier app que ofrezca compartir texto: el navegador, las notas, un
 * mensaje.
 */
class ActividadCompartir : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val texto = extraerTexto(intent)

        if (texto.isNullOrBlank()) {
            Toast.makeText(this, "No se ha recibido ningún texto", Toast.LENGTH_SHORT).show()
        } else {
            ServicioDracPaste.arrancar(this, ServicioDracPaste.ACCION_ENVIAR_TEXTO, texto)
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun extraerTexto(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null

        // Algunas apps mandan el texto en EXTRA_TEXT y otras solo un asunto; se prefiere
        // el cuerpo y se cae al asunto si no hay otra cosa.
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
    }
}
