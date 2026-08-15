using DracPaste.Protocolo.Sesion;
using Xunit;

namespace DracPaste.Protocolo.Tests;

public class AntiEcoTests
{
    private long _ahora = 1_000;

    private AntiEco Nuevo() => new(ventanaMs: 5_000, reloj: () => _ahora);

    [Fact]
    public void UnCambioLocalSinNadaRecibidoAntesSeReenvia()
    {
        Assert.True(Nuevo().DebeReenviar("abc"));
    }

    [Fact]
    public void ElEcoDeLoQueSeAcabaDeEscribirNoSeReenvia()
    {
        // El bucle que esto corta: el PC manda un clip, el móvil lo escribe, su propio
        // listener lo ve como cambio y lo devolvería al PC indefinidamente.
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");

        Assert.False(antiEco.DebeReenviar("abc"));
    }

    [Fact]
    public void UnClipDistintoSiSeReenviaAunqueSeAcabeDeRecibirOtro()
    {
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");

        Assert.True(antiEco.DebeReenviar("def"));
    }

    [Fact]
    public void CopiarDosVecesAManoElMismoTextoLoEnviaDosVeces()
    {
        // La marca se consume al reconocer el eco. Si no lo hiciera, el usuario que
        // copia lo mismo dos veces seguidas vería que la segunda no llega.
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");

        Assert.False(antiEco.DebeReenviar("abc"));
        Assert.True(antiEco.DebeReenviar("abc"));
    }

    [Fact]
    public void LaMarcaCaduca()
    {
        // Media hora después, copiar ese mismo texto a mano es una copia legítima.
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");
        _ahora += 5_001;

        Assert.True(antiEco.DebeReenviar("abc"));
    }

    [Fact]
    public void JustoDentroDeLaVentanaSigueValiendo()
    {
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");
        _ahora += 5_000;

        Assert.False(antiEco.DebeReenviar("abc"));
    }

    [Fact]
    public void OlvidarDejaPasarCualquierCosa()
    {
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("abc");
        antiEco.Olvidar();

        Assert.True(antiEco.DebeReenviar("abc"));
    }

    [Fact]
    public void SoloCuentaElUltimoRecibido()
    {
        var antiEco = Nuevo();
        antiEco.MarcarRecibido("uno");
        antiEco.MarcarRecibido("dos");

        Assert.True(antiEco.DebeReenviar("uno"));
    }

    [Fact]
    public void UnIdaYVueltaCompletoNoEntraEnBucle()
    {
        // Simulación del ciclo real: llega un clip del móvil, se escribe en el
        // portapapeles de Windows, el listener lo detecta y pregunta si reenviar.
        var antiEco = Nuevo();

        for (var i = 0; i < 3; i++)
        {
            var origen = $"clip-{i}";
            antiEco.MarcarRecibido(origen);
            Assert.False(antiEco.DebeReenviar(origen));
            _ahora += 100;
        }
    }
}
