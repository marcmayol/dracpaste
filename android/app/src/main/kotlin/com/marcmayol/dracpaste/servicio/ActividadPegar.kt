package com.marcmayol.dracpaste.servicio

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.marcmayol.dracpaste.portapapeles.GestorPortapapeles

/**
 * El plan B para los fabricantes que no dejan escribir en el portapapeles desde segundo
 * plano (`PLAN.md` §7).
 *
 * Es una activity invisible: se abre desde la acción «Pegar» de la notificación, escribe
 * el clip con el foco puesto —que es lo que algunos OEM exigen— y se cierra al instante.
 * El usuario ve un aviso breve y nada más.
 */
class ActividadPegar : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val texto = intent?.getStringExtra(EXTRA_TEXTO)
        if (texto.isNullOrEmpty()) {
            finish()
            return
        }

        val escrito = GestorPortapapeles(this).escribir(texto)
        Toast.makeText(
            this,
            if (escrito) "Copiado al portapapeles" else "El sistema no ha dejado copiarlo",
            Toast.LENGTH_SHORT,
        ).show()

        if (escrito) {
            ServicioDracPaste.arrancar(this, ServicioDracPaste.ACCION_CLIP_PEGADO)
        }

        finish()
    }

    companion object {
        const val EXTRA_TEXTO = "texto"
    }
}
