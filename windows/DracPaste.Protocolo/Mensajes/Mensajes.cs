using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Protocolo.Mensajes;

/// <summary>
/// Los mensajes del protocolo (<c>docs/protocol.md</c> §3, §4 y §5) y su ida y vuelta a JSON.
///
/// El campo <c>t</c> identifica el tipo. Se decodifica en dos pasos —primero se mira
/// <c>t</c>, después se deserializa la clase concreta— en vez de usar el polimorfismo de
/// System.Text.Json, porque el discriminador tiene que ser exactamente <c>t</c> en el
/// cable y un tipo desconocido no puede reventar: v1 debe poder ignorar en silencio lo
/// que le mande una versión más nueva.
/// </summary>
public abstract record Mensaje
{
    [JsonPropertyName("t")]
    public abstract string T { get; init; }
}

// -------------------------------------------------------------- Emparejamiento

public sealed record PairRequest : Mensaje
{
    public const string Tipo = "PAIR_REQUEST";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("v")]
    public int V { get; init; } = Protocolo.Version;

    [JsonPropertyName("pk")]
    public required string Pk { get; init; }

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("name")]
    public required string Name { get; init; }

    [JsonPropertyName("token")]
    public required string Token { get; init; }
}

public sealed record PairConfirm : Mensaje
{
    public const string Tipo = "PAIR_CONFIRM";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("name")]
    public required string Name { get; init; }

    [JsonPropertyName("fingerprint")]
    public required string Fingerprint { get; init; }
}

public sealed record PairAck : Mensaje
{
    public const string Tipo = "PAIR_ACK";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("fingerprint")]
    public required string Fingerprint { get; init; }
}

// ------------------------------------------------------------------ Handshake

public sealed record Hello : Mensaje
{
    public const string Tipo = "HELLO";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("v")]
    public int V { get; init; } = Protocolo.Version;

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("challenge")]
    public required string Challenge { get; init; }
}

public sealed record ServerHello : Mensaje
{
    public const string Tipo = "SERVER_HELLO";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("v")]
    public int V { get; init; } = Protocolo.Version;

    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("challenge")]
    public required string Challenge { get; init; }
}

public sealed record Auth : Mensaje
{
    public const string Tipo = "AUTH";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("echo")]
    public required string Echo { get; init; }
}

public sealed record AuthOk : Mensaje
{
    public const string Tipo = "AUTH_OK";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("echo")]
    public required string Echo { get; init; }
}

// --------------------------------------------------------------------- Sesión

public sealed record Clip : Mensaje
{
    public const string Tipo = "CLIP";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("type")]
    public string Type { get; init; } = Protocolo.TipoTexto;

    [JsonPropertyName("payload")]
    public required string Payload { get; init; }

    [JsonPropertyName("timestamp_ms")]
    public required long TimestampMs { get; init; }

    [JsonPropertyName("origin_id")]
    public required string OriginId { get; init; }

    /// <summary>
    /// Construye un CLIP de texto calculando su <c>origin_id</c>.
    /// </summary>
    /// <exception cref="ProtocoloException">
    /// Si el texto está vacío o pasa del máximo. Se comprueba aquí, en el único sitio
    /// por el que se crean los clips, y no en cada llamador.
    /// </exception>
    public static Clip DeTexto(string texto, long? timestampMs = null)
    {
        ArgumentNullException.ThrowIfNull(texto);
        var bytes = Encoding.UTF8.GetBytes(texto);
        if (bytes.Length == 0)
        {
            throw new ProtocoloException("No se envían clips vacíos");
        }

        if (bytes.Length > Protocolo.MaxClipBytes)
        {
            throw new ProtocoloException(
                $"El clip ocupa {bytes.Length} bytes y el máximo es {Protocolo.MaxClipBytes}");
        }

        return new Clip
        {
            Payload = Convert.ToBase64String(bytes),
            TimestampMs = timestampMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            OriginId = OrigenDe(texto),
        };
    }

    /// <summary><c>origin_id</c> = SHA-256 del texto en UTF-8, truncado a 16 bytes, en hex.</summary>
    public static string OrigenDe(string texto) =>
        Hex.ToHex(Cripto.Sha256(Encoding.UTF8.GetBytes(texto)).AsSpan(0, 16));

    /// <summary>El texto del clip.</summary>
    public string Texto() => Encoding.UTF8.GetString(Convert.FromBase64String(Payload));

    /// <summary>¿Es un tipo que esta versión sabe manejar?</summary>
    public bool EsTexto() => Type == Protocolo.TipoTexto;
}

public sealed record Ping : Mensaje
{
    public const string Tipo = "PING";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("seq")]
    public required long Seq { get; init; }
}

public sealed record Pong : Mensaje
{
    public const string Tipo = "PONG";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;

