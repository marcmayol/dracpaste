package com.marcmayol.dracpaste.protocolo.sesion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaquinaEstadosTest {

    @Test
    fun `empieza sin emparejar`() {
        assertEquals(EstadoConexion.SIN_EMPAREJAR, MaquinaEstados().estado)
    }

    @Test
    fun `el camino feliz completo`() {
        val m = MaquinaEstados()
        assertEquals(EstadoConexion.BUSCANDO, m.procesar(Evento.Emparejado))
        assertEquals(EstadoConexion.CONECTANDO, m.procesar(Evento.PcLocalizado("192.168.1.40", 47653)))
        assertEquals(EstadoConexion.CONECTADO, m.procesar(Evento.SesionEstablecida))
        assertTrue(m.puedeEnviar())
    }

    @Test
    fun `solo se envia estando conectado`() {
        val m = MaquinaEstados()
        assertFalse(m.puedeEnviar())
        m.procesar(Evento.Emparejado)
        assertFalse(m.puedeEnviar())
        m.procesar(Evento.PcLocalizado("192.168.1.40", 47653))
        assertFalse(m.puedeEnviar())
        m.procesar(Evento.SesionEstablecida)
        assertTrue(m.puedeEnviar())
    }

    @Test
    fun `perder la conexion lleva a reconectar`() {
        val m = conectada()
        assertEquals(EstadoConexion.RECONECTANDO, m.procesar(Evento.ConexionPerdida("sin PONG")))
        assertEquals("sin PONG", m.ultimoMotivo)
        assertFalse(m.puedeEnviar())
    }

    @Test
    fun `reconectando se ataca la ultima IP conocida`() {
        val m = conectada()
        m.procesar(Evento.ConexionPerdida("socket cerrado"))

        assertEquals("192.168.1.40", m.ultimaDireccion)
        assertEquals(EstadoConexion.CONECTANDO, m.procesar(Evento.Reintentar))
    }

    @Test
    fun `sin IP conocida hay que descubrir antes de conectar`() {
        val m = MaquinaEstados()
        m.procesar(Evento.Emparejado)

        assertEquals(EstadoConexion.BUSCANDO, m.procesar(Evento.Reintentar))
    }

    @Test
    fun `cambiar de red olvida la IP anterior`() {
        // Reintentar contra la IP de la red de casa desde la del trabajo solo gasta
        // batería: no hay nadie ahí.
        val m = conectada()
        assertEquals(EstadoConexion.BUSCANDO, m.procesar(Evento.RedCambiada))
        assertNull(m.ultimaDireccion)
        assertEquals(0, m.ultimoPuerto)
    }

    @Test
    fun `cambiar de red reinicia el backoff`() {
        val m = conectada()
        repeat(5) { m.procesar(Evento.ConexionFallida("rechazada")) }
        assertTrue(m.intentosFallidos > 0)

        m.procesar(Evento.RedCambiada)
        assertEquals(0, m.intentosFallidos)
    }

    @Test
    fun `el backoff crece de forma exponencial hasta el tope`() {
        val m = MaquinaEstados(backoffInicialMs = 1_000, backoffMaximoMs = 30_000)
        m.procesar(Evento.Emparejado)
        m.procesar(Evento.PcLocalizado("192.168.1.40", 47653))

        val esperas = mutableListOf<Long>()
        repeat(8) {
            m.procesar(Evento.ConexionFallida("rechazada"))
            esperas.add(m.esperaSiguienteIntento())
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), esperas)
    }

    @Test
    fun `el backoff nunca se desborda`() {
        // Un PC apagado toda la noche acumula muchos fallos; el desplazamiento de bits
        // no puede acabar dando una espera negativa o absurda.
        val m = MaquinaEstados()
        m.procesar(Evento.Emparejado)
        repeat(500) { m.procesar(Evento.ConexionFallida("rechazada")) }

        val espera = m.esperaSiguienteIntento()
        assertTrue("Espera fuera de rango: $espera", espera in 1_000..30_000)
    }

    @Test
    fun `una sesion buena reinicia el backoff`() {
        val m = MaquinaEstados()
        m.procesar(Evento.Emparejado)
        m.procesar(Evento.PcLocalizado("192.168.1.40", 47653))
        repeat(4) { m.procesar(Evento.ConexionFallida("rechazada")) }

        m.procesar(Evento.PcLocalizado("192.168.1.40", 47653))
        m.procesar(Evento.SesionEstablecida)

        assertEquals(0, m.intentosFallidos)
        assertEquals(1_000L, m.esperaSiguienteIntento())
        assertNull(m.ultimoMotivo)
    }

    @Test
    fun `desemparejar corta desde cualquier estado`() {
        for (preparar in listOf<(MaquinaEstados) -> Unit>(
            { it.procesar(Evento.Emparejado) },
            { it.procesar(Evento.Emparejado); it.procesar(Evento.PcLocalizado("10.0.0.1", 1)) },
            { conectada() },
        )) {
            val m = MaquinaEstados()
            preparar(m)
            assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.Desemparejado))
            assertNull(m.ultimaDireccion)
        }
    }

    @Test
    fun `sin emparejar ningun evento de red hace nada`() {
        // Es lo que intentaría un impostor de la LAN anunciando el mismo servicio mDNS
        // contra un móvil que ya ha desemparejado.
        val m = MaquinaEstados()

        assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.PcLocalizado("192.168.1.99", 47653)))
        assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.SesionEstablecida))
        assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.Reintentar))
        assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.RedCambiada))
        assertEquals(EstadoConexion.SIN_EMPAREJAR, m.procesar(Evento.ConexionPerdida("x")))
        assertNull(m.ultimaDireccion)
        assertFalse(m.puedeEnviar())
    }

    @Test
    fun `reintentar estando conectado no rompe la sesion`() {
        // ACTION_SCREEN_ON llega también con la conexión sana.
        val m = conectada()
        assertEquals(EstadoConexion.CONECTADO, m.procesar(Evento.Reintentar))
    }

    @Test
    fun `reintentar mientras se conecta no lanza un segundo intento`() {
        val m = MaquinaEstados()
        m.procesar(Evento.Emparejado)
        m.procesar(Evento.PcLocalizado("192.168.1.40", 47653))

        assertEquals(EstadoConexion.CONECTANDO, m.procesar(Evento.Reintentar))
    }

    @Test
    fun `un ciclo completo de caida y recuperacion`() {
        // Lo que pasa de verdad al suspender el PC y volver a encenderlo.
        val m = conectada()
        m.procesar(Evento.ConexionPerdida("el PC se suspendió"))
        assertEquals(EstadoConexion.RECONECTANDO, m.estado)

        m.procesar(Evento.Reintentar)
        m.procesar(Evento.ConexionFallida("conexión rechazada"))
        assertEquals(EstadoConexion.RECONECTANDO, m.estado)
        assertEquals(2, m.intentosFallidos)

        m.procesar(Evento.PcLocalizado("192.168.1.41", 47653))
        m.procesar(Evento.SesionEstablecida)
        assertEquals(EstadoConexion.CONECTADO, m.estado)
        assertEquals("192.168.1.41", m.ultimaDireccion)
        assertEquals(0, m.intentosFallidos)
    }

    private fun conectada(): MaquinaEstados = MaquinaEstados().apply {
        procesar(Evento.Emparejado)
        procesar(Evento.PcLocalizado("192.168.1.40", 47653))
        procesar(Evento.SesionEstablecida)
    }
}
