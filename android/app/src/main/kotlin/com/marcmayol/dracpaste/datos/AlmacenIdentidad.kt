package com.marcmayol.dracpaste.datos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.ParDeClaves
import com.marcmayol.dracpaste.protocolo.cripto.aHex
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La identidad de este móvil: su par de claves X25519 y su `device_id`
 * (`docs/protocol.md` §2.1).
 *
 * **Cómo se protege la clave privada.** El Android Keystore no puede guardar una clave
 * X25519 arbitraria —solo genera y custodia las suyas—, así que se hace lo que es
 * habitual en este caso: el Keystore genera una clave AES-GCM que nunca sale del
 * hardware seguro, y con ella se cifra la privada X25519 antes de escribirla en el
 * almacenamiento privado de la app.
 *
 * El resultado es que la privada solo se puede descifrar en este dispositivo y con esta
 * app instalada: una copia de seguridad del fichero no sirve de nada en otro móvil, y
 * desinstalar la app destruye la clave del Keystore.
 *
 * El `device_id` son 16 bytes aleatorios. **No** deriva del Android ID, del IMEI ni de
 * ningún identificador de hardware: sería un identificador persistente y rastreable, y
 * este proyecto va justo de lo contrario.
 */
class AlmacenIdentidad(private val contexto: Context) {

    private val fichero: File
        get() = File(contexto.filesDir, NOMBRE_FICHERO)

    /** Carga la identidad guardada o crea una nueva la primera vez. */
    fun cargarOCrear(): Identidad {
        if (fichero.exists()) {
            try {
                return leer()
            } catch (e: Exception) {
                // Sin la privada no hay nada que recuperar: los emparejamientos que
                // dependían de ella ya no valen. Se aparta el fichero en vez de
                // borrarlo, por si hace falta mirarlo.
                fichero.renameTo(File(contexto.filesDir, "$NOMBRE_FICHERO.ilegible-${System.currentTimeMillis()}"))
            }
        }

        val nueva = Identidad(
            par = Cripto.generarParDeClaves(),
            deviceId = Cripto.aleatorio(16).aHex(),
            nombre = nombreDelDispositivo(),
        )
        guardar(nueva)
        return nueva
    }

    private fun leer(): Identidad {
        val json = JSONObject(fichero.readText())
        val privada = descifrarConKeystore(
            android.util.Base64.decode(json.getString("privada_cifrada"), android.util.Base64.NO_WRAP),
            android.util.Base64.decode(json.getString("iv"), android.util.Base64.NO_WRAP),
        )
        val publica = android.util.Base64.decode(json.getString("publica"), android.util.Base64.NO_WRAP)

        // La pública se recalcula y se compara: si el fichero estuviera manipulado, los
        // emparejamientos fallarían más tarde con un error incomprensible.
        require(Cripto.igualesEnTiempoConstante(publica, Cripto.clavePublicaDe(privada))) {
            "La clave pública guardada no corresponde a la privada"
        }

        return Identidad(
            par = ParDeClaves(privada, publica),
            deviceId = json.getString("device_id"),
            nombre = json.optString("nombre", nombreDelDispositivo()),
        )
    }

    private fun guardar(identidad: Identidad) {
        val (cifrada, iv) = cifrarConKeystore(identidad.par.privada)
        val json = JSONObject().apply {
            put("device_id", identidad.deviceId)
            put("nombre", identidad.nombre)
            put("publica", android.util.Base64.encodeToString(identidad.par.publica, android.util.Base64.NO_WRAP))
            put("privada_cifrada", android.util.Base64.encodeToString(cifrada, android.util.Base64.NO_WRAP))
            put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
        }

        // Se escribe a un temporal y se mueve encima: si el móvil se queda sin batería a
        // mitad, no queda un fichero de identidad truncado que dejaría la app sin poder
        // hablar con ningún PC emparejado.
        //
        // Con Files.move y REPLACE_EXISTING, no con File.renameTo: renameTo no
        // sobrescribe un fichero que ya exista y fallaría en silencio.
        val temporal = File(contexto.filesDir, "$NOMBRE_FICHERO.tmp")
        temporal.writeText(json.toString())
        Files.move(
            temporal.toPath(),
            fichero.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    // ------------------------------------------------------------- Keystore

    private fun claveDelKeystore(): SecretKey {
        val keystore = KeyStore.getInstance(PROVEEDOR_KEYSTORE).apply { load(null) }
        (keystore.getEntry(ALIAS_CLAVE, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR_KEYSTORE)
        generador.init(
            KeyGenParameterSpec.Builder(
                ALIAS_CLAVE,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Sin exigir autenticación del usuario: el servicio tiene que poder
                // reconectar con la pantalla bloqueada, que es cuando más falta hace.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generador.generateKey()
    }

    private fun cifrarConKeystore(datos: ByteArray): Pair<ByteArray, ByteArray> {
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.ENCRYPT_MODE, claveDelKeystore())
        return cifrador.doFinal(datos) to cifrador.iv
    }

    private fun descifrarConKeystore(cifrado: ByteArray, iv: ByteArray): ByteArray {
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.DECRYPT_MODE, claveDelKeystore(), GCMParameterSpec(128, iv))
        return cifrador.doFinal(cifrado)
    }

    private fun nombreDelDispositivo(): String {
        val fabricante = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val modelo = android.os.Build.MODEL
        return if (modelo.startsWith(fabricante, ignoreCase = true)) modelo else "$fabricante $modelo"
    }

    private companion object {
        const val NOMBRE_FICHERO = "identidad.json"
        const val PROVEEDOR_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_CLAVE = "dracpaste_identidad"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
    }
}

/** Quién es este móvil de cara al protocolo. */
class Identidad(
    val par: ParDeClaves,
    val deviceId: String,
    val nombre: String,
)
