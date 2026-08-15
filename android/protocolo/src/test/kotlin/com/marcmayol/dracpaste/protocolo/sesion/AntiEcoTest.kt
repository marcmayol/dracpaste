package com.marcmayol.dracpaste.protocolo.sesion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiEcoTest {

    private var ahora = 1_000L
    private val antiEco = AntiEco(ventanaMs = 5_000, reloj = { ahora })

    @Test
    fun `un cambio local sin nada recibido antes se reenvia`() {
        assertTrue(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `el eco de lo que se acaba de escribir no se reenvia`() {
        // El bucle que esto corta: el PC manda un clip, el móvil lo escribe, su propio
        // listener lo ve como cambio y lo devolvería al PC indefinidamente.
        antiEco.marcarRecibido("abc")
        assertFalse(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `un clip distinto si se reenvia aunque se acabe de recibir otro`() {
        antiEco.marcarRecibido("abc")
        assertTrue(antiEco.debeReenviar("def"))
    }

    @Test
    fun `copiar dos veces a mano el mismo texto lo envia dos veces`() {
        // La marca se consume al reconocer el eco. Si no lo hiciera, el usuario que
        // copia lo mismo dos veces seguidas vería que la segunda no llega.
        antiEco.marcarRecibido("abc")
        assertFalse(antiEco.debeReenviar("abc"))
        assertTrue(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `la marca caduca`() {
        // Media hora después, copiar ese mismo texto a mano es una copia legítima.
        antiEco.marcarRecibido("abc")
        ahora += 5_001
        assertTrue(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `justo dentro de la ventana sigue valiendo`() {
        antiEco.marcarRecibido("abc")
        ahora += 5_000
        assertFalse(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `olvidar deja pasar cualquier cosa`() {
        antiEco.marcarRecibido("abc")
        antiEco.olvidar()
        assertTrue(antiEco.debeReenviar("abc"))
    }

    @Test
    fun `solo cuenta el ultimo recibido`() {
        antiEco.marcarRecibido("uno")
        antiEco.marcarRecibido("dos")

        assertTrue(antiEco.debeReenviar("uno"))
    }

    @Test
    fun `un ida y vuelta completo no entra en bucle`() {
        // Simulación del ciclo real: llega un clip del PC, se escribe, el listener lo
        // detecta y pregunta si reenviar. Repetido tres veces, no debe reenviarse nunca.
        repeat(3) { i ->
            val origen = "clip-$i"
            antiEco.marcarRecibido(origen)
            assertFalse("La vuelta $i entró en bucle", antiEco.debeReenviar(origen))
            ahora += 100
        }
    }
}
