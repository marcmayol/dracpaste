package com.marcmayol.dracpaste.ui

import android.content.Intent
import android.os.Build

/**
 * Lo que hay que tocar en cada fabricante para que el servicio no muera.
 *
 * La exención de batería estándar de Android **no basta** en varios fabricantes: Xiaomi,
 * Samsung, Huawei, OPPO y OnePlus tienen su propia capa encima que mata procesos con sus
 * propias reglas, y cada una está en un sitio distinto de sus ajustes.
 *
 * Un mensaje genérico del tipo «desactiva la optimización de batería» no le sirve de nada
 * a quien tiene un Xiaomi: ahí el ajuste que importa se llama «Sin restricciones» y está
 * dentro de «Ahorro de batería» de la propia app, en otro menú.
 */
object GuiaDeFabricante {

    data class Guia(
        val fabricante: String,
        val pasos: List<String>,
        /** Pantalla concreta de ajustes del fabricante, si se puede abrir directamente. */
        val intent: Intent?,
    )

    fun paraEsteDispositivo(): Guia {
        val marca = Build.MANUFACTURER.lowercase()

        return when {
            marca.contains("xiaomi") || marca.contains("redmi") || marca.contains("poco") -> Guia(
                fabricante = "Xiaomi",
                pasos = listOf(
                    "Ajustes → Aplicaciones → DracPaste → Ahorro de batería → marca «Sin restricciones».",
                    "En la misma pantalla, activa «Inicio automático».",
                    "Abre la lista de apps recientes, mantén pulsada DracPaste y toca el candado " +
                        "para que no se cierre al limpiar.",
                ),
                intent = intentSeguro("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )

            marca.contains("samsung") -> Guia(
                fabricante = "Samsung",
                pasos = listOf(
                    "Ajustes → Batería → Límites de uso en segundo plano → «Aplicaciones en reposo»: " +
                        "comprueba que DracPaste no está en la lista.",
                    "Ajustes → Aplicaciones → DracPaste → Batería → «Sin restricciones».",
                ),
                intent = intentSeguro("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )

            marca.contains("huawei") || marca.contains("honor") -> Guia(
                fabricante = "Huawei",
                pasos = listOf(
                    "Ajustes → Batería → Inicio de aplicaciones → DracPaste: pásala a manual y " +
                        "activa las tres opciones.",
                ),
                intent = intentSeguro("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )

            marca.contains("oppo") || marca.contains("realme") || marca.contains("oneplus") -> Guia(
                fabricante = "OPPO / OnePlus",
                pasos = listOf(
                    "Ajustes → Batería → Uso de batería en segundo plano → DracPaste: «Permitir».",
                    "Ajustes → Aplicaciones → DracPaste → «Permitir inicio automático».",
                ),
                intent = intentSeguro("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            )

            else -> Guia(
                fabricante = Build.MANUFACTURER,
                pasos = listOf(
                    "Ajustes → Aplicaciones → DracPaste → Batería → «Sin restricciones».",
                ),
                intent = null,
            )
        }
    }

    /** ¿Este fabricante necesita algo más que la exención estándar de Android? */
    fun necesitaPasosExtra(): Boolean {
        val marca = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "redmi", "poco", "samsung", "huawei", "honor", "oppo", "realme", "oneplus")
            .any { marca.contains(it) }
    }

    /**
     * Las pantallas de ajustes de los fabricantes cambian de nombre entre versiones. Se
     * construye el intent igualmente, pero quien lo use tiene que comprobar que se puede
     * abrir: si no, el usuario vería un cierre inesperado en vez de una guía.
     */
    private fun intentSeguro(paquete: String, clase: String): Intent =
        Intent().setClassName(paquete, clase)
}
