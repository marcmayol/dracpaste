package com.marcmayol.dracpaste.datos

import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import com.marcmayol.dracpaste.protocolo.cripto.Derivacion
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * El registro de PCs corre en el JVM, sin emulador: por eso recibe una carpeta en vez de
 * un Context y persiste con kotlinx.serialization en lugar de `org.json`, que en los
 * tests unitarios de Android es un stub que lanza excepciones.
 */
class RegistroPcsTest {

    private lateinit var carpeta: File
    private lateinit var registro: RegistroPcs

    @Before
    fun preparar() {
        carpeta = File(System.getProperty("java.io.tmpdir"), "dracpaste-${System.nanoTime()}")
        carpeta.mkdirs()
        registro = RegistroPcs(carpeta)
    }

    @After
    fun limpiar() {
        carpeta.deleteRecursively()
    }

    private fun pc(id: String, nombre: String = "PC", publica: ByteArray = Cripto.generarParDeClaves().publica) =
        PcEmparejado(
            deviceId = id,
            nombre = nombre,
            publicaBase64 = Base64.getEncoder().encodeToString(publica),
            huella = "A3F2-9C71",
        )

    @Test
    fun `sin nada guardado no hay PCs`() {
        assertTrue(registro.todos().isEmpty())
        assertNull(registro.activo())
    }

    @Test
    fun `lo guardado sobrevive al reinicio de la app`() {
        registro.guardar(pc("aaaa", "PC-DESPACHO"))

        val recargado = RegistroPcs(carpeta)

        assertEquals(1, recargado.todos().size)
        assertEquals("PC-DESPACHO", recargado.buscar("aaaa")!!.nombre)
    }

    @Test
    fun `el primer PC emparejado pasa a ser el activo`() {
        // Si no lo hiciera, el usuario tendría que ir a ajustes a elegirlo antes de que
        // la app sirviera para algo.
        registro.guardar(pc("aaaa"))

        assertEquals("aaaa", registro.activo()!!.deviceId)
        assertTrue(registro.buscar("aaaa")!!.activo)
    }

    @Test
    fun `el segundo PC no roba el destino activo`() {
        registro.guardar(pc("aaaa", "Sobremesa"))
        registro.guardar(pc("bbbb", "Portátil"))

        assertEquals("aaaa", registro.activo()!!.deviceId)
        assertFalse(registro.buscar("bbbb")!!.activo)
    }

    @Test
    fun `solo puede haber un activo a la vez`() {
        registro.guardar(pc("aaaa"))
        registro.guardar(pc("bbbb"))

        registro.marcarActivo("bbbb")

        assertEquals(1, registro.todos().count { it.activo })
        assertEquals("bbbb", registro.activo()!!.deviceId)
    }

    @Test
    fun `reemparejar el PC activo no le quita el puesto`() {
        // Vuelve a emparejarse el mismo PC (por ejemplo tras reinstalarlo): el usuario
        // no esperaría que sus clips empezaran a ir a otro sitio.
        registro.guardar(pc("aaaa"))
        registro.guardar(pc("bbbb"))
        registro.marcarActivo("aaaa")

        registro.guardar(pc("aaaa", "PC-DESPACHO"))

        assertEquals("aaaa", registro.activo()!!.deviceId)
        assertEquals("PC-DESPACHO", registro.buscar("aaaa")!!.nombre)
    }

    @Test
    fun `desemparejar el activo pasa el relevo a otro`() {
        // Dejar la app sin destino habiendo PCs disponibles solo confundiría al usuario.
        registro.guardar(pc("aaaa"))
        registro.guardar(pc("bbbb"))

        assertTrue(registro.olvidar("aaaa"))

        assertEquals("bbbb", registro.activo()!!.deviceId)
        assertTrue(registro.buscar("bbbb")!!.activo)
    }

    @Test
    fun `desemparejar el ultimo deja la app sin destino`() {
        registro.guardar(pc("aaaa"))

        registro.olvidar("aaaa")

        assertNull(registro.activo())
        assertTrue(registro.todos().isEmpty())
    }

    @Test
    fun `olvidar lo que no esta no rompe nada`() {
        registro.guardar(pc("aaaa"))

        assertFalse(registro.olvidar("no-existe"))
        assertEquals(1, registro.todos().size)
    }

    @Test
    fun `la clave de par sale de lo guardado y coincide con la del PC`() {
        // No se guarda ningún secreto: la clave de par se recalcula desde la privada del
        // móvil y la pública del PC cada vez que hace falta.
        val movil = Cripto.generarParDeClaves()
        val pcClaves = Cripto.generarParDeClaves()
        val identidad = Identidad(movil, "1111", "Pixel")
        registro.guardar(pc("aaaa", publica = pcClaves.publica))

        val enElMovil = registro.claveParDe(registro.buscar("aaaa")!!, identidad)
        val enElPc = Derivacion.clavePar(pcClaves.privada, movil.publica)

        assertArrayEquals(enElPc, enElMovil)
    }

    @Test
    fun `se recuerda donde estaba el PC`() {
        // Es lo que permite reintentar contra la última IP conocida mientras mDNS busca.
        registro.guardar(pc("aaaa"))

        registro.recordarDireccion("aaaa", "192.168.1.40", 47653)

        val guardado = RegistroPcs(carpeta).buscar("aaaa")!!
        assertEquals("192.168.1.40", guardado.ultimaIp)
        assertEquals(47653, guardado.ultimoPuerto)
    }

    @Test
    fun `recordar la direccion no cambia quien es el activo`() {
        registro.guardar(pc("aaaa"))
        registro.guardar(pc("bbbb"))

        registro.recordarDireccion("bbbb", "192.168.1.50", 47653)

        assertEquals("aaaa", registro.activo()!!.deviceId)
        assertEquals(1, registro.todos().count { it.activo })
    }

    @Test
    fun `el fichero no contiene secretos`() {
        registro.guardar(pc("aaaa"))
        val contenido = File(carpeta, "pcs.json").readText()

        assertTrue(contenido.contains("publica"))
        assertFalse(contenido.contains("privada"))
    }

    @Test
    fun `un registro corrupto no impide arrancar`() {
        File(carpeta, "pcs.json").writeText("{no es json")

        assertTrue(registro.todos().isEmpty())
        assertEquals(1, carpeta.listFiles { f -> f.name.startsWith("pcs.json.ilegible-") }!!.size)
    }

    @Test
    fun `tras un registro corrupto se puede volver a emparejar`() {
        File(carpeta, "pcs.json").writeText("{no es json")
        registro.todos()

        registro.guardar(pc("aaaa"))

        assertEquals("aaaa", registro.activo()!!.deviceId)
    }
}
