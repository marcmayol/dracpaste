using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// La identidad de este PC: su par de claves X25519 y su <c>device_id</c>
/// (<c>docs/protocol.md</c> §2.1).
///
/// Se genera una vez y se guarda en <c>%LOCALAPPDATA%\DracPaste\identidad.json</c>. La
/// clave privada va cifrada con DPAPI en el ámbito del usuario actual: otro usuario del
/// mismo equipo no puede descifrarla ni copiando el fichero, porque la clave de DPAPI
/// deriva de sus credenciales de inicio de sesión.
///
/// El <c>device_id</c> son 16 bytes aleatorios. <b>No</b> deriva del nombre del equipo,
/// del número de serie ni de ningún identificador de hardware: sería un identificador
/// persistente y rastreable, y este proyecto va justo de lo contrario.
/// </summary>
public sealed class Identidad
{
    private const string NombreFichero = "identidad.json";

    /// <summary>
    /// Entropía adicional de DPAPI. No es un secreto —está en el código fuente— pero
    /// ata el cifrado a esta aplicación: un blob de DracPaste no lo descifra por
    /// accidente otro programa del mismo usuario.
    /// </summary>
    private static readonly byte[] EntropiaApp = "DracPaste/v1/identidad"u8.ToArray();

    /// <summary>
    /// Sin esto, el codificador por defecto escapa el '+' de base64 y cualquier acento
    /// del nombre del equipo. El fichero seguiría siendo válido, pero ilegible.
    /// </summary>
    private static readonly JsonSerializerOptions Json = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = true,
    };

    private Identidad(byte[] privada, byte[] publica, string deviceId, string nombre)
    {
        Privada = privada;
        Publica = publica;
        DeviceId = deviceId;
        Nombre = nombre;
    }

    public byte[] Privada { get; }

    public byte[] Publica { get; }

    public string DeviceId { get; }

    /// <summary>Nombre legible que verá el usuario en el móvil.</summary>
    public string Nombre { get; }

    public static string CarpetaDeDatos =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "DracPaste");

    /// <summary>
    /// Carga la identidad guardada, o crea una nueva la primera vez.
    /// </summary>
    public static Identidad CargarOCrear(string? carpeta = null)
    {
        var directorio = carpeta ?? CarpetaDeDatos;
        Directory.CreateDirectory(directorio);
        var ruta = Path.Combine(directorio, NombreFichero);

        if (File.Exists(ruta))
        {
            try
            {
                return Leer(ruta);
            }
            catch (Exception e) when (e is JsonException or CryptographicException or FormatException)
            {
                // Una identidad ilegible no se puede recuperar: sin la privada, los
                // emparejamientos que dependían de ella ya no valen. Se aparta el
                // fichero en vez de borrarlo, por si el usuario quiere mirarlo.
                var apartado = ruta + $".ilegible-{DateTime.Now:yyyyMMddHHmmss}";
                File.Move(ruta, apartado, overwrite: true);
            }
        }

        var nueva = Generar();
        nueva.Guardar(directorio);
        return nueva;
    }

    /// <summary>Genera una identidad nueva sin guardarla.</summary>
    public static Identidad Generar(string? nombre = null)
    {
        var par = Cripto.GenerarParDeClaves();
        return new Identidad(
            par.Privada,
            par.Publica,
            Hex.ToHex(Cripto.Aleatorio(16)),
            nombre ?? Environment.MachineName);
    }

    public void Guardar(string? carpeta = null)
    {
        var directorio = carpeta ?? CarpetaDeDatos;
        Directory.CreateDirectory(directorio);

        var guardado = new IdentidadGuardada
        {
            DeviceId = DeviceId,
            Nombre = Nombre,
            Publica = Convert.ToBase64String(Publica),
            PrivadaProtegida = Convert.ToBase64String(
                ProtectedData.Protect(Privada, EntropiaApp, DataProtectionScope.CurrentUser)),
        };

        // Se escribe a un temporal y se reemplaza: si el PC se apaga a mitad —y a este
        // usuario le ha pasado— no queda un fichero de identidad a medias que dejaría la
        // app sin poder hablar con ningún móvil emparejado.
        var ruta = Path.Combine(directorio, NombreFichero);
        var temporal = ruta + ".tmp";
        File.WriteAllText(temporal, JsonSerializer.Serialize(guardado, Json));
        File.Move(temporal, ruta, overwrite: true);
    }

    private static Identidad Leer(string ruta)
    {
        var guardado = JsonSerializer.Deserialize<IdentidadGuardada>(File.ReadAllText(ruta))
            ?? throw new JsonException("El fichero de identidad está vacío");

        var privada = ProtectedData.Unprotect(
            Convert.FromBase64String(guardado.PrivadaProtegida),
            EntropiaApp,
            DataProtectionScope.CurrentUser);

        var publica = Convert.FromBase64String(guardado.Publica);

        // La pública se recalcula y se compara: si el fichero se hubiera manipulado para
        // que la pública anunciada no fuera la de la privada, todos los emparejamientos
        // fallarían con un error incomprensible en vez de aquí, que se ve.
        if (!Cripto.IgualesEnTiempoConstante(publica, Cripto.ClavePublicaDe(privada)))
        {
            throw new CryptographicException("La clave pública guardada no corresponde a la privada");
        }

        return new Identidad(privada, publica, guardado.DeviceId, guardado.Nombre);
    }

    /// <summary>Huella de este PC, para enseñarla junto a la del móvil.</summary>
    public string HuellaCon(byte[] publicaDelOtro) => Derivacion.Huella(Publica, publicaDelOtro);

    private sealed record IdentidadGuardada
    {
        [JsonPropertyName("device_id")]
        public required string DeviceId { get; init; }

        [JsonPropertyName("nombre")]
        public required string Nombre { get; init; }

        [JsonPropertyName("publica")]
        public required string Publica { get; init; }

        [JsonPropertyName("privada_dpapi")]
        public required string PrivadaProtegida { get; init; }
    }
}
