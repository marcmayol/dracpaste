using System.Net;
using System.Net.Sockets;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;
using DracPaste.Protocolo.Sesion;
using Xunit;

namespace DracPaste.Protocolo.Tests;

/// <summary>
/// Handshake completo sobre loopback. No hace falta móvil, ni WiFi, ni emparejar nada:
/// dos sockets en 127.0.0.1 recorren exactamente el mismo camino que recorrerán el móvil
/// y el PC.
/// </summary>
public class HandshakeTests
{
    private const string IdPc = "1111111111111111aaaaaaaaaaaaaaaa";
    private const string IdMovil = "2222222222222222bbbbbbbbbbbbbbbb";

    private static byte[] ClavePar() => Hex.FromHex(
        "7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74");

    [Fact]
    public async Task ElHandshakeCompletoEstableceLaSesionEnLosDosLados()
    {
        var (sesionPc, sesionMovil) = await HandshakeCompleto();

        Assert.Equal(IdMovil, sesionPc.DeviceIdRemoto);
        Assert.Equal(IdPc, sesionMovil.DeviceIdRemoto);
    }

    [Fact]
    public async Task LosContadoresEmpiezanDondeLosDejoElHandshake()
    {
        // El handshake gasta el contador 0 en cada dirección (AUTH y AUTH_OK). El primer
        // clip debe llevar el 1: si alguno se reiniciara, se repetiría un nonce.
        var (sesionPc, sesionMovil) = await HandshakeCompleto();

        Assert.Equal(1, sesionPc.Saliente.ContadorSalida);
        Assert.Equal(0, sesionPc.Entrante.UltimoContadorAceptado);
        Assert.Equal(1, sesionMovil.Saliente.ContadorSalida);
        Assert.Equal(0, sesionMovil.Entrante.UltimoContadorAceptado);
    }

    [Fact]
    public async Task UnClipViajaCifradoEnLasDosDirecciones()
    {
        using var par = await ParConectado();
        var (sesionPc, sesionMovil) = await HandshakeSobre(par);

        // Del PC al móvil.
        var delPc = Clip.DeTexto("copiado en el PC");
        await Framing.EscribirAsync(par.FlujoPc, sesionPc.Saliente.Sellar(CodecMensajes.Codificar(delPc)));
        var recibidoEnMovil = (Clip)CodecMensajes.Decodificar(
            sesionMovil.Entrante.Abrir(await Framing.LeerAsync(par.FlujoMovil)));
        Assert.Equal("copiado en el PC", recibidoEnMovil.Texto());

        // Y del móvil al PC.
        var delMovil = Clip.DeTexto("copiado en el móvil");
        await Framing.EscribirAsync(par.FlujoMovil, sesionMovil.Saliente.Sellar(CodecMensajes.Codificar(delMovil)));
        var recibidoEnPc = (Clip)CodecMensajes.Decodificar(
            sesionPc.Entrante.Abrir(await Framing.LeerAsync(par.FlujoPc)));
        Assert.Equal("copiado en el móvil", recibidoEnPc.Texto());
    }

