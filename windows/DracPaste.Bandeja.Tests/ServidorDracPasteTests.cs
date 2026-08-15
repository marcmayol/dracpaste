using System.Net.Sockets;
using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja.Tests;

/// <summary>
/// El servidor de verdad, con su TcpListener, atendido por un cliente que hace
/// exactamente lo que hará el móvil: emparejarse, abrir sesión y mandar clips.
///
/// Es la prueba de la Fase 1 que sí se puede automatizar. Lo que queda fuera —que el
/// móvil físico encuentre el PC por mDNS, y que aguante cambios de red y suspensión— está
/// en PROGRESS.md como prueba manual.
/// </summary>
public class ServidorDracPasteTests : IDisposable
{
    private readonly string _carpeta = Path.Combine(
        Path.GetTempPath(),
        "dracpaste-tests-" + Guid.NewGuid().ToString("N"));

    private const string IdMovil = "2222222222222222bbbbbbbbbbbbbbbb";

    public ServidorDracPasteTests() => Directory.CreateDirectory(_carpeta);

    [Fact]
    public async Task UnMovilSeEmparejaYQuedaGuardado()
    {
        var (servidor, identidad, registro, tokens) = Montar();
        await using var _ = servidor;

        var movil = Cripto.GenerarParDeClaves();
        var resultado = await EmparejarComoMovil(servidor, identidad, tokens, movil);

        Assert.Equal(identidad.DeviceId, resultado.DeviceIdRemoto);
        Assert.Equal(identidad.Nombre, resultado.NombreRemoto);

        var guardado = registro.Buscar(IdMovil);
        Assert.NotNull(guardado);
        Assert.Equal(resultado.Huella, guardado!.Huella);
        Assert.Equal(Convert.ToBase64String(movil.Publica), guardado.PublicaBase64);
    }

    [Fact]
    public async Task TrasEmparejarSePuedeAbrirSesionYMandarUnClip()
    {
        // El recorrido completo de la Fase 1: emparejar, cerrar, volver a conectar como
        // haría el móvil al día siguiente, y que el texto llegue.
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        var recibidos = new List<Clip>();
        using var llego = new SemaphoreSlim(0);
        servidor.ClipRecibido += clip =>
        {
            lock (recibidos)
            {
                recibidos.Add(clip);
            }

            llego.Release();
        };

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);

        using var cliente = await Conectar(servidor.Puerto);
        var flujo = cliente.GetStream();
        var sesion = await Handshake.IniciarAsync(
            flujo, IdMovil, identidad.DeviceId, Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await Framing.EscribirAsync(
            flujo, sesion.Saliente.Sellar(CodecMensajes.Codificar(Clip.DeTexto("desde el móvil"))));

        Assert.True(await llego.WaitAsync(TimeSpan.FromSeconds(10)), "El clip no llegó al servidor");
        Assert.Equal("desde el móvil", recibidos[0].Texto());
    }

    [Fact]
    public async Task ElServidorEnviaUnClipAlMovilConectado()
    {
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);

        using var cliente = await Conectar(servidor.Puerto);
        var flujo = cliente.GetStream();
        var sesion = await Handshake.IniciarAsync(
            flujo, IdMovil, identidad.DeviceId, Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await EsperarA(() => servidor.SesionesEstablecidas == 1, "el servidor no registró la sesión");

        Assert.True(await servidor.EnviarClipAsync(Clip.DeTexto("copiado en el PC")));

        var recibido = (Clip)CodecMensajes.Decodificar(
            sesion.Entrante.Abrir(await Framing.LeerAsync(flujo)));
        Assert.Equal("copiado en el PC", recibido.Texto());
    }

    [Fact]
    public async Task SinMovilConectadoElEnvioNoRompeNada()
    {
        // Sin cola de clips (docs/protocol.md §8): si no hay nadie, el clip se descarta
        // y la app lo dice; no se guarda nada pendiente.
        var (servidor, _, _, _) = Montar();
        await using var _s = servidor;

        Assert.False(await servidor.EnviarClipAsync(Clip.DeTexto("nadie escucha")));
    }

    [Fact]
    public async Task UnMovilNoEmparejadoNoAbreSesion()
    {
        var (servidor, identidad, _, _) = Montar();
        await using var _s = servidor;

        using var cliente = await Conectar(servidor.Puerto);
        var intruso = Cripto.GenerarParDeClaves();

        await Assert.ThrowsAnyAsync<Exception>(() => Handshake.IniciarAsync(
            cliente.GetStream(),
            "9999999999999999dddddddddddddddd",
            identidad.DeviceId,
            Derivacion.ClavePar(intruso.Privada, identidad.Publica)));
    }

    [Fact]
    public async Task UnEmparejamientoSinTokenValidoNoGuardaNada()
    {
        var (servidor, identidad, registro, _) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        using var cliente = await Conectar(servidor.Puerto);

        await Assert.ThrowsAnyAsync<Exception>(() => Emparejamiento.IniciarAsync(
            cliente.GetStream(),
            movil.Privada,
            IdMovil,
            "Intruso",
            new DatosQr
            {
                Pk = Convert.ToBase64String(identidad.Publica),
                Ip = "127.0.0.1",
                Port = servidor.Puerto,
                Token = Convert.ToBase64String(Cripto.Aleatorio(16)),
                Name = identidad.Nombre,
                DeviceId = identidad.DeviceId,
            }));

        Assert.Empty(registro.Todos);
    }

