using System.Text.Json;
using System.Text.Json.Serialization;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Protocolo.Sesion;

/// <summary>
/// El emparejamiento (<c>docs/protocol.md</c> §3), que ocurre una vez por par de
/// dispositivos.
///
/// Lo único que impide que cualquiera de la red local se empareje por su cuenta es el
/// token del QR: demuestra que quien se empareja ha tenido delante la pantalla del PC.
/// Por eso el token es de un solo uso y caduca en dos minutos.
/// </summary>
public static class Emparejamiento
{
    /// <summary>
    /// Lado del PC: atiende una petición de emparejamiento.
    /// </summary>
    /// <param name="consumirToken">
    /// Comprueba el token y lo invalida en el mismo paso. Devuelve <c>false</c> si no
    /// existe, ha caducado o ya se usó. Que la comprobación y el consumo sean atómicos
    /// evita que dos peticiones simultáneas aprovechen el mismo token.
    /// </param>
    public static async Task<ResultadoEmparejamiento> AceptarAsync(
        Stream flujo,
        byte[] miPrivada,
        string miDeviceId,
        string miNombre,
        Func<byte[], bool> consumirToken,
        CancellationToken ct = default)
    {
        using var limite = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limite.CancelAfter(Protocolo.TimeoutHandshakeMs);
        var token = limite.Token;

        if (CodecMensajes.Decodificar(await Framing.LeerAsync(flujo, token).ConfigureAwait(false))
            is not PairRequest peticion)
        {
            throw new ProtocoloException("Se esperaba un PAIR_REQUEST");
        }

        if (peticion.V != Protocolo.Version)
        {
            throw new ProtocoloException($"Versión de protocolo no soportada: {peticion.V}");
        }

        var tokenRecibido = DecodificarBase64(peticion.Token, "el token");
        if (!consumirToken(tokenRecibido))
        {
            // No se contesta nada ni se explica el motivo: a quien lo intenta sin haber
            // visto el QR no se le confirma siquiera que este PC hable el protocolo.
            throw new ProtocoloException("Token de emparejamiento no válido");
        }

        var publicaDelMovil = DecodificarBase64(peticion.Pk, "la clave pública");
        if (publicaDelMovil.Length != Cripto.TamClave)
        {
            throw new ProtocoloException(
                $"La clave pública tiene {publicaDelMovil.Length} bytes y debe tener {Cripto.TamClave}");
        }

        var clavePar = Derivacion.ClavePar(miPrivada, publicaDelMovil);
        var huella = Derivacion.Huella(Cripto.ClavePublicaDe(miPrivada), publicaDelMovil);

        var (retoMovil, retoPc) = Derivacion.RetosDeEmparejamiento(tokenRecibido);
        var claves = Derivacion.ClavesDeSesion(clavePar, retoMovil, retoPc);
        var entrante = new SobreCifrado(claves.ParaRecibir(soyElMovil: false));
        var saliente = new SobreCifrado(claves.ParaEnviar(soyElMovil: false));

        await Framing.EscribirAsync(
            flujo,
            saliente.Sellar(CodecMensajes.Codificar(new PairConfirm
            {
                DeviceId = miDeviceId,
                Name = miNombre,
                Fingerprint = huella,
            })),
            token).ConfigureAwait(false);

        var respuesta = CodecMensajes.Decodificar(
            entrante.Abrir(await Framing.LeerAsync(flujo, token).ConfigureAwait(false)));

        if (respuesta is not PairAck ack)
        {
            throw new ProtocoloException($"Se esperaba un PAIR_ACK y llegó {respuesta.T}");
        }

        if (ack.Fingerprint != huella)
        {
            throw new ProtocoloException("Las huellas no coinciden");
        }

        return new ResultadoEmparejamiento(peticion.DeviceId, peticion.Name, publicaDelMovil, clavePar, huella);
    }

    /// <summary>
    /// Lado del móvil: se empareja con el PC del QR.
    /// </summary>
    public static async Task<ResultadoEmparejamiento> IniciarAsync(
        Stream flujo,
        byte[] miPrivada,
        string miDeviceId,
        string miNombre,
        DatosQr qr,
        CancellationToken ct = default)
    {
        using var limite = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limite.CancelAfter(Protocolo.TimeoutHandshakeMs);
        var token = limite.Token;

        var publicaDelPc = DecodificarBase64(qr.Pk, "la clave pública del PC");
        var tokenQr = DecodificarBase64(qr.Token, "el token del QR");

        await Framing.EscribirAsync(
            flujo,
            CodecMensajes.Codificar(new PairRequest
            {
                Pk = Convert.ToBase64String(Cripto.ClavePublicaDe(miPrivada)),
                DeviceId = miDeviceId,
                Name = miNombre,
                Token = qr.Token,
            }),
            token).ConfigureAwait(false);

        var clavePar = Derivacion.ClavePar(miPrivada, publicaDelPc);
        var huella = Derivacion.Huella(Cripto.ClavePublicaDe(miPrivada), publicaDelPc);

        var (retoMovil, retoPc) = Derivacion.RetosDeEmparejamiento(tokenQr);
        var claves = Derivacion.ClavesDeSesion(clavePar, retoMovil, retoPc);
        var entrante = new SobreCifrado(claves.ParaRecibir(soyElMovil: true));
        var saliente = new SobreCifrado(claves.ParaEnviar(soyElMovil: true));

        var confirmacion = CodecMensajes.Decodificar(
            entrante.Abrir(await Framing.LeerAsync(flujo, token).ConfigureAwait(false)));

        if (confirmacion is not PairConfirm confirmado)
        {
            throw new ProtocoloException($"Se esperaba un PAIR_CONFIRM y llegó {confirmacion.T}");
        }

        if (confirmado.Fingerprint != huella)
        {
            throw new ProtocoloException("Las huellas no coinciden");
        }

        await Framing.EscribirAsync(
            flujo,
            saliente.Sellar(CodecMensajes.Codificar(new PairAck { Fingerprint = huella })),
            token).ConfigureAwait(false);

        await EsperarAlCierreDelPcAsync(flujo, token).ConfigureAwait(false);

        return new ResultadoEmparejamiento(confirmado.DeviceId, confirmado.Name, publicaDelPc, clavePar, huella);
    }

