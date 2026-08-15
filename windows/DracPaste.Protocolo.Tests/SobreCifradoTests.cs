using System.Buffers.Binary;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;
using Xunit;

namespace DracPaste.Protocolo.Tests;

public class SobreCifradoTests
{
    private static byte[] Clave() => Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();

    private static byte[] OtraClave() => Enumerable.Range(1, 32).Select(i => (byte)i).ToArray();

    [Fact]
    public void LoSelladoSeAbreIgual()
    {
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());
        var mensaje = "un clip"u8.ToArray();

        Assert.Equal(mensaje, receptor.Abrir(emisor.Sellar(mensaje)));
    }

    [Fact]
    public void ElContadorAvanzaYViajaEnLaCabecera()
    {
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());

        for (var i = 0; i < 5; i++)
        {
            var sobre = emisor.Sellar(System.Text.Encoding.UTF8.GetBytes($"mensaje {i}"));
            Assert.Equal(i, BinaryPrimitives.ReadInt64BigEndian(sobre));
            Assert.Equal($"mensaje {i}", System.Text.Encoding.UTF8.GetString(receptor.Abrir(sobre)));
        }

        Assert.Equal(5, emisor.ContadorSalida);
        Assert.Equal(4, receptor.UltimoContadorAceptado);
    }

    [Fact]
    public void DosMensajesIgualesProducenBytesDistintos()
    {
        // Si el nonce no avanzara, dos clips idénticos darían el mismo cifrado y
        // cualquiera en la red vería que se ha copiado dos veces lo mismo.
        var emisor = new SobreCifrado(Clave());

        Assert.NotEqual(emisor.Sellar("igual"u8.ToArray()), emisor.Sellar("igual"u8.ToArray()));
    }

    [Fact]
    public void UnSobreRepetidoSeRechaza()
    {
        // El ataque que esto evita: alguien graba el sobre de un clip y lo reinyecta más
        // tarde para que ese texto vuelva a aparecer en el portapapeles.
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());
        var sobre = emisor.Sellar("una vez"u8.ToArray());

        receptor.Abrir(sobre);

        var e = Assert.Throws<ProtocoloException>(() => receptor.Abrir(sobre));
        Assert.Contains("repetido", e.Message);
    }

    [Fact]
    public void UnContadorQueRetrocedeSeRechaza()
    {
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());
        var primero = emisor.Sellar("uno"u8.ToArray());
        var segundo = emisor.Sellar("dos"u8.ToArray());

        receptor.Abrir(segundo);

        var e = Assert.Throws<ProtocoloException>(() => receptor.Abrir(primero));
        Assert.Contains("retrocedido", e.Message);
    }

    [Fact]
    public void SeAceptanHuecosEnElContador()
    {
        // Un frame perdido no debe bloquear la sesión: solo se exige que el contador
        // avance, no que sea consecutivo.
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());

        emisor.Sellar("se pierde"u8.ToArray());
        var siguiente = emisor.Sellar("llega"u8.ToArray());

        Assert.Equal("llega", System.Text.Encoding.UTF8.GetString(receptor.Abrir(siguiente)));
    }

    [Fact]
    public void ConLaClaveEquivocadaNoSeAbre()
    {
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(OtraClave());

        var e = Assert.Throws<AutenticacionFallidaException>(
            () => receptor.Abrir(emisor.Sellar("secreto"u8.ToArray())));
        Assert.Contains("integridad", e.Message);
    }

    [Fact]
    public void UnByteManipuladoInvalidaElSobre()
    {
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());
        var sobre = emisor.Sellar("no me toques"u8.ToArray());
        sobre[12] ^= 0x01;

        Assert.Throws<AutenticacionFallidaException>(() => receptor.Abrir(sobre));
    }

    [Fact]
    public void ManipularElContadorTambienInvalidaElSobre()
    {
        // El contador va en claro, pero cambiarlo cambia el nonce y el tag deja de
        // cuadrar: queda autenticado sin necesidad de meterlo en el AAD.
        var emisor = new SobreCifrado(Clave());
        var receptor = new SobreCifrado(Clave());
        var sobre = emisor.Sellar("intacto"u8.ToArray());
        sobre[7] = 9;

        Assert.Throws<AutenticacionFallidaException>(() => receptor.Abrir(sobre));
    }

    [Fact]
    public void UnSobreMasCortoQueSuPropioTagSeRechaza()
    {
        var e = Assert.Throws<ProtocoloException>(() => new SobreCifrado(Clave()).Abrir(new byte[10]));
        Assert.Contains("corto", e.Message);
    }

    [Fact]
    public void ElSobreDeKotlinSeAbreEnCSharp()
    {
        // Interoperabilidad real del sobre, no solo de las primitivas: estos bytes son
        // los que produce el gemelo Kotlin sellando "hola" con la clave M2P de los
        // vectores y contador 0.
        var clave = Hex.FromHex("f0dbcb2507a2f78763fb7fda468ffc6a9fc8a55630153130d0725f5ac54d66f3");
        var sobreDeKotlin = Hex.FromHex("0000000000000000678e67f72a09b0970f17bb20686f7545b9f5b1bb");

        var receptor = new SobreCifrado(clave);
        Assert.Equal("hola", System.Text.Encoding.UTF8.GetString(receptor.Abrir(sobreDeKotlin)));
    }
}
