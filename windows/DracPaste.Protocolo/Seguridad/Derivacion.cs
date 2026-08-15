using System.Text;

namespace DracPaste.Protocolo.Seguridad;

/// <summary>
/// Derivación de claves del protocolo (<c>docs/protocol.md</c> §2.2, §2.3 y §2.6).
///
/// Todo lo de aquí es determinista: con las mismas entradas, el PC y el móvil obtienen
/// exactamente los mismos bytes. Es lo que comprueban los vectores de §7 sin necesidad
/// de conectar los dos dispositivos.
/// </summary>
public static class Derivacion
{
    private static readonly byte[] SaltPar = Encoding.ASCII.GetBytes("DracPaste/v1/pair");
    private static readonly byte[] InfoM2P = Encoding.ASCII.GetBytes("DracPaste/v1/m2p");
    private static readonly byte[] InfoP2M = Encoding.ASCII.GetBytes("DracPaste/v1/p2m");
    private static readonly byte[] PrefijoRetosEmparejamiento = Encoding.ASCII.GetBytes("DracPaste/v1/pairing");

    /// <summary>
    /// Clave de par: se calcula una vez al emparejar y se puede recalcular siempre desde
    /// las claves guardadas. Nunca cifra un mensaje; solo es el material del que salen
    /// las claves de sesión.
    ///
    /// Las dos públicas se ordenan byte a byte para que los dos lados lleguen al mismo
    /// resultado sin depender de quién es cliente y quién servidor.
    /// </summary>
    public static byte[] ClavePar(byte[] privadaPropia, byte[] publicaDelOtro)
    {
        var publicaPropia = Cripto.ClavePublicaDe(privadaPropia);
        return Cripto.AcordarYDerivar(
            privadaPropia,
            publicaDelOtro,
            SaltPar,
            Ordenar(publicaPropia, publicaDelOtro));
    }

    /// <summary>
    /// Claves de sesión, una por dirección, derivadas de los dos retos que se
    /// intercambian al conectar.
    ///
    /// Se derivan en cada conexión a propósito. Con ChaCha20-Poly1305, repetir el par
    /// (clave, nonce) rompe la confidencialidad; si se cifrara con la clave de par, cada
    /// reconexión —y hay muchas: cambios de red, suspensión, Doze— reiniciaría el
    /// contador y reutilizaría combinaciones ya usadas.
    /// </summary>
    public static ClavesDeSesion ClavesDeSesion(byte[] clavePar, byte[] retoMovil, byte[] retoPc)
    {
        ArgumentNullException.ThrowIfNull(retoMovil);
        ArgumentNullException.ThrowIfNull(retoPc);
        if (retoMovil.Length != Cripto.TamReto)
        {
            throw new ArgumentException($"El reto del móvil debe tener {Cripto.TamReto} bytes", nameof(retoMovil));
        }

        if (retoPc.Length != Cripto.TamReto)
        {
            throw new ArgumentException($"El reto del PC debe tener {Cripto.TamReto} bytes", nameof(retoPc));
        }

        var salt = Concatenar(retoMovil, retoPc);
        return new ClavesDeSesion(
            movilAPc: Cripto.Hkdf(clavePar, salt, InfoM2P),
            pcAMovil: Cripto.Hkdf(clavePar, salt, InfoP2M));
    }

    /// <summary>
    /// Retos del emparejamiento. No se intercambian: los dos lados los derivan del token
    /// del QR, que ya es aleatorio y de un solo uso, así que el emparejamiento se ahorra
    /// una vuelta de mensajes (§3.2 paso 2).
    /// </summary>
    public static (byte[] Movil, byte[] Pc) RetosDeEmparejamiento(byte[] token)
    {
        var semilla = Cripto.Sha256(Concatenar(PrefijoRetosEmparejamiento, token));
        return (semilla[..16], semilla[16..32]);
    }

    /// <summary>
    /// Huella corta para que el usuario compare a ojo lo que ve en el móvil y en el PC.
    /// Los dos dispositivos de un par muestran siempre la misma.
    /// </summary>
    public static string Huella(byte[] publicaA, byte[] publicaB)
    {
        var resumen = Cripto.Sha256(Ordenar(publicaA, publicaB));
        var hex = Hex.ToHex(resumen.AsSpan(0, 4)).ToUpperInvariant();
        return $"{hex[..4]}-{hex[4..8]}";
    }

    /// <summary><c>pk_lo || pk_hi</c>, comparando byte a byte como enteros sin signo.</summary>
    private static byte[] Ordenar(byte[] a, byte[] b) =>
        Comparar(a, b) <= 0 ? Concatenar(a, b) : Concatenar(b, a);

    private static int Comparar(byte[] a, byte[] b)
    {
        var minimo = Math.Min(a.Length, b.Length);
        for (var i = 0; i < minimo; i++)
        {
            var diferencia = a[i] - b[i];
            if (diferencia != 0)
            {
                return diferencia;
            }
        }

        return a.Length - b.Length;
    }

    private static byte[] Concatenar(byte[] a, byte[] b)
    {
        var salida = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, salida, 0, a.Length);
        Buffer.BlockCopy(b, 0, salida, a.Length, b.Length);
        return salida;
    }
}

/// <summary>Las dos claves de una sesión. Cada dirección lleva la suya.</summary>
public sealed class ClavesDeSesion
{
    public ClavesDeSesion(byte[] movilAPc, byte[] pcAMovil)
    {
        MovilAPc = movilAPc;
        PcAMovil = pcAMovil;
    }

    public byte[] MovilAPc { get; }

    public byte[] PcAMovil { get; }

    /// <summary>Clave con la que cifra quien está en este extremo.</summary>
    public byte[] ParaEnviar(bool soyElMovil) => soyElMovil ? MovilAPc : PcAMovil;

    /// <summary>Clave con la que se descifra lo que llega.</summary>
    public byte[] ParaRecibir(bool soyElMovil) => soyElMovil ? PcAMovil : MovilAPc;

    public void Limpiar() => Cripto.Limpiar(MovilAPc, PcAMovil);

    public override string ToString() => "ClavesDeSesion(...)";
}
