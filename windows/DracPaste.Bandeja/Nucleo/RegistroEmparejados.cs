using System.Text.Json;
using System.Text.Json.Serialization;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Los móviles emparejados con este PC.
///
/// Solo se guarda la clave <b>pública</b> de cada uno: la clave de par se recalcula
/// cuando hace falta a partir de la privada de este PC (§2.2). Así, este fichero no
/// contiene ningún secreto —una pública no lo es— y quien lo lea no puede descifrar
/// nada.
///
/// La estructura admite varios móviles porque el protocolo es por pareja: cada uno tiene
/// su clave, y desemparejar a uno no afecta a los demás.
/// </summary>
public sealed class RegistroEmparejados
{
    private const string NombreFichero = "emparejados.json";

    /// <summary>
    /// Sin esto, el codificador por defecto escapa el '+' de base64 y cualquier acento
    /// del nombre del móvil. El fichero seguiría siendo válido, pero ilegible.
    /// </summary>
    private static readonly JsonSerializerOptions Json = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = true,
    };

    private readonly string _ruta;
    private readonly object _candado = new();
    private readonly Dictionary<string, DispositivoEmparejado> _porDeviceId;

    private RegistroEmparejados(string ruta, Dictionary<string, DispositivoEmparejado> dispositivos)
    {
        _ruta = ruta;
        _porDeviceId = dispositivos;
    }

    public static RegistroEmparejados Cargar(string? carpeta = null)
    {
        var directorio = carpeta ?? Identidad.CarpetaDeDatos;
        Directory.CreateDirectory(directorio);
        var ruta = Path.Combine(directorio, NombreFichero);

        if (!File.Exists(ruta))
        {
            return new RegistroEmparejados(ruta, new Dictionary<string, DispositivoEmparejado>());
        }

        try
        {
            var lista = JsonSerializer.Deserialize<List<DispositivoEmparejado>>(File.ReadAllText(ruta))
                ?? new List<DispositivoEmparejado>();
            return new RegistroEmparejados(ruta, lista.ToDictionary(d => d.DeviceId));
        }
        catch (Exception e) when (e is JsonException or ArgumentException)
        {
            // Un registro ilegible se aparta: es preferible que el usuario tenga que
            // volver a emparejar a que la app no arranque.
            File.Move(ruta, ruta + $".ilegible-{DateTime.Now:yyyyMMddHHmmss}", overwrite: true);
            return new RegistroEmparejados(ruta, new Dictionary<string, DispositivoEmparejado>());
        }
    }

    public IReadOnlyCollection<DispositivoEmparejado> Todos
    {
        get
        {
            lock (_candado)
            {
                return _porDeviceId.Values.ToList();
            }
        }
    }

    public DispositivoEmparejado? Buscar(string deviceId)
    {
        lock (_candado)
        {
            return _porDeviceId.GetValueOrDefault(deviceId);
        }
    }

    /// <summary>Añade o actualiza un emparejamiento y lo persiste.</summary>
    public void Guardar(DispositivoEmparejado dispositivo)
    {
        lock (_candado)
        {
            _porDeviceId[dispositivo.DeviceId] = dispositivo;
            Persistir();
        }
    }

    /// <summary>Olvida un dispositivo. Devuelve si estaba.</summary>
    public bool Olvidar(string deviceId)
    {
        lock (_candado)
        {
            if (!_porDeviceId.Remove(deviceId))
            {
                return false;
            }

            Persistir();
            return true;
        }
    }

    /// <summary>
    /// Clave de par de un dispositivo, recalculada al vuelo. Es lo que necesita el
    /// handshake (§4).
    /// </summary>
    public byte[]? ClaveParDe(string deviceId, Identidad identidad)
    {
        var dispositivo = Buscar(deviceId);
        if (dispositivo is null)
        {
            return null;
        }

        return Derivacion.ClavePar(identidad.Privada, Convert.FromBase64String(dispositivo.PublicaBase64));
    }

    private void Persistir()
    {
        var temporal = _ruta + ".tmp";
        File.WriteAllText(temporal, JsonSerializer.Serialize(_porDeviceId.Values.ToList(), Json));
        File.Move(temporal, _ruta, overwrite: true);
    }
}

/// <summary>Un móvil emparejado con este PC.</summary>
public sealed record DispositivoEmparejado
{
    [JsonPropertyName("device_id")]
    public required string DeviceId { get; init; }

    [JsonPropertyName("nombre")]
    public required string Nombre { get; init; }

    /// <summary>Su clave pública. No es un secreto.</summary>
    [JsonPropertyName("publica")]
    public required string PublicaBase64 { get; init; }

    [JsonPropertyName("huella")]
    public required string Huella { get; init; }

    [JsonPropertyName("emparejado_en")]
    public required DateTimeOffset EmparejadoEn { get; init; }
}
