using System.Net;
using System.Net.Sockets;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;
using DracPaste.Protocolo.Sesion;
using Xunit;

namespace DracPaste.Protocolo.Tests;

/// <summary>
/// Emparejamiento completo sobre loopback, incluido el camino que sigue después: que la
/// sesión del día siguiente se abra con lo que quedó guardado.
/// </summary>
public class EmparejamientoTests
{
    private const string IdPc = "1111111111111111aaaaaaaaaaaaaaaa";
    private const string IdMovil = "2222222222222222bbbbbbbbbbbbbbbb";

    [Fact]
    public async Task ElEmparejamientoCompletoDejaLaMismaClaveEnLosDosLados()
    {
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var token = Cripto.Aleatorio(16);

        var (enElPc, enElMovil) = await Emparejar(pc, movil, token);

        Assert.Equal(enElPc.ClavePar, enElMovil.ClavePar);
        Assert.Equal(enElPc.Huella, enElMovil.Huella);
        Assert.Equal(IdMovil, enElPc.DeviceIdRemoto);
        Assert.Equal(IdPc, enElMovil.DeviceIdRemoto);
        Assert.Equal(movil.Publica, enElPc.PublicaRemota);
        Assert.Equal(pc.Publica, enElMovil.PublicaRemota);
    }

    [Fact]
    public async Task LaHuellaQueVeElUsuarioEsLaMismaEnLasDosPantallas()
    {
        // Es lo único que el usuario puede comparar a ojo para saber que no hay nadie en
        // medio. Si cada lado mostrara una distinta, la comprobación no valdría nada.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();

        var (enElPc, enElMovil) = await Emparejar(pc, movil, Cripto.Aleatorio(16));

        Assert.Equal(enElPc.Huella, enElMovil.Huella);
        Assert.Matches("^[0-9A-F]{4}-[0-9A-F]{4}$", enElPc.Huella);
    }

    [Fact]
    public async Task TrasEmparejarSePuedeAbrirSesionConLoGuardado()
    {
        // El recorrido real: hoy se empareja, mañana la app arranca y solo tiene las
        // claves guardadas. Si la clave de par no se pudiera recalcular, el
        // emparejamiento no serviría de nada.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var (enElPc, _) = await Emparejar(pc, movil, Cripto.Aleatorio(16));

        var clavePcRecalculada = Derivacion.ClavePar(pc.Privada, enElPc.PublicaRemota);
        var claveMovilRecalculada = Derivacion.ClavePar(movil.Privada, pc.Publica);
        Assert.Equal(clavePcRecalculada, claveMovilRecalculada);

        using var par = await ParConectado();
        var sesionPc = Handshake.AceptarAsync(par.FlujoPc, IdPc, _ => clavePcRecalculada);
        var sesionMovil = Handshake.IniciarAsync(par.FlujoMovil, IdMovil, IdPc, claveMovilRecalculada);
        await Task.WhenAll(sesionPc, sesionMovil);

        Assert.Equal(IdMovil, (await sesionPc).DeviceIdRemoto);
    }

    [Fact]
    public async Task UnTokenInvalidoCortaElEmparejamiento()
    {
        // Alguien de la red que intenta emparejarse sin haber visto nunca el QR.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        using var par = await ParConectado();

        var enElPc = Emparejamiento.AceptarAsync(
            par.FlujoPc, pc.Privada, IdPc, "PC", _ => false);
        var enElMovil = Emparejamiento.IniciarAsync(
            par.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, Cripto.Aleatorio(16)));