    /// <summary>
    /// Espera a que el PC cierre la conexión, que es su forma de acusar recibo del
    /// <c>PAIR_ACK</c> (<c>docs/protocol.md</c> §3.2 paso 6).
    ///
    /// El <c>PAIR_ACK</c> es el último mensaje, así que quien lo envía no sabría si
    /// llegó. Como el PC cierra solo después de haber guardado, ese cierre es la única
    /// señal de que el emparejamiento existe en los dos lados. Sin esto, un PC que
    /// falle al guardar dejaría al móvil creyendo que está emparejado mientras el PC lo
    /// rechaza en cada conexión.
    /// </summary>
    private static async Task EsperarAlCierreDelPcAsync(Stream flujo, CancellationToken ct)
    {
        try
        {
            await Framing.LeerAsync(flujo, ct).ConfigureAwait(false);
        }
        catch (EndOfStreamException)
        {
            // Es lo que se espera: el PC guardó y cerró.
            return;
        }
        catch (IOException e)
        {
            throw new ProtocoloException(
                "El PC cortó la conexión sin confirmar el emparejamiento", e);
        }

        // Si en vez de cerrar manda algo, no está siguiendo este protocolo.
        throw new ProtocoloException("El PC respondió al PAIR_ACK en vez de cerrar la conexión");
    }

    private static byte[] DecodificarBase64(string valor, string que)
    {
        try
        {
            return Convert.FromBase64String(valor);
        }
        catch (FormatException e)
        {
            throw new ProtocoloException($"{que} no es base64 válido", e);
        }
    }
}

/// <summary>Lo que queda tras emparejarse: con esto se puede abrir sesión mañana.</summary>
public sealed class ResultadoEmparejamiento
{
    public ResultadoEmparejamiento(
        string deviceIdRemoto,
        string nombreRemoto,
        byte[] publicaRemota,
        byte[] clavePar,
        string huella)
    {
        DeviceIdRemoto = deviceIdRemoto;
        NombreRemoto = nombreRemoto;
        PublicaRemota = publicaRemota;
        ClavePar = clavePar;
        Huella = huella;
    }

    public string DeviceIdRemoto { get; }

    public string NombreRemoto { get; }

    public byte[] PublicaRemota { get; }

    /// <summary>
    /// Se guarda la pública, no esto: la clave de par se recalcula siempre desde las dos
    /// públicas y la privada propia.
    /// </summary>
    public byte[] ClavePar { get; }

    public string Huella { get; }
}

/// <summary>
/// El contenido del QR (<c>docs/protocol.md</c> §3.1). Es el único mensaje del protocolo
/// que no viaja por el socket, sino por la pantalla y la cámara.
/// </summary>
public sealed record DatosQr
{
    [JsonPropertyName("v")]
    public int V { get; init; } = Protocolo.Version;

    [JsonPropertyName("pk")]
    public required string Pk { get; init; }

    [JsonPropertyName("ip")]
    public required string Ip { get; init; }

    [JsonPropertyName("port")]
    public required int Port { get; init; }

    [JsonPropertyName("token")]
    public required string Token { get; init; }

    [JsonPropertyName("name")]
    public required string Name { get; init; }

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    public string ASerializar() => JsonSerializer.Serialize(this, CodecMensajes.Opciones);

    public static DatosQr Leer(string json)
    {
        DatosQr? datos;
        try
        {
            datos = JsonSerializer.Deserialize<DatosQr>(json, CodecMensajes.Opciones);
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException)
        {
            throw new ProtocoloException("El QR no contiene un emparejamiento de DracPaste", e);
        }

        if (datos is null)
        {
            throw new ProtocoloException("El QR está vacío");
        }

        if (datos.V != Protocolo.Version)
        {
            throw new ProtocoloException($"El QR es de la versión {datos.V} y esta app habla la {Protocolo.Version}");
        }

        return datos;
    }
}
