package com.marcmayol.dracpaste

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprueba el manifiesto.
 *
 * Puede parecer excesivo, pero varias decisiones del plan **solo existen aquí**: si
 * alguien quita `excludeFromRecents` de la activity de captura, el usuario empieza a ver
 * una ventana fantasma en la lista de aplicaciones cada vez que envía un clip; si el tema
 * translúcido se cambia por el normal, ve un parpadeo blanco a pantalla completa. Ninguna
 * de las dos cosas rompe un test ni da un error: simplemente quedan feas y nadie se entera
 * hasta que se prueba a mano en un móvil.
 */
class ManifiestoTest {

    private val manifiesto: String = File("src/main/AndroidManifest.xml").readText()

    /**
     * El bloque `<activity>` que declara esa clase, con sus atributos y sus
     * `intent-filter` si los tiene.
     *
     * Una declaración termina de dos maneras: con `/>` si va sola, o con `</activity>` si
     * lleva hijos. Se toma la que aparezca antes.
     */
    private fun bloqueDe(clase: String): String {
        val nombre = manifiesto.indexOf("android:name=\".$clase\"")
        assertTrue("No se encuentra $clase en el manifiesto", nombre > 0)

        val inicio = manifiesto.lastIndexOf("<activity", nombre)
        assertTrue("$clase no está dentro de una <activity>", inicio >= 0)

        // Dónde acaba la etiqueta de apertura. Hay que mirar ahí y no al primer "/>" que
        // aparezca: los <action> de dentro de un intent-filter también se autocierran, y
        // buscando a ciegas el bloque se cortaría por la mitad.
        val finDeLaApertura = manifiesto.indexOf('>', nombre)
        assertTrue("Etiqueta de $clase sin cerrar", finDeLaApertura > 0)

        val vaSola = manifiesto[finDeLaApertura - 1] == '/'
        val fin = if (vaSola) {
            finDeLaApertura + 1
        } else {
            val cierre = manifiesto.indexOf("</activity>", finDeLaApertura)
            assertTrue("No se encuentra el </activity> de $clase", cierre > 0)
            cierre
        }

        return manifiesto.substring(inicio, fin)
    }

    @Test
    fun `la activity de captura es invisible y no deja rastro`() {
        val bloque = bloqueDe("portapapeles.ActividadCaptura")

        assertTrue(
            "Sin el tema translúcido, el usuario ve un parpakeo a pantalla completa en cada envío",
            bloque.contains("@style/Theme.DracPaste.Invisible"),
        )
        assertTrue(
            "Sin excludeFromRecents aparece una ventana fantasma en la lista de apps",
            bloque.contains("android:excludeFromRecents=\"true\""),
        )
        assertTrue(
            "singleTask evita que dos toques seguidos apilen dos instancias",
            bloque.contains("android:launchMode=\"singleTask\""),
        )
        assertTrue(bloque.contains("android:noHistory=\"true\""))
    }

    @Test
    fun `la activity de pegar tambien es invisible`() {
        val bloque = bloqueDe("servicio.ActividadPegar")

        assertTrue(bloque.contains("@style/Theme.DracPaste.Invisible"))
        assertTrue(bloque.contains("android:excludeFromRecents=\"true\""))
    }

    @Test
    fun `DracPaste aparece en el menu de compartir texto`() {
        val bloque = bloqueDe("portapapeles.ActividadCompartir")

        assertTrue("Debe ser exported para que el sistema la ofrezca", bloque.contains("android:exported=\"true\""))
        assertTrue(bloque.contains("android.intent.action.SEND"))
        assertTrue(bloque.contains("android:mimeType=\"text/plain\""))
    }

    @Test
    fun `el servicio declara el tipo connectedDevice`() {
        // Obligatorio desde Android 14: sin esto el sistema tira el servicio nada más
        // arrancar, y la sincronización moriría al salir de la app.
        assertTrue(manifiesto.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertTrue(manifiesto.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }

    @Test
    fun `estan los permisos que necesita el protocolo`() {
        for (permiso in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.CAMERA",
        )) {
            assertTrue("Falta el permiso $permiso", manifiesto.contains(permiso))
        }
    }

    @Test
    fun `no se piden permisos que la app no necesita`() {
        // Una app de privacidad no puede pedir de más: cada permiso del manifiesto es
        // algo que el usuario ve y tiene que creerse.
        for (permiso in listOf(
            "READ_LOGS",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "READ_CONTACTS",
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "QUERY_ALL_PACKAGES",
        )) {
            assertTrue("La app no debería pedir $permiso", !manifiesto.contains(permiso))
        }
    }

    @Test
    fun `la camara es opcional`() {
        // Solo hace falta para escanear el QR una vez. Marcarla como obligatoria dejaría
        // la app fuera de dispositivos sin cámara sin ningún motivo.
        assertTrue(
            manifiesto.contains("android:name=\"android.hardware.camera.any\"") &&
                manifiesto.contains("android:required=\"false\""),
        )
    }

    @Test
    fun `no se hace copia de seguridad automatica`() {
        // Las claves privadas están envueltas por el Keystore de ESTE dispositivo: una
        // copia restaurada en otro móvil no podría descifrarlas, y el usuario se
        // encontraría con emparejamientos que parecen existir y no funcionan.
        assertTrue(manifiesto.contains("android:allowBackup=\"false\""))
    }
}