        var e = await Assert.ThrowsAsync<ProtocoloException>(() => enElPc);
        Assert.Contains("Token", e.Message);
        await Assert.ThrowsAnyAsync<Exception>(() => enElMovil);
    }

    [Fact]
    public async Task ElTokenSeConsumeUnaSolaVez()
    {
        // Aunque el mismo token llegue dos veces, la segunda no puede emparejar: el
        // consumo y la comprobación son el mismo paso.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var token = Cripto.Aleatorio(16);
        var usos = 0;

        bool Consumir(byte[] t) => Interlocked.Increment(ref usos) == 1;

        using var primero = await ParConectado();
        var tarea1 = Task.Run(async () =>
        {
            try
            {
                return await Emparejamiento.AceptarAsync(primero.FlujoPc, pc.Privada, IdPc, "PC", Consumir);
            }
            finally
            {
                primero.CerrarLadoPc();
            }
        });
        var tarea2 = Emparejamiento.IniciarAsync(
            primero.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, token));
        await Task.WhenAll(tarea1, tarea2);

        using var segundo = await ParConectado();
        var reintento = Emparejamiento.AceptarAsync(segundo.FlujoPc, pc.Privada, IdPc, "PC", Consumir);
        var reintentoMovil = Emparejamiento.IniciarAsync(
            segundo.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, token));

        await Assert.ThrowsAsync<ProtocoloException>(() => reintento);
        await Assert.ThrowsAnyAsync<Exception>(() => reintentoMovil);
    }

    [Fact]
    public async Task UnPcQueFingeSerOtroNoPasaLaComprobacionDeHuella()
    {
        // El QR dice una clave pública y contesta un PC con otra: las huellas no
        // cuadran y el móvil no guarda nada.
        var pcDelQr = Cripto.GenerarParDeClaves();
        var impostor = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var token = Cripto.Aleatorio(16);

        using var par = await ParConectado();
        var enElImpostor = Emparejamiento.AceptarAsync(
            par.FlujoPc, impostor.Privada, IdPc, "PC falso", _ => true);
        var enElMovil = Emparejamiento.IniciarAsync(
            par.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pcDelQr, token));

        // El móvil deriva su clave con la pública del QR, así que ni siquiera consigue
        // descifrar lo que manda el impostor.
        await Assert.ThrowsAnyAsync<Exception>(() => enElMovil);
        await Assert.ThrowsAnyAsync<Exception>(() => enElImpostor);
    }

    [Fact]
    public async Task ElMovilNoDaPorBuenoElEmparejamientoHastaQueElPcCierra()
    {
        // El PAIR_ACK es el último mensaje: su emisor no sabría si llegó. Como el PC
        // cierra solo después de guardar, ese cierre es el acuse. Aquí el PC recibe el
        // ACK pero se queda callado sin cerrar —el equivalente a quedarse sin disco al
        // guardar— y el móvil no debe declararse emparejado.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var token = Cripto.Aleatorio(16);

        using var par = await ParConectado();
        var enElPc = Emparejamiento.AceptarAsync(
            par.FlujoPc, pc.Privada, IdPc, "PC", t => t.SequenceEqual(token));

        using var corte = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        var enElMovil = Emparejamiento.IniciarAsync(
            par.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, token), corte.Token);

        // El PC completa su parte pero no cierra.
        await enElPc;

        await Assert.ThrowsAnyAsync<Exception>(() => enElMovil);
    }

    [Fact]
    public async Task SiElPcContestaEnVezDeCerrarSeAborta()
    {
        // Un PC que no sigue el protocolo: manda algo después del PAIR_ACK.
        var pc = Cripto.GenerarParDeClaves();
        var movil = Cripto.GenerarParDeClaves();
        var token = Cripto.Aleatorio(16);

        using var par = await ParConectado();
        var enElPc = Task.Run(async () =>
        {
            var resultado = await Emparejamiento.AceptarAsync(
                par.FlujoPc, pc.Privada, IdPc, "PC", t => t.SequenceEqual(token));
            await Framing.EscribirAsync(par.FlujoPc, "sorpresa"u8.ToArray());
            return resultado;
        });

        var enElMovil = Emparejamiento.IniciarAsync(
            par.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, token));

        await enElPc;
        var e = await Assert.ThrowsAsync<ProtocoloException>(() => enElMovil);
        Assert.Contains("cerrar la conexión", e.Message);
    }

    [Fact]
    public void ElQrVaYVuelve()
    {
        var qr = new DatosQr
        {
            Pk = Convert.ToBase64String(Cripto.GenerarParDeClaves().Publica),
            Ip = "192.168.1.40",
            Port = 47653,
            Token = Convert.ToBase64String(Cripto.Aleatorio(16)),
            Name = "PC-DESPACHO",
            DeviceId = IdPc,
        };

        var leido = DatosQr.Leer(qr.ASerializar());

        Assert.Equal(qr, leido);
    }

    [Fact]
    public void UnQrDeOtraVersionSeRechazaConUnMensajeEntendible()
    {
        var json = """{"v":99,"pk":"AA==","ip":"1.2.3.4","port":1,"token":"AA==","name":"x","device_id":"y"}""";

        var e = Assert.Throws<ProtocoloException>(() => DatosQr.Leer(json));
        Assert.Contains("versión", e.Message);
    }

    [Fact]
    public void UnQrDeOtraCosaSeRechaza()
    {
        // El usuario apunta la cámara a cualquier otro QR del mundo.
        Assert.Throws<ProtocoloException>(() => DatosQr.Leer("https://ejemplo.com"));
    }

    // ------------------------------------------------------------------ Apoyo

    private static DatosQr Qr(ParDeClaves pc, byte[] token) => new()
    {
        Pk = Convert.ToBase64String(pc.Publica),
        Ip = "127.0.0.1",
        Port = 47653,
        Token = Convert.ToBase64String(token),
        Name = "PC",
        DeviceId = IdPc,
    };

    private static async Task<(ResultadoEmparejamiento Pc, ResultadoEmparejamiento Movil)> Emparejar(
        ParDeClaves pc,
        ParDeClaves movil,
        byte[] token)
    {
        using var par = await ParConectado();

        // El PC cierra su socket tras guardar, como hace el servidor real: ese cierre es
        // lo que el móvil espera como acuse del PAIR_ACK (docs/protocol.md §3.2 paso 6).
        var enElPc = Task.Run(async () =>
        {
            try
            {
                return await Emparejamiento.AceptarAsync(
                    par.FlujoPc, pc.Privada, IdPc, "PC-DESPACHO", t => t.SequenceEqual(token));
            }
            finally
            {
                par.CerrarLadoPc();
            }
        });

        var enElMovil = Emparejamiento.IniciarAsync(
            par.FlujoMovil, movil.Privada, IdMovil, "Pixel", Qr(pc, token));

        await Task.WhenAll(enElPc, enElMovil);
        return (await enElPc, await enElMovil);
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

        /// <summary>Lo que hace el servidor real al terminar de emparejar.</summary>
        public void CerrarLadoPc() => _pc.Close();

        public void Dispose()
        {
            _pc.Dispose();
            _movil.Dispose();
        }
    }
}
