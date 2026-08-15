package com.marcmayol.dracpaste.protocolo.cripto

/**
 * Utilidad de desarrollo: imprime los valores derivados de los vectores de prueba para
 * poder fijarlos en `docs/protocol.md` §7 y en los tests de ambos lados.
 *
 *   ./gradlew :protocolo:imprimirVectores
 */
object ImprimirVectores {
    @JvmStatic
    fun main(args: Array<String>) {
        val privMovil = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".desdeHex()
        val privPc = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".desdeHex()
        val pubMovil = Cripto.clavePublicaDe(privMovil)
        val pubPc = Cripto.clavePublicaDe(privPc)
        val retoMovil = "000102030405060708090a0b0c0d0e0f".desdeHex()
        val retoPc = "101112131415161718191a1b1c1d1e1f".desdeHex()

        val clavePar = Derivacion.clavePar(privMovil, pubPc)
        val sesion = Derivacion.clavesDeSesion(clavePar, retoMovil, retoPc)
        val cifrado = Cripto.cifrar(sesion.movilAPc, Cripto.nonceDeContador(0), "hola".toByteArray())
        val (retoEmpMovil, retoEmpPc) =
            Derivacion.retosDeEmparejamiento("0f0e0d0c0b0a09080706050403020100".desdeHex())

        println("pub_movil    = ${pubMovil.aHex()}")
        println("pub_pc       = ${pubPc.aHex()}")
        println("shared       = ${Cripto.secretoCompartido(privMovil, pubPc).aHex()}")
        println("CLAVE_PAR    = ${clavePar.aHex()}")
        println("CLAVE_M2P    = ${sesion.movilAPc.aHex()}")
        println("CLAVE_P2M    = ${sesion.pcAMovil.aHex()}")
        println("CIFRADO      = ${cifrado.aHex()}")
        println("HUELLA       = ${Derivacion.huella(pubMovil, pubPc)}")
        println("RETO_EMP_M   = ${retoEmpMovil.aHex()}")
        println("RETO_EMP_P   = ${retoEmpPc.aHex()}")
    }
}