    [Fact]
    public async Task UnaBasuraCualquieraEnElPuertoNoTumbaElServidor()
    {
        // Cualquiera de la red puede conectar al puerto y escribir lo que quiera. El
        // servidor tiene que seguir atendiendo al móvil de verdad después.
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        using (var basura = await Conectar(servidor.Puerto))
        {
            await basura.GetStream().WriteAsync("GET / HTTP/1.1\r\n\r\n"u8.ToArray());
        }

        using (var otra = await Conectar(servidor.Puerto))
        {
            // Una cabecera que anuncia un frame enorme.
            await otra.GetStream().WriteAsync(new byte[] { 0x7F, 0xFF, 0xFF, 0xFF });
        }

        var movil = Cripto.GenerarParDeClaves();
        var resultado = await EmparejarComoMovil(servidor, identidad, tokens, movil);
        Assert.Equal(identidad.DeviceId, resultado.DeviceIdRemoto);
    }

    [Fact]
    public async Task UnaReconexionSustituyeALaSesionAnterior()
    {
        // Al cambiar de WiFi, el móvil abre un socket nuevo mientras el viejo sigue
        // "conectado" para el PC. Si no se sustituyera, los clips irían al socket zombi.
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);
        var clavePar = Derivacion.ClavePar(movil.Privada, identidad.Publica);

        using var primera = await Conectar(servidor.Puerto);
        await Handshake.IniciarAsync(primera.GetStream(), IdMovil, identidad.DeviceId, clavePar);
        await EsperarA(() => servidor.SesionesEstablecidas == 1, "no se registró la primera sesión");

        using var segunda = await Conectar(servidor.Puerto);
        var sesion2 = await Handshake.IniciarAsync(
            segunda.GetStream(), IdMovil, identidad.DeviceId, clavePar);

        // Se espera al contador, no a HayMovilConectado: durante el relevo las dos
        // sesiones están vivas un instante, y sin esto el clip podría irse a la vieja.
        await EsperarA(() => servidor.SesionesEstablecidas == 2, "no se registró la segunda sesión");
        Assert.True(await servidor.EnviarClipAsync(Clip.DeTexto("va a la sesión nueva")));