    [JsonPropertyName("seq")]
    public required long Seq { get; init; }
}

public sealed record Unpair : Mensaje
{
    public const string Tipo = "UNPAIR";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;
}

public sealed record Bye : Mensaje
{
    public const string Tipo = "BYE";

    [JsonPropertyName("t")]
    public override string T { get; init; } = Tipo;
}

/// <summary>Un tipo que esta versión no conoce. No es un error: se ignora.</summary>
public sealed record MensajeDesconocido : Mensaje
{
    [JsonPropertyName("t")]
    public override string T { get; init; } = string.Empty;
}

// -------------------------------------------------------------- Codificación

public static class CodecMensajes
{
    internal static readonly JsonSerializerOptions Opciones = new()
    {
        // Una versión futura puede añadir campos; no es motivo para cortar la conexión.
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = null,

        // Sin esto, el codificador por defecto escapa '+' como + y todo lo que no
        // sea ASCII. Los payloads del protocolo van en base64 —donde '+' es un carácter
        // normal— y los nombres de dispositivo llevan acentos: el mensaje seguiría
        // siendo JSON válido, pero abultaría el triple y sería ilegible al depurarlo.
        // "Unsafe" se refiere a incrustar el resultado en HTML, que no es el caso.
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static byte[] Codificar(Mensaje mensaje)
    {
        ArgumentNullException.ThrowIfNull(mensaje);
        var texto = mensaje switch
        {
            PairRequest m => JsonSerializer.Serialize(m, Opciones),
            PairConfirm m => JsonSerializer.Serialize(m, Opciones),
            PairAck m => JsonSerializer.Serialize(m, Opciones),
            Hello m => JsonSerializer.Serialize(m, Opciones),
            ServerHello m => JsonSerializer.Serialize(m, Opciones),
            Auth m => JsonSerializer.Serialize(m, Opciones),
            AuthOk m => JsonSerializer.Serialize(m, Opciones),
            Clip m => JsonSerializer.Serialize(m, Opciones),
            Ping m => JsonSerializer.Serialize(m, Opciones),
            Pong m => JsonSerializer.Serialize(m, Opciones),
            Unpair m => JsonSerializer.Serialize(m, Opciones),
            Bye m => JsonSerializer.Serialize(m, Opciones),
            MensajeDesconocido m => throw new ProtocoloException(
                $"No se envía un mensaje de tipo desconocido: {m.T}"),
            _ => throw new ProtocoloException($"Tipo de mensaje no contemplado: {mensaje.GetType().Name}"),
        };

        return Encoding.UTF8.GetBytes(texto);
    }

    /// <summary>
    /// Decodifica un mensaje. Un tipo desconocido devuelve <see cref="MensajeDesconocido"/>
    /// en vez de lanzar: quien llama decide ignorarlo, que es lo que manda el protocolo.
    /// </summary>
    public static Mensaje Decodificar(byte[] bytes)
    {
        ArgumentNullException.ThrowIfNull(bytes);

        JsonObject objeto;
        try
        {
            objeto = JsonNode.Parse(Encoding.UTF8.GetString(bytes)) as JsonObject
                ?? throw new ProtocoloException("El mensaje no es un objeto JSON");
        }
        catch (JsonException e)
        {
            throw new ProtocoloException("El mensaje no es JSON válido", e);
        }

        var tipo = objeto["t"]?.GetValue<string>()
            ?? throw new ProtocoloException("El mensaje no lleva campo 't'");

        try
        {
            Mensaje? mensaje = tipo switch
            {
                PairRequest.Tipo => objeto.Deserialize<PairRequest>(Opciones),
                PairConfirm.Tipo => objeto.Deserialize<PairConfirm>(Opciones),
                PairAck.Tipo => objeto.Deserialize<PairAck>(Opciones),
                Hello.Tipo => objeto.Deserialize<Hello>(Opciones),
                ServerHello.Tipo => objeto.Deserialize<ServerHello>(Opciones),
                Auth.Tipo => objeto.Deserialize<Auth>(Opciones),
                AuthOk.Tipo => objeto.Deserialize<AuthOk>(Opciones),
                Clip.Tipo => objeto.Deserialize<Clip>(Opciones),
                Ping.Tipo => objeto.Deserialize<Ping>(Opciones),
                Pong.Tipo => objeto.Deserialize<Pong>(Opciones),
                Unpair.Tipo => objeto.Deserialize<Unpair>(Opciones),
                Bye.Tipo => objeto.Deserialize<Bye>(Opciones),
                _ => new MensajeDesconocido { T = tipo },
            };

            return mensaje ?? throw new ProtocoloException($"El mensaje '{tipo}' quedó vacío al decodificar");
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException)
        {
            throw new ProtocoloException($"El mensaje '{tipo}' no tiene la forma esperada", e);
        }
    }
}
