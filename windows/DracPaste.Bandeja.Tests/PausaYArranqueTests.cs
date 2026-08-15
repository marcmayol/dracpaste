using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Seguridad;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja.Tests;

public class PausaTests : IDisposable
{
    private readonly string _carpeta = Path.Combine(
        Path.GetTempPath(),
        "dracpaste-tests-" + Guid.NewGuid().ToString("N"));

    private const string IdMovil = "2222222222222222bbbbbbbbbbbbbbbb";

    public PausaTests() => Directory.CreateDirectory(_carpeta);

    [Fact]
    public async Task EnPausaNoSaleNingunClip()
    {
        var identidad = Identidad.CargarOCrear(_carpeta);
        var registro = RegistroEmparejados.Cargar(_carpeta);
        var tokens = new GestorTokens();
        await using var servidor = new ServidorDracPaste(identidad, registro, tokens);
        servidor.Arrancar(puertoPreferido: 0);

        var movil = Cripto.GenerarParDeClaves();
        await Emparejar(servidor, identidad, tokens, movil);

        using var cliente = new System.Net.Sockets.TcpClient();
        await cliente.ConnectAsync(System.Net.IPAddress.Loopback, servidor.Puerto);
        await Handshake.IniciarAsync(
            cliente.GetStream(), IdMovil, identidad.DeviceId,
            Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await EsperarA(() => servidor.SesionesEstablecidas == 1);

        // Con la sesión viva, la pausa es lo único que impide el envío.
        Assert.True(await servidor.EnviarClipAsync(Clip.DeTexto("antes de pausar")));

        servidor.EnPausa = true;
        Assert.False(await servidor.EnviarClipAsync(Clip.DeTexto("durante la pausa")));

        servidor.EnPausa = false;
        Assert.True(await servidor.EnviarClipAsync(Clip.DeTexto("después de reanudar")));
    }

    [Fact]
    public async Task LaPausaNoTiraLaConexion()
    {
        // Es lo que permite al usuario distinguir «lo he pausado yo» de «se ha roto
        // algo»: la notificación del móvil sigue diciendo que están conectados.
        var identidad = Identidad.CargarOCrear(_carpeta);
        var registro = RegistroEmparejados.Cargar(_carpeta);
        var tokens = new GestorTokens();
        await using var servidor = new ServidorDracPaste(identidad, registro, tokens);
        servidor.Arrancar(puertoPreferido: 0);

        var movil = Cripto.GenerarParDeClaves();
        await Emparejar(servidor, identidad, tokens, movil);

        using var cliente = new System.Net.Sockets.TcpClient();
        await cliente.ConnectAsync(System.Net.IPAddress.Loopback, servidor.Puerto);
        await Handshake.IniciarAsync(
            cliente.GetStream(), IdMovil, identidad.DeviceId,
            Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await EsperarA(() => servidor.SesionesEstablecidas == 1);

        servidor.EnPausa = true;

        Assert.True(servidor.HayMovilConectado);
    }

    private static async Task Emparejar(
        ServidorDracPaste servidor, Identidad identidad, GestorTokens tokens, ParDeClaves movil)
    {
        var qr = new DatosQr
        {
            Pk = Convert.ToBase64String(identidad.Publica),
            Ip = "127.0.0.1",
            Port = servidor.Puerto,
            Token = Convert.ToBase64String(tokens.Emitir()),
            Name = identidad.Nombre,
            DeviceId = identidad.DeviceId,
        };

        using var cliente = new System.Net.Sockets.TcpClient();
        await cliente.ConnectAsync(System.Net.IPAddress.Loopback, servidor.Puerto);
        await Emparejamiento.IniciarAsync(cliente.GetStream(), movil.Privada, IdMovil, "Pixel", qr);
    }

    private static async Task EsperarA(Func<bool> condicion)
    {
        var limite = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < limite && !condicion())
        {
            await Task.Delay(25);
        }

        Assert.True(condicion(), "Se agotó la espera");
    }

    public void Dispose()
    {
        if (Directory.Exists(_carpeta))
        {
            try
            {
                Directory.Delete(_carpeta, recursive: true);
            }
            catch (IOException)
            {
                // Un fichero recién cerrado puede seguir bloqueado un instante.
            }
        }
    }
}

/// <summary>
/// El arranque con Windows toca el registro del usuario, así que estos tests están
/// desactivados: ejecutarlos dejaría DracPaste arrancando en el equipo de quien los corra
/// sin que nadie se lo haya pedido.
///
/// Para ejecutarlos: quitar los <c>Skip</c>. El estado previo se restaura al terminar.
/// </summary>
[Trait("Category", "Registro")]
public class ArranqueConWindowsTests
{
    private const string Motivo =
        "Escribe en la clave Run del registro del usuario. Ver el comentario de la clase.";

    [Fact(Skip = Motivo)]
    public void ActivarYDesactivarDejaElRegistroComoEstaba()
    {
        var estabaActivo = ArranqueConWindows.Activo;

        try
        {
            Assert.True(ArranqueConWindows.Activar(true));
            Assert.True(ArranqueConWindows.Activo);

            Assert.True(ArranqueConWindows.Activar(false));
            Assert.False(ArranqueConWindows.Activo);
        }
        finally
        {
            ArranqueConWindows.Activar(estabaActivo);
        }
    }

    [Fact(Skip = Motivo)]
    public void DesactivarDosVecesNoFalla()
    {
        var estabaActivo = ArranqueConWindows.Activo;

        try
        {
            ArranqueConWindows.Activar(false);
            Assert.True(ArranqueConWindows.Activar(false));
        }
        finally
        {
            ArranqueConWindows.Activar(estabaActivo);
        }
    }
}
