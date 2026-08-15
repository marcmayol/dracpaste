using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Sesion;
using Xunit;

namespace DracPaste.Protocolo.Tests;

/// <summary>
/// El bucle de eco, simulado de punta a punta con los dos dispositivos.
///
/// Los tests de <see cref="AntiEco"/> comprueban la pieza por separado; estos comprueban
/// lo que de verdad importa: que <b>conectando las dos mitades</b>, un clip no rebota
/// indefinidamente. Es el fallo que se ve desde fuera como dos aparatos dándose el mismo
/// texto para siempre, y no se puede reproducir a mano de forma fiable.
/// </summary>
public class CicloAntiEcoTests
{
    private long _ahora;
    private readonly Dispositivo _pc;
    private readonly Dispositivo _movil;

    public CicloAntiEcoTests()
    {
        _pc = new Dispositivo(() => _ahora);
        _movil = new Dispositivo(() => _ahora);
    }

    [Fact]
    public void UnaCopiaEnElPcLlegaAlMovilYAhiSePara()
    {
        _pc.CopiaElUsuario("hola");

        var vueltas = RepartirHasta();

        Assert.Equal("hola", _movil.Portapapeles);
        Assert.Equal("hola", _pc.Portapapeles);
        Assert.Equal(1, vueltas);
    }

    [Fact]
    public void UnaCopiaEnElMovilLlegaAlPcYAhiSePara()
    {
        _movil.CopiaElUsuario("desde el móvil");

        Assert.Equal(1, RepartirHasta());
        Assert.Equal("desde el móvil", _pc.Portapapeles);
    }

    [Fact]
    public void CopiarDiezCosasSeguidasNoPierdeNingunaNiEntraEnBucle()
    {
        // El caso real de quien está trabajando: copia una cosa detrás de otra.
        for (var i = 0; i < 10; i++)
        {
            _pc.CopiaElUsuario($"clip {i}");
            RepartirHasta();
            _ahora += 200;
        }

        Assert.Equal("clip 9", _movil.Portapapeles);
    }

    [Fact]
    public void CopiarLoMismoDosVecesAManoLoEnviaDosVeces()
    {
        // Si la marca no se consumiera al reconocer el eco, la segunda copia no llegaría
        // y el usuario no entendería por qué.
        _pc.CopiaElUsuario("repetido");
        RepartirHasta();
        _movil.Enviados.Clear();

        _movil.CopiaElUsuario("repetido");

        Assert.Equal(1, RepartirHasta());
        Assert.Equal("repetido", _pc.Portapapeles);
    }

    [Fact]
    public void LosDosCopianALaVezYLaCosaNoSeDesmadra()
    {
        // Cada uno copia algo distinto en el mismo instante. Ninguno de los dos textos
        // puede quedarse dando vueltas.
        _pc.CopiaElUsuario("del PC");
        _movil.CopiaElUsuario("del móvil");

        var vueltas = RepartirHasta();

        Assert.True(vueltas < 5, $"Se quedó rebotando durante {vueltas} vueltas");
        Assert.Empty(_pc.Enviados);
        Assert.Empty(_movil.Enviados);
    }

    [Fact]
    public void PasadaLaVentanaVolverACopiarLoMismoSiViaja()
    {
        _pc.CopiaElUsuario("persistente");
        RepartirHasta();

        _ahora += 60_000;
        _movil.CopiaElUsuario("persistente");

        Assert.Equal(1, RepartirHasta());
    }

    [Fact]
    public void ElCicloSeCortaAunqueElRelojNoAvance()
    {
        // Todo ocurre dentro del mismo milisegundo, que es lo que pasa en una red local
        // rápida: la caducidad de la marca no puede ser lo que corte el bucle.
        _pc.CopiaElUsuario("instantáneo");

        Assert.Equal(1, RepartirHasta());
    }

    /// <summary>Entrega los clips pendientes de un lado al otro hasta que no quede ninguno.</summary>
    private int RepartirHasta(int vueltasMaximas = 20)
    {
        var vueltas = 0;
        while (vueltas < vueltasMaximas)
        {
            var delPc = _pc.Enviados.ToList();
            var delMovil = _movil.Enviados.ToList();
            if (delPc.Count == 0 && delMovil.Count == 0)
            {
                return vueltas;
            }

            _pc.Enviados.Clear();
            _movil.Enviados.Clear();
            delPc.ForEach(t => _movil.Recibir(Clip.DeTexto(t)));
            delMovil.ForEach(t => _pc.Recibir(Clip.DeTexto(t)));
            vueltas++;
        }

        return vueltas;
    }

    /// <summary>
    /// Un dispositivo con su portapapeles y su anti-eco. Modela lo justo: escribir un
    /// clip recibido, y que el listener local reaccione a cualquier cambio.
    /// </summary>
    private sealed class Dispositivo
    {
        private readonly AntiEco _antiEco;

        public Dispositivo(Func<long> reloj) => _antiEco = new AntiEco(reloj: reloj);

        public string? Portapapeles { get; private set; }

        public List<string> Enviados { get; } = new();

        /// <summary>Llega un clip del otro lado: se marca y se escribe.</summary>
        public void Recibir(Clip clip)
        {
            _antiEco.MarcarRecibido(clip.OriginId);
            EscribirEnPortapapeles(clip.Texto());
        }

        /// <summary>El usuario copia algo a mano.</summary>
        public void CopiaElUsuario(string texto) => EscribirEnPortapapeles(texto);

        /// <summary>
        /// Todo cambio del portapapeles pasa por aquí, venga de donde venga: es lo que
        /// hace el listener real, que no sabe quién ha escrito.
        /// </summary>
        private void EscribirEnPortapapeles(string texto)
        {
            Portapapeles = texto;
            if (_antiEco.DebeReenviar(Clip.OrigenDe(texto)))
            {
                Enviados.Add(texto);
            }
        }
    }
}
