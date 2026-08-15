using System.Security.Cryptography;
using NSec.Cryptography;

namespace DracPaste.Protocolo.Seguridad;

/// <summary>
/// Primitivas criptográficas del protocolo, tal y como las fija <c>docs/protocol.md</c> §2.
///
/// Se apoya en NSec (libsodium) para X25519 y ChaCha20-Poly1305, y en el HKDF del
/// propio .NET cuando el material de partida ya es un array de bytes.
///
/// El gemelo Kotlin de este fichero usa Bouncy Castle. Que las dos implementaciones se
/// entiendan no es un acto de fe: los algoritmos son estándares RFC y los vectores de
/// <c>docs/protocol.md</c> §7 se comprueban en los dos lados
/// (ver <c>docs/decisions.md</c> D-003).
/// </summary>
public static class Cripto
{
    public const int TamClave = 32;
    public const int TamNonce = 12;
    public const int TamTag = 16;
    public const int TamReto = 16;

    private static readonly KeyAgreementAlgorithm Acuerdo = KeyAgreementAlgorithm.X25519;
    private static readonly AeadAlgorithm Aead = AeadAlgorithm.ChaCha20Poly1305;

    // ---------------------------------------------------------------- X25519

    /// <summary>Genera un par de claves X25519 nuevo.</summary>
    public static ParDeClaves GenerarParDeClaves()
    {
        var privada = Aleatorio(TamClave);
        return new ParDeClaves(privada, ClavePublicaDe(privada));
    }

    /// <summary>Deriva la clave pública que corresponde a una privada.</summary>
    public static byte[] ClavePublicaDe(byte[] privada)
    {
        ArgumentNullException.ThrowIfNull(privada);
        if (privada.Length != TamClave)
        {
            throw new ArgumentException($"La clave privada debe tener {TamClave} bytes", nameof(privada));
        }

        using var clave = Key.Import(Acuerdo, privada, KeyBlobFormat.RawPrivateKey);
        return clave.PublicKey.Export(KeyBlobFormat.RawPublicKey);
    }

    /// <summary>
    /// Acuerda el secreto X25519 y lo pasa por HKDF en un solo paso.
    ///
    /// No hay forma de sacar el secreto crudo, y es a propósito: NSec lo mantiene
    /// opaco para que no acabe copiado por ahí. Como el protocolo nunca usa el secreto
    /// directamente —siempre pasa por HKDF (§2.2)—, no se pierde nada.
    /// </summary>
    /// <exception cref="ClaveInvalidaException">
    /// Si la clave pública del otro es un punto de orden pequeño. Eso no es un secreto:
    /// es un intento de forzar una clave que el atacante ya conoce.
    /// </exception>
    public static byte[] AcordarYDerivar(byte[] privada, byte[] publicaDelOtro, byte[] salt, byte[] info, int longitud = TamClave)
    {
        ArgumentNullException.ThrowIfNull(privada);
        ArgumentNullException.ThrowIfNull(publicaDelOtro);
        if (privada.Length != TamClave)
        {
            throw new ArgumentException($"La clave privada debe tener {TamClave} bytes", nameof(privada));
        }

        if (publicaDelOtro.Length != TamClave)
        {
            throw new ArgumentException($"La clave pública debe tener {TamClave} bytes", nameof(publicaDelOtro));
        }

        using var miClave = Key.Import(Acuerdo, privada, KeyBlobFormat.RawPrivateKey);

        PublicKey suClave;
        try
        {
            suClave = PublicKey.Import(Acuerdo, publicaDelOtro, KeyBlobFormat.RawPublicKey);
        }
        catch (FormatException e)
        {
            throw new ClaveInvalidaException("La clave pública recibida no es válida", e);
        }

        using var secreto = Acuerdo.Agree(miClave, suClave)
            ?? throw new ClaveInvalidaException("La clave pública recibida es un punto de orden pequeño");

        return KeyDerivationAlgorithm.HkdfSha256.DeriveBytes(secreto, salt, info, longitud);
    }

    // ------------------------------------------------------------------ HKDF

