using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Protocolo.Sesion;

/// <summary>
/// El handshake de sesión (<c>docs/protocol.md</c> §4).
///
/// Está separado del socket a propósito: recibe un <see cref="Stream"/> cualquiera, así
/// que se puede probar entero sobre loopback, sin móvil ni WiFi.
///
/// Al terminar, los dos extremos saben que el otro posee la clave de par, y cada uno
/// tiene su pareja de sobres cifrados con los contadores a cero.
/// </summary>
public static class Handshake
{
    /// <summary>
    /// Lado del PC: espera el <c>HELLO</c> del móvil y responde.
    /// </summary>
    /// <param name="buscarClavePar">
    /// Devuelve la clave de par de ese <c>device_id</c>, o <c>null</c> si no está
    /// emparejado. Un móvil desconocido no recibe respuesta: no se le confirma siquiera
    /// que este PC exista.
    /// </param>
    public static async Task<SesionEstablecida> AceptarAsync(
        Stream flujo,
        string miDeviceId,
        Func<string, byte[]?> buscarClavePar,
        CancellationToken ct = default)
    {
        using var limite = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limite.CancelAfter(Protocolo.TimeoutHandshakeMs);
        var token = limite.Token;

        if (await Framing.LeerAsync(flujo, token).ConfigureAwait(false) is not { } bytesHello ||
            CodecMensajes.Decodificar(bytesHello) is not Hello hello)
        {
            throw new ProtocoloException("Se esperaba un HELLO para abrir la sesión");
        }

        if (hello.V != Protocolo.Version)
        {
            throw new ProtocoloException($"Versión de protocolo no soportada: {hello.V}");
        }

        var clavePar = buscarClavePar(hello.DeviceId)
            ?? throw new ProtocoloException($"El dispositivo {hello.DeviceId} no está emparejado");

        var retoMovil = DecodificarReto(hello.Challenge, "del móvil");
        var retoPc = Cripto.Aleatorio(Cripto.TamReto);

        await Framing.EscribirAsync(
            flujo,
            CodecMensajes.Codificar(new ServerHello
            {
                DeviceId = miDeviceId,
                Challenge = Convert.ToBase64String(retoPc),
            }),
            token).ConfigureAwait(false);

        var claves = Derivacion.ClavesDeSesion(clavePar, retoMovil, retoPc);
        var entrante = new SobreCifrado(claves.ParaRecibir(soyElMovil: false));
        var saliente = new SobreCifrado(claves.ParaEnviar(soyElMovil: false));

        // Que este AUTH se descifre ya demuestra que el otro extremo tiene la clave de
        // par; el eco demuestra además que responde a esta sesión y no a una grabada.
        var auth = CodecMensajes.Decodificar(
            entrante.Abrir(await Framing.LeerAsync(flujo, token).ConfigureAwait(false)));

        if (auth is not Auth autenticacion)
        {
            throw new ProtocoloException($"Se esperaba un AUTH y llegó {auth.T}");
        }

        VerificarEco(autenticacion.Echo, retoPc);

        await Framing.EscribirAsync(
            flujo,
            saliente.Sellar(CodecMensajes.Codificar(new AuthOk { Echo = Convert.ToBase64String(retoMovil) })),
            token).ConfigureAwait(false);

        return new SesionEstablecida(hello.DeviceId, entrante, saliente);
    }

