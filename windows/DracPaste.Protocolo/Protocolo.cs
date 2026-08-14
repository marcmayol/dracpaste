namespace DracPaste.Protocolo;

/// <summary>
/// Constantes del protocolo DracPaste v1.
///
/// Todo lo que hay aquí está fijado en <c>docs/protocol.md</c>. Si un valor cambia,
/// cambia primero el documento y después este fichero y su equivalente en Kotlin
/// (<c>Protocolo.kt</c>), en el mismo cambio.
/// </summary>
public static class Protocolo
{
    /// <summary>Versión del protocolo que habla esta implementación.</summary>
    public const int Version = 1;

    /// <summary>Tipo de servicio mDNS que publica el PC y busca el móvil.</summary>
    public const string ServicioMdns = "_dracpaste._tcp";

    /// <summary>Puerto preferido. Si está ocupado, se toma otro y se anuncia por mDNS.</summary>
    public const int PuertoPreferido = 47653;

    /// <summary>Máximo de un frame completo. Un length mayor es error de protocolo.</summary>
    public const int MaxFrameBytes = 1024 * 1024;

    /// <summary>Máximo del texto de un clip antes de codificar.</summary>
    public const int MaxClipBytes = 256 * 1024;

    /// <summary>Cada cuánto se envía un PING.</summary>
    public const int IntervaloPingMs = 15_000;

    /// <summary>Sin PONG en este plazo, la conexión se da por muerta.</summary>
    public const int TimeoutPongMs = 10_000;

    /// <summary>Un handshake que no termina en este plazo se aborta.</summary>
    public const int TimeoutHandshakeMs = 10_000;

    /// <summary>Cuánto vale el token del QR de emparejamiento.</summary>
    public const int ValidezTokenMs = 120_000;

    /// <summary>Ventana en la que un clip recién recibido no se reenvía (anti-eco).</summary>
    public const int VentanaAntiEcoMs = 5_000;

    /// <summary>Varias apps disparan más de un WM_CLIPBOARDUPDATE por una sola copia.</summary>
    public const int DebounceClipboardMs = 100;

    /// <summary>Único tipo de contenido que v1 sabe transportar.</summary>
    public const string TipoTexto = "text/plain";

    /// <summary>Formato de portapapeles propio que marca lo que escribe esta app.</summary>
    public const string FormatoMarcaPropia = "DracPasteOrigin";

    /// <summary>Claves de los registros TXT del anuncio mDNS.</summary>
    public const string TxtVersion = "v";

    /// <inheritdoc cref="TxtVersion"/>
    public const string TxtId = "id";

    /// <inheritdoc cref="TxtVersion"/>
    public const string TxtNombre = "name";
}
