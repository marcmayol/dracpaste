package com.marcmayol.dracpaste.portapapeles

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.marcmayol.dracpaste.servicio.ServicioDracPaste

/**
 * La pieza clave de la dirección móvil → PC.
 *
 * **Por qué existe.** Desde Android 10, una aplicación solo puede leer el portapapeles si
 * tiene el foco de pantalla o es el teclado del sistema. DracPaste no hace ninguna de las
 * dos cosas de forma permanente, así que cuando el usuario pulsa el botón de la
 * notificación se abre esta ventana: es translúcida, no dibuja nada, no aparece en
 * recientes y vive unos milisegundos. El tiempo justo de tener el foco, leer y cerrarse.
 *
 * **El detalle que hace que funcione.** La lectura ocurre en [onWindowFocusChanged], no en
 * `onCreate`. Cuando `onCreate` se ejecuta, la ventana todavía **no tiene el foco**: el
 * portapapeles devuelve `null` y el clip sale vacío. Es el fallo número uno de la tabla de
 * riesgos del plan y no se manifiesta en el emulador de forma fiable, así que es fácil
 * darlo por bueno sin serlo.
 */
class ActividadCaptura : Activity() {

    /**
     * El foco puede ir y venir varias veces (una notificación que aparece encima, por
     * ejemplo). Solo interesa la primera vez que llega: leer dos veces enviaría el clip
     * dos veces.
     */
    private var yaLeido = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aquí NO se lee el portapapeles: todavía no hay foco y devolvería vacío.
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (!hasFocus || yaLeido) {
            return
        }

        yaLeido = true
        capturarYEnviar()
    }

    /**
     * Si el foco no llega —porque otra ventana se ha puesto delante, o porque el sistema
     * decide no dárnoslo—, la activity no puede quedarse abierta para siempre.
     */
    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            finish()
        }
    }

    private fun capturarYEnviar() {
        when (val leido = GestorPortapapeles(this).leer()) {
            is GestorPortapapeles.ResultadoLectura.Texto -> {
                ServicioDracPaste.arrancar(
                    this,
                    ServicioDracPaste.ACCION_ENVIAR_TEXTO,
                    leido.contenido,
                )
            }

            GestorPortapapeles.ResultadoLectura.Sensible -> {
                // Un gestor de contraseñas ha marcado el clip. No se envía ni cifrado, y
                // se le dice al usuario por qué: si no, parecería que la app falla.
                avisar("Ese contenido está marcado como sensible y no se comparte")
            }

            GestorPortapapeles.ResultadoLectura.Vacio -> {
                avisar("No hay nada que copiar en el portapapeles")
            }
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun avisar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
}