    /// <summary>
    /// Lado del móvil: abre la sesión contra un PC ya emparejado.
    ///
    /// Existe también en C# —aunque el cliente real sea Android— porque permite probar
    /// el handshake completo de punta a punta sobre loopback, y porque un futuro cliente
    /// de escritorio lo usaría tal cual.
    /// </summary>
    /// <param name="deviceIdEsperado">
    /// El <c>device_id</c> del PC activo. Si contesta otro, se corta: es justo lo que
    /// haría un impostor de la red anunciando el mismo servicio mDNS.
    /// </param>
    public static async Task<SesionEstablecida> IniciarAsync(
        Stream flujo,
        string miDeviceId,
        string deviceIdEsperado,
        byte[] clavePar,
        CancellationToken ct = default)
    {
        using var limite = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limite.CancelAfter(Protocolo.TimeoutHandshakeMs);
        var token = limite.Token;

        var retoMovil = Cripto.Aleatorio(Cripto.TamReto);
        await Framing.EscribirAsync(
            flujo,
            CodecMensajes.Codificar(new Hello
            {
                DeviceId = miDeviceId,
                Challenge = Convert.ToBase64String(retoMovil),
            }),
            token).ConfigureAwait(false);

        if (CodecMensajes.Decodificar(await Framing.LeerAsync(flujo, token).ConfigureAwait(false))
            is not ServerHello serverHello)
        {
            throw new ProtocoloException("Se esperaba un SERVER_HELLO");
        }

        if (serverHello.V != Protocolo.Version)
        {
            throw new ProtocoloException($"Versión de protocolo no soportada: {serverHello.V}");
        }

        if (serverHello.DeviceId != deviceIdEsperado)
        {
            throw new ProtocoloException(
                $"Contestó {serverHello.DeviceId} y se esperaba {deviceIdEsperado}");
        }

        var retoPc = DecodificarReto(serverHello.Challenge, "del PC");
        var claves = Derivacion.ClavesDeSesion(clavePar, retoMovil, retoPc);
        var entrante = new SobreCifrado(claves.ParaRecibir(soyElMovil: true));
        var saliente = new SobreCifrado(claves.ParaEnviar(soyElMovil: true));

        await Framing.EscribirAsync(
            flujo,
            saliente.Sellar(CodecMensajes.Codificar(new Auth { Echo = Convert.ToBase64String(retoPc) })),
            token).ConfigureAwait(false);

        var respuesta = CodecMensajes.Decodificar(
            entrante.Abrir(await Framing.LeerAsync(flujo, token).ConfigureAwait(false)));

        if (respuesta is not AuthOk authOk)
        {
            throw new ProtocoloException($"Se esperaba un AUTH_OK y llegó {respuesta.T}");
        }

        VerificarEco(authOk.Echo, retoMovil);

        return new SesionEstablecida(serverHello.DeviceId, entrante, saliente);
    }

    private static byte[] DecodificarReto(string base64, string dueno)
    {
        byte[] reto;
        try
        {
            reto = Convert.FromBase64String(base64);
        }
        catch (FormatException e)
        {
            throw new ProtocoloException($"El reto {dueno} no es base64 válido", e);
        }

        if (reto.Length != Cripto.TamReto)
        {
            throw new ProtocoloException(
                $"El reto {dueno} tiene {reto.Length} bytes y debe tener {Cripto.TamReto}");
        }

        return reto;
    }

    private static void VerificarEco(string ecoBase64, byte[] esperado)
    {
        byte[] eco;
        try
        {
            eco = Convert.FromBase64String(ecoBase64);
        }
        catch (FormatException e)
        {
            throw new ProtocoloException("El eco del reto no es base64 válido", e);
        }

        if (!Cripto.IgualesEnTiempoConstante(eco, esperado))
        {
            throw new ProtocoloException("El eco del reto no coincide");
        }
    }
}

/// <summary>Una sesión autenticada, con sus dos sobres listos.</summary>
public sealed class SesionEstablecida
{
    public SesionEstablecida(string deviceIdRemoto, SobreCifrado entrante, SobreCifrado saliente)
    {
        DeviceIdRemoto = deviceIdRemoto;
        Entrante = entrante;
        Saliente = saliente;
    }

    public string DeviceIdRemoto { get; }

    public SobreCifrado Entrante { get; }

    public SobreCifrado Saliente { get; }

    public void Limpiar()
    {
        Entrante.Limpiar();
        Saliente.Limpiar();
    }
}
