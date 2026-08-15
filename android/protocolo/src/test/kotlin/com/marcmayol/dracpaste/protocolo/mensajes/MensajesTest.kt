package com.marcmayol.dracpaste.protocolo.mensajes

import com.marcmayol.dracpaste.protocolo.Protocolo
import com.marcmayol.dracpaste.protocolo.red.ProtocoloException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MensajesTest {

    @Test
    fun `un clip va y vuelve con su texto intacto`() {
        val clip = Clip.deTexto("hola mundo", timestampMs = 1_755_100_000_000)
        val decodificado = CodecMensajes.decodificar(CodecMensajes.codificar(clip)) as Clip

        assertEquals("hola mundo", decodificado.texto())
        assertEquals(Protocolo.TIPO_TEXTO, decodificado.type)
        assertEquals(1_755_100_000_000, decodificado.timestampMs)
        assertEquals(clip.originId, decodificado.originId)
    }

    @Test
    fun `los acentos y emojis sobreviven al viaje`() {
        // El caso real: se copia un texto en catalán o con un emoji y llega mutilado
        // porque alguien asumió ASCII en algún punto del camino.
        val original = "Això és una prova — ñ, ü, 汉字 y 🐉"
        val decodificado = CodecMensajes.decodificar(
            CodecMensajes.codificar(Clip.deTexto(original)),
        ) as Clip

        assertEquals(original, decodificado.texto())
    }

    @Test
    fun `el origin_id depende solo del contenido`() {
        val a = Clip.deTexto("mismo texto", timestampMs = 1)
        val b = Clip.deTexto("mismo texto", timestampMs = 999_999)
        val c = Clip.deTexto("otro texto", timestampMs = 1)

        assertEquals(a.originId, b.originId)
        assertTrue(a.originId != c.originId)
        assertEquals(32, a.originId.length) // 16 bytes en hex
    }

    @Test
    fun `el origin_id es estable entre ejecuciones`() {
        // Vale como vector de prueba para el lado de C#: si allí sale otro hash, el
        // anti-eco no funcionaría y los clips rebotarían entre los dos dispositivos.
        assertEquals(
            "b221d9dbb083a7f33428d7c2a3c3198a",
            Clip.origenDe("hola"),
        )
    }

    @Test
    fun `no se crean clips vacios`() {
        try {
            Clip.deTexto("")
            fail("Se esperaba el rechazo del clip vacío")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("vacíos"))
        }
    }

    @Test
    fun `no se crean clips por encima del maximo`() {
        try {
            Clip.deTexto("a".repeat(Protocolo.MAX_CLIP_BYTES + 1))
            fail("Se esperaba el rechazo por tamaño")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("máximo"))
        }
    }

    @Test
    fun `el maximo se mide en bytes, no en caracteres`() {
        // Un emoji ocupa cuatro bytes: contar caracteres dejaría pasar clips de cuatro
        // veces el máximo.
        val justoPorEncima = "🐉".repeat(Protocolo.MAX_CLIP_BYTES / 4 + 1)
        try {
            Clip.deTexto(justoPorEncima)
            fail("Se esperaba el rechazo por tamaño en bytes")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("máximo"))
        }
    }

    @Test
    fun `los mensajes del handshake van y vuelven`() {
        val hello = Hello(deviceId = "aabb", challenge = "Y2hhbGxlbmdl")
        assertEquals(hello, CodecMensajes.decodificar(CodecMensajes.codificar(hello)))

        val serverHello = ServerHello(deviceId = "ccdd", challenge = "b3Rybw==")
        assertEquals(serverHello, CodecMensajes.decodificar(CodecMensajes.codificar(serverHello)))

        val auth = Auth(echo = "ZWNv")
        assertEquals(auth, CodecMensajes.decodificar(CodecMensajes.codificar(auth)))

        val authOk = AuthOk(echo = "ZWNv")
        assertEquals(authOk, CodecMensajes.decodificar(CodecMensajes.codificar(authOk)))
    }

    @Test
    fun `los mensajes de emparejamiento van y vuelven`() {
        val peticion = PairRequest(
            pk = "cGs=",
            deviceId = "0011",
            name = "Pixel",
            token = "dG9rZW4=",
        )
        assertEquals(peticion, CodecMensajes.decodificar(CodecMensajes.codificar(peticion)))

        val confirmacion = PairConfirm(deviceId = "2233", name = "PC", fingerprint = "A3F2-9C71")
        assertEquals(confirmacion, CodecMensajes.decodificar(CodecMensajes.codificar(confirmacion)))

        val ack = PairAck(fingerprint = "A3F2-9C71")
        assertEquals(ack, CodecMensajes.decodificar(CodecMensajes.codificar(ack)))
    }

    @Test
    fun `ping y pong conservan la secuencia`() {
        val ping = Ping(seq = 42)
        assertEquals(ping, CodecMensajes.decodificar(CodecMensajes.codificar(ping)))
        val pong = Pong(seq = 42)
        assertEquals(pong, CodecMensajes.decodificar(CodecMensajes.codificar(pong)))
    }

    @Test
    fun `un tipo desconocido no rompe la sesion`() {
        // Una versión futura hablando de imágenes no puede tirar la conexión de un
        // cliente v1: se ignora y se sigue.
        val mensaje = CodecMensajes.decodificar("""{"t":"IMAGEN","datos":"..."}""".toByteArray())

        assertTrue(mensaje is MensajeDesconocido)
        assertEquals("IMAGEN", mensaje.t)
    }

    @Test
    fun `un campo nuevo en un tipo conocido no rompe nada`() {
        val json = """{"t":"PING","seq":7,"campo_del_futuro":true}"""
        assertEquals(Ping(seq = 7), CodecMensajes.decodificar(json.toByteArray()))
    }

    @Test
    fun `un clip de tipo no soportado se reconoce como tal`() {
        val json = """{"t":"CLIP","type":"image/png","payload":"AAAA","timestamp_ms":1,"origin_id":"ab"}"""
        val clip = CodecMensajes.decodificar(json.toByteArray()) as Clip

        assertTrue(!clip.esTexto())
    }

    @Test
    fun `un JSON invalido se rechaza como error de protocolo`() {
        try {
            CodecMensajes.decodificar("esto no es json".toByteArray())
            fail("Se esperaba un error de protocolo")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `un mensaje sin campo t se rechaza`() {
        try {
            CodecMensajes.decodificar("""{"seq":1}""".toByteArray())
            fail("Se esperaba un error de protocolo")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("'t'"))
        }
    }

    @Test
    fun `un mensaje conocido con la forma equivocada se rechaza`() {
        try {
            CodecMensajes.decodificar("""{"t":"PING"}""".toByteArray())
            fail("Se esperaba un error de protocolo")
        } catch (e: ProtocoloException) {
            assertTrue(e.message!!.contains("PING"))
        }
    }

    @Test
    fun `el campo t viaja con el nombre exacto del protocolo`() {
        val json = String(CodecMensajes.codificar(Ping(seq = 1)))
        assertTrue("El discriminador debe ser 't'", json.contains("\"t\":\"PING\""))
        assertTrue(json.contains("\"seq\":1"))
    }

    @Test
    fun `los nombres con guion bajo se respetan en el cable`() {
        // timestamp_ms y origin_id se llaman así en docs/protocol.md; si Kotlin los
        // serializara en camelCase, el lado de C# no los encontraría.
        val json = String(CodecMensajes.codificar(Clip.deTexto("x", timestampMs = 5)))
        assertTrue(json.contains("\"timestamp_ms\":5"))
        assertTrue(json.contains("\"origin_id\":"))

        val jsonHello = String(CodecMensajes.codificar(Hello(deviceId = "ab", challenge = "cd")))
        assertTrue(jsonHello.contains("\"device_id\":\"ab\""))
    }
}
