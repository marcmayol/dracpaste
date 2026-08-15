package com.marcmayol.dracpaste

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marcmayol.dracpaste.datos.AlmacenIdentidad
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import com.marcmayol.dracpaste.protocolo.cripto.aHex
import com.marcmayol.dracpaste.protocolo.cripto.desdeHex
import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import com.marcmayol.dracpaste.protocolo.mensajes.CodecMensajes
import com.marcmayol.dracpaste.protocolo.sesion.DatosQr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Los vectores del protocolo, ejecutados **en un dispositivo y sobre el APK minificado**.
 *
 * **Por qué hace falta este test si ya hay uno igual en el JVM.** Porque comprueban cosas
 * distintas. El del JVM demuestra que el algoritmo está bien escrito; este demuestra que
 * *sigue funcionando después de que R8 pase por encima*.
 *
 * Bouncy Castle carga parte de sus piezas por reflexión y R8 no puede verlas: si las
 * reglas de `proguard-rules.pro` se quedaran cortas, la app compilaría igual, instalaría
 * igual, arrancaría igual… y fallaría al emparejar, sin ningún error que explicara por
 * qué. Es de los fallos más caros que puede tener este proyecto, porque solo aparece en la
 * versión que se publica.
 *
 * Por eso `testBuildType` es `release`.
 */
@RunWith(AndroidJUnit4::class)
class CriptoMinificadaTest {

    private val privMovil = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".desdeHex()
    private val privPc = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".desdeHex()
    private val pubMovil = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".desdeHex()
    private val pubPc = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".desdeHex()

    @Test
    fun x25519SigueFuncionandoTrasMinificar() {
        assertArrayEquals(pubMovil, Cripto.clavePublicaDe(privMovil))
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            Cripto.secretoCompartido(privMovil, pubPc).aHex(),
        )
    }

    @Test
    fun laClaveDeParSigueSiendoLaMisma() {
        assertEquals(
            "7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74",
            Derivacion.clavePar(privMovil, pubPc).aHex(),
        )
    }

    @Test
    fun chaCha20Poly1305SigueProduciendoLosMismosBytes() {
        val clave = "f0dbcb2507a2f78763fb7fda468ffc6a9fc8a55630153130d0725f5ac54d66f3".desdeHex()
        val cifrado = Cripto.cifrar(clave, Cripto.nonceDeContador(0), "hola".toByteArray())

        assertEquals("678e67f72a09b0970f17bb20686f7545b9f5b1bb", cifrado.aHex())
        assertEquals("hola", String(Cripto.descifrar(clave, Cripto.nonceDeContador(0), cifrado)))
    }

    @Test
    fun laHuellaSigueSiendoLaMisma() {
        assertEquals("9962-5B51", Derivacion.huella(pubMovil, pubPc))
    }

    @Test
    fun elOriginIdSigueSiendoElMismo() {
        // Si R8 rompiera esto, el anti-eco dejaría de reconocer los clips del PC y los dos
        // dispositivos se los devolverían indefinidamente.
        assertEquals("b221d9dbb083a7f33428d7c2a3c3198a", Clip.origenDe("hola"))
    }

    @Test
    fun losMensajesSeSerializanIgual() {
        // kotlinx.serialization genera los serializadores por nombre de clase: es lo
        // primero que se rompe cuando R8 renombra sin las reglas adecuadas.
        val clip = Clip.deTexto("Això és una prova 🐉", timestampMs = 1_755_100_000_000)
        val json = String(CodecMensajes.codificar(clip))

        assertTrue("El discriminador debe seguir siendo 't'", json.contains("\"t\":\"CLIP\""))
        assertTrue(json.contains("\"timestamp_ms\":1755100000000"))
        assertTrue(json.contains("\"origin_id\":"))

        val decodificado = CodecMensajes.decodificar(json.toByteArray()) as Clip
        assertEquals("Això és una prova 🐉", decodificado.texto())
    }

    @Test
    fun elQrSeLeeIgual() {
        val original = DatosQr(
            pk = Base64.getEncoder().encodeToString(pubPc),
            ip = "192.168.1.40",
            port = 47653,
            token = Base64.getEncoder().encodeToString(Cripto.aleatorio(16)),
            name = "PC-DESPACHO",
            deviceId = "3b0636ae3ff926a9d75caa0033b3db2a",
        )

        assertEquals(original, DatosQr.leer(original.aSerializar()))
    }

    @Test
    fun elKeystoreDelDispositivoGuardaYDevuelveLaIdentidad() {
        // Esto solo se puede probar en un dispositivo: el Android Keystore no existe en el
        // JVM. Se comprueba que la privada envuelta por la clave AES del hardware seguro
        // vuelve intacta.
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val almacen = AlmacenIdentidad(contexto)

        val primera = almacen.cargarOCrear()
        val segunda = almacen.cargarOCrear()

        assertEquals(primera.deviceId, segunda.deviceId)
        assertArrayEquals(primera.par.privada, segunda.par.privada)
        assertArrayEquals(
            "La pública guardada no corresponde a la privada",
            primera.par.publica,
            Cripto.clavePublicaDe(segunda.par.privada),
        )
        assertEquals(32, primera.deviceId.length)
    }
}
