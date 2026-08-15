package com.marcmayol.dracpaste.protocolo.cripto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectores de prueba de `docs/protocol.md` §7.
 *
 * Este fichero tiene un gemelo en C# (`VectoresProtocoloTests`) con **las mismas
 * constantes**. Es lo que sustituye a "conectar un móvil y ver si se entienden": si
 * alguna de las dos implementaciones se desvía del protocolo, uno de los dos ficheros
 * se pone en rojo sin que haga falta hardware (`docs/decisions.md` D-003).
 *
 * Si cambia un valor de aquí, cambia el protocolo: primero el documento, después los
 * dos ficheros de test.
 */
class VectoresProtocoloTest {

    private val privMovil = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".desdeHex()
    private val privPc = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".desdeHex()
    private val pubMovil = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".desdeHex()
    private val pubPc = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".desdeHex()
    private val retoMovil = "000102030405060708090a0b0c0d0e0f".desdeHex()
    private val retoPc = "101112131415161718191a1b1c1d1e1f".desdeHex()

    @Test
    fun `las publicas salen de las privadas segun RFC 7748`() {
        assertArrayEquals(pubMovil, Cripto.clavePublicaDe(privMovil))
        assertArrayEquals(pubPc, Cripto.clavePublicaDe(privPc))
    }

    @Test
    fun `el secreto compartido coincide con el del RFC y es el mismo por ambos lados`() {
        val esperado = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(esperado, Cripto.secretoCompartido(privMovil, pubPc).aHex())
        assertEquals(esperado, Cripto.secretoCompartido(privPc, pubMovil).aHex())
    }

    @Test
    fun `la clave de par es la misma en el movil y en el PC`() {
        val desdeMovil = Derivacion.clavePar(privMovil, pubPc)
        val desdePc = Derivacion.clavePar(privPc, pubMovil)
        assertArrayEquals(desdeMovil, desdePc)
        assertEquals(VECTOR_CLAVE_PAR, desdeMovil.aHex())
    }

    @Test
    fun `las claves de sesion coinciden con el vector fijado`() {
        val claves = Derivacion.clavesDeSesion(VECTOR_CLAVE_PAR.desdeHex(), retoMovil, retoPc)
        assertEquals(VECTOR_CLAVE_M2P, claves.movilAPc.aHex())
        assertEquals(VECTOR_CLAVE_P2M, claves.pcAMovil.aHex())
    }

    @Test
    fun `las dos direcciones usan claves distintas`() {
        val claves = Derivacion.clavesDeSesion(VECTOR_CLAVE_PAR.desdeHex(), retoMovil, retoPc)
        assertTrue(
            "Si las dos direcciones compartieran clave, un mensaje del PC podría " +
                "reinyectarse como si viniera del móvil",
            !claves.movilAPc.contentEquals(claves.pcAMovil),
        )
    }

    @Test
    fun `el cifrado autenticado produce el vector fijado`() {
        val clave = VECTOR_CLAVE_M2P.desdeHex()
        val nonce = Cripto.nonceDeContador(0)
        val cifrado = Cripto.cifrar(clave, nonce, "hola".toByteArray(Charsets.UTF_8))
        assertEquals(VECTOR_CIFRADO_HOLA, cifrado.aHex())

        // Y el viaje de vuelta.
        assertEquals("hola", String(Cripto.descifrar(clave, nonce, cifrado), Charsets.UTF_8))
    }

    @Test
    fun `el nonce se construye con el contador en big-endian`() {
        assertEquals("000000000000000000000000", Cripto.nonceDeContador(0).aHex())
        assertEquals("000000000000000000000001", Cripto.nonceDeContador(1).aHex())
        assertEquals("0000000000000000000000ff", Cripto.nonceDeContador(255).aHex())
        assertEquals("000000000000000100000000", Cripto.nonceDeContador(4294967296L).aHex())
    }

    @Test
    fun `la huella es la misma se mire desde donde se mire`() {
        assertEquals(Derivacion.huella(pubMovil, pubPc), Derivacion.huella(pubPc, pubMovil))
        assertEquals(VECTOR_HUELLA, Derivacion.huella(pubMovil, pubPc))
    }

    @Test
    fun `los retos de emparejamiento se derivan del token`() {
        val token = "0f0e0d0c0b0a09080706050403020100".desdeHex()
        val (delMovil, delPc) = Derivacion.retosDeEmparejamiento(token)
        assertEquals(16, delMovil.size)
        assertEquals(16, delPc.size)
        assertEquals(VECTOR_RETO_EMPAREJAMIENTO_MOVIL, delMovil.aHex())
        assertEquals(VECTOR_RETO_EMPAREJAMIENTO_PC, delPc.aHex())
    }

    companion object {
        // Estos valores están fijados en docs/protocol.md §7 y replicados en el test
        // de C#. No se tocan sin cambiar el protocolo.
        const val VECTOR_CLAVE_PAR =
            "7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74"
        const val VECTOR_CLAVE_M2P =
            "f0dbcb2507a2f78763fb7fda468ffc6a9fc8a55630153130d0725f5ac54d66f3"
        const val VECTOR_CLAVE_P2M =
            "bd975ac0e20687bfa1dd130670c6659a2a1f8854fa1c924870f5e482814a4715"
        const val VECTOR_CIFRADO_HOLA = "678e67f72a09b0970f17bb20686f7545b9f5b1bb"
        const val VECTOR_HUELLA = "9962-5B51"
        const val VECTOR_RETO_EMPAREJAMIENTO_MOVIL = "5af8673472d05d3ccd761485d419b651"
        const val VECTOR_RETO_EMPAREJAMIENTO_PC = "6bbd531bbad346bd2162feb25261c2e2"
    }
}
