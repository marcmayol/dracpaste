package com.marcmayol.dracpaste.portapapeles

/**
 * Reconoce los clips que su autor ha marcado como sensibles: contraseñas, códigos de un
 * solo uso, números de tarjeta.
 *
 * **Estos clips no se sincronizan nunca**, ni siquiera cifrados. Es una decisión cerrada
 * del plan (§3.1): cuando un gestor de contraseñas copia algo, no tiene por qué salir del
 * móvil.
 *
 * Está separado de `ClipDescription` a propósito, sin depender de Android, para que la
 * regla se pueda probar sin un dispositivo. Equivocarse en el nombre de una clave haría
 * que las contraseñas viajaran sin que nada lo indicara.
 */
object MarcadoSensible {

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`. La constante existe desde Android 13, pero
     * los gestores de contraseñas llevan usando esta misma cadena desde antes: se
     * comprueba por su valor literal para respetarla también en Android 10, 11 y 12, que
     * es justo donde el sistema no la conoce y nadie más la va a mirar.
     */
    const val EXTRA_ANDROID = "android.content.extra.IS_SENSITIVE"

    /** La variante que usan AndroidX y algunos gestores. */
    const val EXTRA_ANDROIDX = "androidx.content.extra.IS_SENSITIVE"

    val CLAVES = listOf(EXTRA_ANDROID, EXTRA_ANDROIDX)

    /**
     * @param leerBooleano lee esa clave de los extras del clip. En Android es
     *   `extras.getBoolean(clave, false)`; en los tests, un mapa.
     */
    fun esSensible(leerBooleano: (String) -> Boolean): Boolean = CLAVES.any(leerBooleano)
}
