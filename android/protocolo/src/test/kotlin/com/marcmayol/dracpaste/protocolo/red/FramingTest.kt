package com.marcmayol.dracpaste.protocolo.red

import com.marcmayol.dracpaste.protocolo.Protocolo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class FramingTest {

    @Test
    fun `un frame escrito se vuelve a leer igual`() {
        val payload = "un clip cualquiera".toByteArray()
        val buffer = ByteArrayOutputStream()
        Framing.escribir(buffer, payload)

        assertArrayEquals(payload, Framing.leer(ByteArrayInputStream(buffer.toByteArray())))
    }

    @Test
    fun `la cabecera son cuatro bytes big-endian`() {
        val buffer = ByteArrayOutputStream()
        Framing.escribir(buffer, ByteArray(258))
        val bytes = buffer.toByteArray()

        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(1, bytes[2].toInt())
        assertEquals(2, bytes[3].toInt())
        assertEquals(4 + 258, bytes.size)
    }

    @Test
    fun `dos frames seguidos se separan bien`() {
        // El caso que justifica que haya framing: TCP entrega bytes, no mensajes, y dos
        // clips copiados seguidos pueden llegar pegados en la misma lectura.
        val buffer = ByteArrayOutputStream()
        Framing.escribir(buffer, "primero".toByteArray())
        Framing.escribir(buffer, "segundo".toByteArray())

        val entrada = ByteArrayInputStream(buffer.toByteArray())
        assertEquals("primero", String(Framing.leer(entrada)))
        assertEquals("segundo", String(Framing.leer(entrada)))
    }

    @Test
    fun `un frame partido en trozos se reensambla`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val buffer = ByteArrayOutputStream()
        Framing.escribir(buffer, payload)

        // Un flujo que entrega los bytes de tres en tres, como haría una red lenta.
        val tacaneo = object : InputStream() {
            private val datos = buffer.toByteArray()
            private var pos = 0
            override fun read(): Int = if (pos < datos.size) datos[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= datos.size) return -1
                val n = minOf(3, len, datos.size - pos)
                System.arraycopy(datos, pos, b, off, n)
                pos += n
                return n
            }
        }

        assertArrayEquals(payload, Framing.leer(tacaneo))
    }

    @Test
    fun `una longitud desmedida se rechaza sin reservar memoria`() {
        // Cualquiera en la red puede abrir un socket y mandar estos cuatro bytes: si se
        // creyeran, serían casi 2 GB de reserva de memoria.
        val cabecera = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try {
            Framing.leer(ByteArrayInputStream(cabecera))
            fail("Se esperaba un rechazo por longitud excesiva")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("máximo"))
        }
    }

    @Test
    fun `una longitud de cero se rechaza`() {
        try {
            Framing.leer(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
            fail("Se esperaba un rechazo por longitud cero")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("inválida"))
        }
    }

    @Test
    fun `no se escriben frames vacios`() {
        try {
            Framing.escribir(ByteArrayOutputStream(), ByteArray(0))
            fail("Se esperaba un rechazo del frame vacío")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("vacíos"))
        }
    }

    @Test
    fun `no se escriben frames por encima del maximo`() {
        try {
            Framing.escribir(ByteArrayOutputStream(), ByteArray(Protocolo.MAX_FRAME_BYTES + 1))
            fail("Se esperaba un rechazo por tamaño")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("máximo"))
        }
    }

    @Test
    fun `una conexion cortada a medias se distingue de un error de protocolo`() {
        // El otro extremo anuncia 100 bytes y se va tras 10. Eso es una desconexión, no
        // un mensaje mal formado: quien llama tiene que poder reconectar en vez de
        // desemparejar.
        val buffer = ByteArrayOutputStream()
        Framing.escribir(buffer, ByteArray(100))
        val truncado = buffer.toByteArray().copyOfRange(0, 14)

        try {
            Framing.leer(ByteArrayInputStream(truncado))
            fail("Se esperaba un fin de flujo")
        } catch (e: EOFException) {
            assertTrue(e.message!!.contains("cerró"))
        }
    }
}