        var recibido = (Clip)CodecMensajes.Decodificar(
            sesion2.Entrante.Abrir(await Framing.LeerAsync(segunda.GetStream())));
        Assert.Equal("va a la sesión nueva", recibido.Texto());
    }

    [Fact]
    public async Task ElServidorContestaAlPingDelMovil()
    {
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);

        using var cliente = await Conectar(servidor.Puerto);
        var flujo = cliente.GetStream();
        var sesion = await Handshake.IniciarAsync(
            flujo, IdMovil, identidad.DeviceId, Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await Framing.EscribirAsync(flujo, sesion.Saliente.Sellar(CodecMensajes.Codificar(new Ping { Seq = 7 })));

        var respuesta = CodecMensajes.Decodificar(sesion.Entrante.Abrir(await Framing.LeerAsync(flujo)));
        var pong = Assert.IsType<Pong>(respuesta);
        Assert.Equal(7, pong.Seq);
    }

    [Fact]
    public async Task DesemparejarAvisaAlMovilYBorraSuClave()
    {
        var (servidor, identidad, registro, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);

        using var cliente = await Conectar(servidor.Puerto);
        var flujo = cliente.GetStream();
        var sesion = await Handshake.IniciarAsync(
            flujo, IdMovil, identidad.DeviceId, Derivacion.ClavePar(movil.Privada, identidad.Publica));
        await EsperarA(() => servidor.SesionesEstablecidas == 1, "no se registró la sesión");

        await servidor.DesemparejarAsync(IdMovil);

        // El móvil recibe el aviso, así que puede borrar su clave también.
        var mensaje = CodecMensajes.Decodificar(sesion.Entrante.Abrir(await Framing.LeerAsync(flujo)));
        Assert.IsType<Unpair>(mensaje);
        Assert.Empty(registro.Todos);
    }

    [Fact]
    public async Task TrasDesemparejarElMovilYaNoPuedeConectar()
    {
        // Es lo que hace que desemparejar signifique algo: sin esto, el móvil seguiría
        // entrando con la clave que ya tenía guardada.
        var (servidor, identidad, _, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);
        var clavePar = Derivacion.ClavePar(movil.Privada, identidad.Publica);

        await servidor.DesemparejarAsync(IdMovil);

        using var cliente = await Conectar(servidor.Puerto);
        await Assert.ThrowsAnyAsync<Exception>(
            () => Handshake.IniciarAsync(cliente.GetStream(), IdMovil, identidad.DeviceId, clavePar));
    }

    [Fact]
    public async Task DesemparejarUnMovilQueNoEstaConectadoTambienLoOlvida()
    {
        // El móvil puede estar apagado o en otra red. Que el usuario tenga que esperar a
        // que aparezca para poder desemparejarlo sería absurdo.
        var (servidor, identidad, registro, tokens) = Montar();
        await using var _s = servidor;

        await EmparejarComoMovil(servidor, identidad, tokens, Cripto.GenerarParDeClaves());
        Assert.Single(registro.Todos);

        await servidor.DesemparejarAsync(IdMovil);

        Assert.Empty(registro.Todos);
    }

    [Fact]
    public async Task UnUnpairDelMovilHaceQueElPcOlvideSuClave()
    {
        // El desemparejamiento iniciado desde el otro lado tiene que surtir el mismo
        // efecto: si no, el usuario desempareja en el móvil y el PC sigue guardando una
        // clave que ya no sirve para nada.
        var (servidor, identidad, registro, tokens) = Montar();
        await using var _s = servidor;

        var movil = Cripto.GenerarParDeClaves();
        await EmparejarComoMovil(servidor, identidad, tokens, movil);

        using var cliente = await Conectar(servidor.Puerto);
        var flujo = cliente.GetStream();
        var sesion = await Handshake.IniciarAsync(
            flujo, IdMovil, identidad.DeviceId, Derivacion.ClavePar(movil.Privada, identidad.Publica));

        await Framing.EscribirAsync(flujo, sesion.Saliente.Sellar(CodecMensajes.Codificar(new Unpair())));

        await EsperarA(() => registro.Todos.Count == 0, "el PC no olvidó el móvil tras el UNPAIR");
    }

    [Fact]
    public async Task VariosMovilesEmparejadosConvivenSinPisarseLasClaves()
    {
        // Cada pareja tiene su clave: comprometer o desemparejar una no puede afectar a
        // las demás (PLAN.md §3.3).
        var (servidor, identidad, registro, tokens) = Montar();
        await using var _s = servidor;

        var primero = Cripto.GenerarParDeClaves();
        var segundo = Cripto.GenerarParDeClaves();
        const string idSegundo = "3333333333333333cccccccccccccccc";

        await EmparejarComoMovil(servidor, identidad, tokens, primero);
        await EmparejarComoMovil(servidor, identidad, tokens, segundo, idSegundo, "Tablet");

        Assert.Equal(2, registro.Todos.Count);
        Assert.NotEqual(
            registro.ClaveParDe(IdMovil, identidad),
            registro.ClaveParDe(idSegundo, identidad));

        await servidor.DesemparejarAsync(IdMovil);

        // El otro sigue pudiendo entrar con su propia clave.
        Assert.Single(registro.Todos);
        Assert.Equal(idSegundo, registro.Todos.Single().DeviceId);

        using var cliente = await Conectar(servidor.Puerto);
        var sesion = await Handshake.IniciarAsync(
            cliente.GetStream(),
            idSegundo,
            identidad.DeviceId,
            Derivacion.ClavePar(segundo.Privada, identidad.Publica));

        // Visto desde el móvil, el extremo remoto es el PC.
        Assert.Equal(identidad.DeviceId, sesion.DeviceIdRemoto);
        await EsperarA(() => servidor.HayMovilConectado, "el segundo móvil no consiguió sesión");
    }

    // ------------------------------------------------------------------ Apoyo

    private (ServidorDracPaste Servidor, Identidad Identidad, RegistroEmparejados Registro, GestorTokens Tokens) Montar()
    {
        var identidad = Identidad.CargarOCrear(_carpeta);
        var registro = RegistroEmparejados.Cargar(_carpeta);
        var tokens = new GestorTokens();
        var servidor = new ServidorDracPaste(identidad, registro, tokens);
        // Puerto efímero: con el puerto fijo del protocolo, dos tests en paralelo
        // competirían por él y fallarían de forma intermitente.
        servidor.Arrancar(puertoPreferido: 0);
        return (servidor, identidad, registro, tokens);
    }

    private static async Task<ResultadoEmparejamiento> EmparejarComoMovil(
        ServidorDracPaste servidor,
        Identidad identidad,
        GestorTokens tokens,
        ParDeClaves movil,
        string deviceId = IdMovil,
        string nombre = "Pixel")
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

        using var cliente = await Conectar(servidor.Puerto);
        return await Emparejamiento.IniciarAsync(
            cliente.GetStream(), movil.Privada, deviceId, nombre, qr);
    }

    private static async Task<TcpClient> Conectar(int puerto)
    {
        var cliente = new TcpClient();
        await cliente.ConnectAsync(System.Net.IPAddress.Loopback, puerto);
        cliente.NoDelay = true;
        return cliente;
    }

    private static async Task EsperarA(Func<bool> condicion, string queFalla)
    {
        var limite = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < limite)
        {
            if (condicion())
            {
                return;
            }

            await Task.Delay(25);
        }

        Assert.Fail($"Se agotó la espera: {queFalla}");
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
                // En Windows, un fichero recién cerrado puede seguir bloqueado un
                // instante. No es motivo para fallar el test.
            }
        }
    }
}
