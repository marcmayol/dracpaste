package com.marcmayol.dracpaste.datos

import android.content.Context
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Los PCs emparejados con este móvil y cuál de ellos es el activo.
 *
 * Solo se guarda la clave **pública** de cada uno: la clave de par se recalcula cuando
 * hace falta a partir de la privada de este móvil (§2.2). Este fichero no contiene
 * ningún secreto.
 *
 * **Multi-PC (decisión cerrada del plan §3.3).** La estructura admite varios PCs, cada
 * uno con su clave, pero la política de v1 es "destino activo": los clips van a uno solo,
 * y los anuncios mDNS de los demás se ignoran. Que las claves sean por pareja hace que
 * desemparejar o comprometer un PC no afecte a los otros.
 */
class RegistroPcs(private val carpeta: File) {

    /**
     * En Android la carpeta es la privada de la app. Se admite un [File] cualquiera para
     * que los tests puedan usar un temporal y correr en el JVM, sin emulador.
     */
    constructor(contexto: Context) : this(contexto.filesDir)

    private val fichero: File
        get() = File(carpeta, NOMBRE_FICHERO)

    private val candado = Any()

    fun todos(): List<PcEmparejado> = synchronized(candado) { leer() }

    fun buscar(deviceId: String): PcEmparejado? = todos().firstOrNull { it.deviceId == deviceId }

    /** El PC al que van los clips ahora mismo. */
    fun activo(): PcEmparejado? {
        val lista = todos()
        return lista.firstOrNull { it.activo } ?: lista.firstOrNull()
    }

    fun guardar(pc: PcEmparejado) = synchronized(candado) {
        val previos = leer()
        val resto = previos.filterNot { it.deviceId == pc.deviceId }
        val yaEraElActivo = previos.any { it.deviceId == pc.deviceId && it.activo }

        // El primer PC que se empareja pasa a ser el activo: si no, el usuario tendría
        // que ir a ajustes a elegirlo antes de que la app sirviera para nada.
        val nuevo = pc.copy(activo = pc.activo || yaEraElActivo || resto.isEmpty())

        // Solo puede haber un activo: si este lo es, los demás dejan de serlo.
        val ajustados = if (nuevo.activo) resto.map { it.copy(activo = false) } else resto

        escribir(ajustados + nuevo)
    }

    fun marcarActivo(deviceId: String) = synchronized(candado) {
        escribir(leer().map { it.copy(activo = it.deviceId == deviceId) })
    }

    /** Devuelve si estaba. */
    fun olvidar(deviceId: String): Boolean = synchronized(candado) {
        val lista = leer()
        val restantes = lista.filterNot { it.deviceId == deviceId }
        if (restantes.size == lista.size) return false

        // Si se ha desemparejado el activo, otro toma el relevo: dejar la app sin
        // destino habiendo PCs disponibles solo confundiría al usuario.
        val ajustados = if (restantes.none { it.activo } && restantes.isNotEmpty()) {
            restantes.mapIndexed { indice, pc -> pc.copy(activo = indice == 0) }
        } else {
            restantes
        }

        escribir(ajustados)
        true
    }

    /**
     * Clave de par de un PC, recalculada al vuelo. Es lo que necesita el handshake (§4).
     */
    fun claveParDe(pc: PcEmparejado, identidad: Identidad): ByteArray =
        Derivacion.clavePar(identidad.par.privada, Base64.getDecoder().decode(pc.publicaBase64))

    /** Recuerda dónde estaba el PC, para atacar esa IP mientras mDNS busca (§8). */
    fun recordarDireccion(deviceId: String, ip: String, puerto: Int) = synchronized(candado) {
        escribir(
            leer().map {
                if (it.deviceId == deviceId) it.copy(ultimaIp = ip, ultimoPuerto = puerto) else it
            },
        )
    }

    private fun leer(): List<PcEmparejado> {
        if (!fichero.exists()) return emptyList()

        return try {
            json.decodeFromString(ListSerializer(PcEmparejado.serializer()), fichero.readText())
        } catch (e: Exception) {
            // Un registro ilegible se aparta: es preferible que el usuario tenga que
            // volver a emparejar a que la app no arranque.
            fichero.renameTo(File(carpeta, "$NOMBRE_FICHERO.ilegible-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    private fun escribir(lista: List<PcEmparejado>) {
        carpeta.mkdirs()

        // Se escribe a un temporal y se mueve encima: si el móvil se queda sin batería a
        // mitad, no queda un registro truncado que dejaría la app sin emparejamientos.
        //
        // Con Files.move y REPLACE_EXISTING, no con File.renameTo: renameTo no
        // sobrescribe un fichero que ya exista —en Windows falla en silencio y devuelve
        // false—, así que el registro se habría quedado congelado en su primera versión
        // sin que nada avisara.
        val temporal = File(carpeta, "$NOMBRE_FICHERO.tmp")
        temporal.writeText(json.encodeToString(ListSerializer(PcEmparejado.serializer()), lista))
        Files.move(
            temporal.toPath(),
            fichero.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val NOMBRE_FICHERO = "pcs.json"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

/** Un PC emparejado con este móvil. */
@Serializable
data class PcEmparejado(
    @SerialName("device_id") val deviceId: String,
    val nombre: String,
    /** Su clave pública. No es un secreto. */
    @SerialName("publica") val publicaBase64: String,
    val huella: String,
    val activo: Boolean = false,
    @SerialName("ultima_ip") val ultimaIp: String? = null,
    @SerialName("ultimo_puerto") val ultimoPuerto: Int = 0,
)
