namespace DracPaste.Protocolo.Seguridad;

/// <summary>
/// Hex en minúscula, que es como el protocolo representa los identificadores
/// (<c>device_id</c>, <c>origin_id</c>, claves en los vectores de prueba).
/// </summary>
public static class Hex
{
    public static string ToHex(ReadOnlySpan<byte> datos) => Convert.ToHexString(datos).ToLowerInvariant();

    public static byte[] FromHex(string hex)
    {
        ArgumentNullException.ThrowIfNull(hex);
        if (hex.Length % 2 != 0)
        {
            throw new ArgumentException("Una cadena hexadecimal debe tener un número par de dígitos", nameof(hex));
        }

        return Convert.FromHexString(hex);
    }
}