    /// <summary>HKDF-SHA256 (RFC 5869) sobre material que ya está en memoria.</summary>
    public static byte[] Hkdf(byte[] ikm, byte[] salt, byte[] info, int longitud = TamClave)
    {
        return HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, longitud, salt, info);
    }

    // ------------------------------------------------- ChaCha20-Poly1305 AEAD

    /// <summary>
    /// Cifra y autentica. Devuelve <c>ciphertext || tag</c>.
    ///
    /// El AAD va vacío por decisión del protocolo (§2.4).
    /// </summary>
    public static byte[] Cifrar(byte[] clave, byte[] nonce, byte[] textoPlano)
    {
        ValidarClaveYNonce(clave, nonce);
        using var k = Key.Import(Aead, clave, KeyBlobFormat.RawSymmetricKey);
        return Aead.Encrypt(k, nonce, ReadOnlySpan<byte>.Empty, textoPlano);
    }

    /// <summary>
    /// Descifra y verifica el tag.
    /// </summary>
    /// <exception cref="AutenticacionFallidaException">
    /// Si el tag no cuadra. No se distingue entre "clave equivocada", "nonce equivocado"
    /// y "alguien ha manipulado los bytes": para quien llama son el mismo problema, y
    /// contestar cuál es filtra información.
    /// </exception>
    public static byte[] Descifrar(byte[] clave, byte[] nonce, byte[] cifrado)
    {
        ValidarClaveYNonce(clave, nonce);
        ArgumentNullException.ThrowIfNull(cifrado);
        if (cifrado.Length < TamTag)
        {
            throw new AutenticacionFallidaException("El mensaje cifrado es más corto que su propio tag");
        }

        using var k = Key.Import(Aead, clave, KeyBlobFormat.RawSymmetricKey);
        return Aead.Decrypt(k, nonce, ReadOnlySpan<byte>.Empty, cifrado)
            ?? throw new AutenticacionFallidaException("El mensaje no supera la verificación de integridad");
    }

    /// <summary>
    /// Construye el nonce del protocolo: 4 bytes a cero y el contador en 8 bytes
    /// big-endian (§2.4).
    /// </summary>
    public static byte[] NonceDeContador(long contador)
    {
        if (contador < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(contador), "El contador de nonces no puede ser negativo");
        }

        var nonce = new byte[TamNonce];
        for (var i = 0; i < 8; i++)
        {
            nonce[TamNonce - 1 - i] = (byte)((contador >>> (8 * i)) & 0xFF);
        }

        return nonce;
    }

    // ----------------------------------------------------------------- Varios

    /// <summary>SHA-256.</summary>
    public static byte[] Sha256(byte[] datos) => SHA256.HashData(datos);

    /// <summary>Bytes aleatorios criptográficamente seguros.</summary>
    public static byte[] Aleatorio(int cuantos) => RandomNumberGenerator.GetBytes(cuantos);

    /// <summary>
    /// Comparación en tiempo constante. Comparar retos o huellas con una comparación
    /// normal deja escapar por el tiempo de ejecución cuántos bytes ha acertado quien
    /// lo intenta.
    /// </summary>
    public static bool IgualesEnTiempoConstante(byte[] a, byte[] b) =>
        CryptographicOperations.FixedTimeEquals(a, b);

    /// <summary>Borra una clave de memoria en cuanto deja de hacer falta.</summary>
    public static void Limpiar(params byte[]?[] secretos)
    {
        foreach (var secreto in secretos)
        {
            if (secreto is not null)
            {
                CryptographicOperations.ZeroMemory(secreto);
            }
        }
    }

    private static void ValidarClaveYNonce(byte[] clave, byte[] nonce)
    {
        ArgumentNullException.ThrowIfNull(clave);
        ArgumentNullException.ThrowIfNull(nonce);
        if (clave.Length != TamClave)
        {
            throw new ArgumentException($"La clave debe tener {TamClave} bytes", nameof(clave));
        }

        if (nonce.Length != TamNonce)
        {
            throw new ArgumentException($"El nonce debe tener {TamNonce} bytes", nameof(nonce));
        }
    }
}

/// <summary>Par de claves X25519. La privada nunca sale del dispositivo.</summary>
public sealed class ParDeClaves
{
    public ParDeClaves(byte[] privada, byte[] publica)
    {
        ArgumentNullException.ThrowIfNull(privada);
        ArgumentNullException.ThrowIfNull(publica);
        if (privada.Length != Cripto.TamClave)
        {
            throw new ArgumentException("Clave privada de tamaño incorrecto", nameof(privada));
        }

        if (publica.Length != Cripto.TamClave)
        {
            throw new ArgumentException("Clave pública de tamaño incorrecto", nameof(publica));
        }

        Privada = privada;
        Publica = publica;
    }

    public byte[] Privada { get; }

    public byte[] Publica { get; }

    /// <summary>No se imprime la privada ni por accidente en un log.</summary>
    public override string ToString() => $"ParDeClaves(publica={Hex.ToHex(Publica)})";
}

public sealed class ClaveInvalidaException : Exception
{
    public ClaveInvalidaException(string mensaje, Exception? causa = null) : base(mensaje, causa) { }
}

public sealed class AutenticacionFallidaException : Exception
{
    public AutenticacionFallidaException(string mensaje, Exception? causa = null) : base(mensaje, causa) { }
}
