package com.marcmayol.dracpaste.protocolo.red

import com.marcmayol.dracpaste.protocolo.cripto.AutenticacionFallidaException
import com.marcmayol.dracpaste.protocolo.cripto.Cripto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SobreCifradoTest {

    private val clave = ByteArray(32) { it.toByte() }
    private val otraClave = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `lo sellado se abre igual`() {
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)
        val mensaje = "un clip".toByteArray()

        assertArrayEquals(mensaje, receptor.abrir(emisor.sellar(mensaje)))
    }

    @Test
    fun `el contador avanza y viaja en la cabecera`() {
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)

        repeat(5) { i ->
            val sobre = emisor.sellar("mensaje $i".toByteArray())
            assertEquals(i.toLong(), leerContador(sobre))
            assertEquals("mensaje $i", String(receptor.abrir(sobre)))
        }
        assertEquals(5L, emisor.contadorSalida)
        assertEquals(4L, receptor.ultimoContadorAceptado)
    }

    @Test
    fun `dos mensajes iguales producen bytes distintos`() {
        // Si el nonce no avanzara, dos clips idénticos darían el mismo cifrado y
        // cualquiera en la red vería que se ha copiado dos veces lo mismo.
        val emisor = SobreCifrado(clave)
        val primero = emisor.sellar("igual".toByteArray())
        val segundo = emisor.sellar("igual".toByteArray())

        assertTrue(!primero.contentEquals(segundo))
    }

    @Test
    fun `un sobre repetido se rechaza`() {
        // El ataque que esto evita: alguien graba el sobre de un clip y lo reinyecta
        // más tarde para que ese texto vuelva a aparecer en el portapapeles.
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)
        val sobre = emisor.sellar("una vez".toByteArray())

        receptor.abrir(sobre)
        try {
            receptor.abrir(sobre)
            fail("Se esperaba el rechazo de la repetición")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("repetido"))
        }
    }

    @Test
    fun `un contador que retrocede se rechaza`() {
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)
        val primero = emisor.sellar("uno".toByteArray())
        val segundo = emisor.sellar("dos".toByteArray())

        receptor.abrir(segundo)
        try {
            receptor.abrir(primero)
            fail("Se esperaba el rechazo del contador retrocedido")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("retrocedido"))
        }
    }

    @Test
    fun `se aceptan huecos en el contador`() {
        // Un frame perdido no debe bloquear la sesión: solo se exige que el contador
        // avance, no que sea consecutivo.
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)

        emisor.sellar("se pierde".toByteArray())
        val siguiente = emisor.sellar("llega".toByteArray())

        assertEquals("llega", String(receptor.abrir(siguiente)))
    }

    @Test
    fun `con la clave equivocada no se abre`() {
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(otraClave)

        try {
            receptor.abrir(emisor.sellar("secreto".toByteArray()))
            fail("Se esperaba un fallo de autenticación")
        } catch (e: AutenticacionFallidaException) {
            assertTrue(e.message!!.contains("integridad"))
        }
    }

    @Test
    fun `un byte manipulado invalida el sobre`() {
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)
        val sobre = emisor.sellar("no me toques".toByteArray())
        sobre[12] = (sobre[12].toInt() xor 0x01).toByte()

        try {
            receptor.abrir(sobre)
            fail("Se esperaba un fallo de autenticación")
        } catch (e: AutenticacionFallidaException) {
            assertTrue(e.message!!.contains("integridad"))
        }
    }

    @Test
    fun `manipular el contador tambien invalida el sobre`() {
        // El contador va en claro, pero cambiarlo cambia el nonce y el tag deja de
        // cuadrar: queda autenticado sin necesidad de meterlo en el AAD.
        val emisor = SobreCifrado(clave)
        val receptor = SobreCifrado(clave)
        val sobre = emisor.sellar("intacto".toByteArray())
        sobre[7] = 9

        try {
            receptor.abrir(sobre)
            fail("Se esperaba un fallo de autenticación")
        } catch (e: AutenticacionFallidaException) {
            assertTrue(e.message!!.contains("integridad"))
        }
    }

    @Test
    fun `un sobre mas corto que su propio tag se rechaza`() {
        try {
            SobreCifrado(clave).abrir(ByteArray(10))
            fail("Se esperaba el rechazo por tamaño")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("corto"))
        }
    }

    @Test
    fun `el nonce del sobre coincide con el del contador`() {
        val emisor = SobreCifrado(clave)
        val sobre = emisor.sellar("comprobacion".toByteArray())

        // Descifrando a mano con el nonce derivado del contador se obtiene lo mismo.
        val nonce = Cripto.nonceDeContador(leerContador(sobre))
        val descifrado = Cripto.descifrar(clave, nonce, sobre.copyOfRange(8, sobre.size))
        assertEquals("comprobacion", String(descifrado))
    }

    private fun leerContador(sobre: ByteArray): Long {
        var valor = 0L
        for (i in 0 until 8) valor = (valor shl 8) or (sobre[i].toLong() and 0xFF)
        return valor
    }
}
