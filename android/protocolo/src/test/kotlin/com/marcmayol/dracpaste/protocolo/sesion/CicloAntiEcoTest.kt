package com.marcmayol.dracpaste.protocolo.sesion

import com.marcmayol.dracpaste.protocolo.mensajes.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El bucle de eco, simulado de punta a punta con los dos dispositivos.
 *
 * Los tests de [AntiEco] comprueban la pieza por separado; estos comprueban lo que de
 * verdad importa: que **conectando las dos mitades**, un clip no rebota indefinidamente.
 * Es el fallo del plan §7 que se ve desde fuera como dos aparatos dándose el mismo texto
 * para siempre, y no se puede reproducir a mano de forma fiable.
 */
class CicloAntiEcoTest {

    /**
     * Un dispositivo con su portapapeles y su anti-eco. Modela lo justo: escribir un
     * clip recibido, y que el listener local reaccione a cualquier cambio.
     */
    private class Dispositivo(val nombre: String, private val reloj: () -> Long) {
        val antiEco = AntiEco(reloj = reloj)
        var portapapeles: String? = null
        val enviados = mutableListOf<String>()

        /** Llega un clip del otro lado: se marca y se escribe. */
        fun recibir(clip: Clip) {
            antiEco.marcarRecibido(clip.originId)
            escribirEnPortapapeles(clip.texto())
        }

        /** El usuario copia algo a mano. */
        fun copiaElUsuario(texto: String) = escribirEnPortapapeles(texto)

        /**
         * Todo cambio del portapapeles pasa por aquí, venga de donde venga: es lo que
         * hace el listener real, que no sabe quién ha escrito.
         */
        private fun escribirEnPortapapeles(texto: String) {
            portapapeles = texto
            if (antiEco.debeReenviar(Clip.origenDe(texto))) {
                enviados.add(texto)
            }
        }
    }

    private var ahora = 0L
    private val pc = Dispositivo("PC") { ahora }
    private val movil = Dispositivo("Móvil") { ahora }

    /** Entrega los clips pendientes de un lado al otro, hasta que no quede ninguno. */
    private fun repartirHasta(vueltasMaximas: Int = 20): Int {
        var vueltas = 0
        while (vueltas < vueltasMaximas) {
            val delPc = pc.enviados.toList()
            val delMovil = movil.enviados.toList()
            if (delPc.isEmpty() && delMovil.isEmpty()) return vueltas

            pc.enviados.clear()
            movil.enviados.clear()
            delPc.forEach { movil.recibir(Clip.deTexto(it)) }
            delMovil.forEach { pc.recibir(Clip.deTexto(it)) }
            vueltas++
        }
        return vueltas
    }

    @Test
    fun `una copia en el PC llega al movil y ahi se para`() {
        pc.copiaElUsuario("hola")

        val vueltas = repartirHasta()

        assertEquals("hola", movil.portapapeles)
        assertEquals("hola", pc.portapapeles)
        assertEquals("El clip debería haber dado una sola vuelta", 1, vueltas)
    }

    @Test
    fun `una copia en el movil llega al PC y ahi se para`() {
        movil.copiaElUsuario("desde el móvil")

        val vueltas = repartirHasta()

        assertEquals("desde el móvil", pc.portapapeles)
        assertEquals(1, vueltas)
    }

    @Test
    fun `copiar diez cosas seguidas no pierde ninguna ni entra en bucle`() {
        // El caso real de quien está trabajando: copia una cosa detrás de otra.
        repeat(10) { i ->
            pc.copiaElUsuario("clip $i")
            repartirHasta()
            ahora += 200
        }

        assertEquals("clip 9", movil.portapapeles)
    }

    @Test
    fun `copiar lo mismo dos veces a mano lo envia dos veces`() {
        // Si la marca no se consumiera al reconocer el eco, la segunda copia no llegaría
        // y el usuario no entendería por qué.
        pc.copiaElUsuario("repetido")
        repartirHasta()
        movil.enviados.clear()

        movil.copiaElUsuario("repetido")
        val vueltas = repartirHasta()

        assertEquals(1, vueltas)
        assertEquals("repetido", pc.portapapeles)
    }

    @Test
    fun `los dos copian a la vez y la cosa no se desmadra`() {
        // Cada uno copia algo distinto en el mismo instante. Ninguno de los dos textos
        // puede quedarse dando vueltas.
        pc.copiaElUsuario("del PC")
        movil.copiaElUsuario("del móvil")

        val vueltas = repartirHasta()

        assertTrue("Se quedó rebotando durante $vueltas vueltas", vueltas < 5)
        assertTrue(pc.enviados.isEmpty())
        assertTrue(movil.enviados.isEmpty())
    }

    @Test
    fun `pasada la ventana, volver a copiar lo mismo si viaja`() {
        // Media hora después, copiar ese mismo texto a mano es una copia legítima.
        pc.copiaElUsuario("persistente")
        repartirHasta()

        ahora += 60_000
        movil.copiaElUsuario("persistente")
        val vueltas = repartirHasta()

        assertEquals(1, vueltas)
    }

    @Test
    fun `el ciclo se corta aunque el reloj no avance`() {
        // Todo ocurre dentro del mismo milisegundo, que es lo que pasa en una red local
        // rápida: la caducidad de la marca no puede ser lo que corte el bucle.
        pc.copiaElUsuario("instantáneo")

        assertEquals(1, repartirHasta())
    }
}