    [Fact]
    public async Task ElTextoNoViajaLegiblePorElCable()
    {
        // Lo que confirmaría Wireshark, comprobado aquí: los bytes que salen del socket
        // no contienen el texto del clip.
        using var par = await ParConectado();
        var (sesionPc, _) = await HandshakeSobre(par);

        const string secreto = "esto-no-debe-verse-en-la-red";
        var sellado = sesionPc.Saliente.Sellar(CodecMensajes.Codificar(Clip.DeTexto(secreto)));

        var comoTexto = System.Text.Encoding.UTF8.GetString(sellado);
        Assert.DoesNotContain(secreto, comoTexto);
        Assert.DoesNotContain("CLIP", comoTexto);
        Assert.DoesNotContain(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(secreto)), comoTexto);
    }

    [Fact]
    public async Task UnMovilDesconocidoNoEntra()
    {
        using var par = await ParConectado();

        var enElPc = Handshake.AceptarAsync(par.FlujoPc, IdPc, _ => null);
        var enElMovil = Handshake.IniciarAsync(par.FlujoMovil, IdMovil, IdPc, ClavePar());

        await Assert.ThrowsAsync<ProtocoloException>(() => enElPc);
        await Assert.ThrowsAnyAsync<Exception>(() => enElMovil);
    }

    [Fact]
    public async Task UnPcConOtraClaveNoConsigueAutenticarse()
    {
        // El impostor de la LAN: anuncia el mismo servicio mDNS y acepta la conexión,
        // pero no tiene la clave del par.
        using var par = await ParConectado();
        var claveDelImpostor = Cripto.Aleatorio(32);

        var enElPc = Handshake.AceptarAsync(par.FlujoPc, IdPc, _ => claveDelImpostor);
        var enElMovil = Handshake.IniciarAsync(par.FlujoMovil, IdMovil, IdPc, ClavePar());

        await Assert.ThrowsAnyAsync<Exception>(() => enElPc);
        await Assert.ThrowsAnyAsync<Exception>(() => enElMovil);
    }

    [Fact]
    public async Task UnPcQueNoEsElActivoSeRechaza()
    {
        // Otro PC de la casa, emparejado también, pero que no es el destino activo.
        using var par = await ParConectado();
        const string otroPc = "9999999999999999cccccccccccccccc";

        var enElPc = Handshake.AceptarAsync(par.FlujoPc, otroPc, _ => ClavePar());
        var enElMovil = Handshake.IniciarAsync(par.FlujoMovil, IdMovil, IdPc, ClavePar());

        var e = await Assert.ThrowsAsync<ProtocoloException>(() => enElMovil);
        Assert.Contains("se esperaba", e.Message);
        await Assert.ThrowsAnyAsync<Exception>(() => enElPc);
    }

    [Fact]
    public async Task CadaSesionUsaClavesDistintas()
    {
        // Es lo que hace seguro reiniciar los contadores a cero en cada reconexión: los
        // retos son nuevos, así que las claves también.
        var primera = await SelladoDelPrimerClip();
        var segunda = await SelladoDelPrimerClip();

        Assert.NotEqual(primera, segunda);
    }

    [Fact]
    public async Task UnHandshakeQueNoLlegaNoDejaLaConexionColgada()
    {
        // Alguien abre el socket y no dice nada. Sin plazo, ese socket se quedaría
        // ocupando un hilo para siempre.
        using var par = await ParConectado();
        using var corte = new CancellationTokenSource(TimeSpan.FromSeconds(2));

        await Assert.ThrowsAnyAsync<Exception>(
            () => Handshake.AceptarAsync(par.FlujoPc, IdPc, _ => ClavePar(), corte.Token));
    }

    // ------------------------------------------------------------------ Apoyo

    private static async Task<(SesionEstablecida Pc, SesionEstablecida Movil)> HandshakeCompleto()
    {
        using var par = await ParConectado();
        return await HandshakeSobre(par);
    }

    private static async Task<(SesionEstablecida Pc, SesionEstablecida Movil)> HandshakeSobre(ParDeSockets par)
    {
        var enElPc = Handshake.AceptarAsync(par.FlujoPc, IdPc, id => id == IdMovil ? ClavePar() : null);
        var enElMovil = Handshake.IniciarAsync(par.FlujoMovil, IdMovil, IdPc, ClavePar());

        await Task.WhenAll(enElPc, enElMovil);
        return (await enElPc, await enElMovil);
    }

    private static async Task<byte[]> SelladoDelPrimerClip()
    {
        using var par = await ParConectado();
        var (sesionPc, _) = await HandshakeSobre(par);
        return sesionPc.Saliente.Sellar(CodecMensajes.Codificar(Clip.DeTexto("mismo texto", 1)));
    }

    private static async Task<ParDeSockets> ParConectado()
    {
        var escucha = new TcpListener(IPAddress.Loopback, 0);
        escucha.Start();
        try
        {
            var puerto = ((IPEndPoint)escucha.LocalEndpoint).Port;
            var cliente = new TcpClient();
            var conectando = cliente.ConnectAsync(IPAddress.Loopback, puerto);
            var servidor = await escucha.AcceptTcpClientAsync();
            await conectando;
            return new ParDeSockets(servidor, cliente);
        }
        finally
        {
            escucha.Stop();
        }
    }

    private sealed class ParDeSockets : IDisposable
    {
        private readonly TcpClient _pc;
        private readonly TcpClient _movil;

        public ParDeSockets(TcpClient pc, TcpClient movil)
        {
            _pc = pc;
            _movil = movil;
            FlujoPc = pc.GetStream();
            FlujoMovil = movil.GetStream();
        }

        public NetworkStream FlujoPc { get; }

        public NetworkStream FlujoMovil { get; }

        public void Dispose()
        {
            _pc.Dispose();
            _movil.Dispose();
        }
    }
}
