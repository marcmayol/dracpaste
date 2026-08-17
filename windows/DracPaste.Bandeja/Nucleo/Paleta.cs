namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Los colores de DracPaste en el PC: **papel y tinta con terracota**.
///
/// Solo el tema claro, y a propósito. WinForms no sigue el modo oscuro de Windows, y
/// hacerlo a mano obliga a repintar cada control (<c>owner-draw</c>): mucho trabajo, frágil
/// con los escalados de DPI, y con un resultado que nunca acaba de parecerse a una
/// aplicación del sistema. Más vale un claro bien hecho.
///
/// El terracota **solo marca acciones**. Un estado nunca se dice con color: el icono de la
/// bandeja es monocromo y a 16 px el color no existe.
/// </summary>
internal static class Paleta
{
    /// <summary>Texto principal y bordes marcados.</summary>
    public static readonly Color Tinta = Color.FromArgb(0x1A, 0x1A, 0x1A);

    /// <summary>Fondo de las ventanas.</summary>
    public static readonly Color Papel = Color.FromArgb(0xFA, 0xF8, 0xF4);

    /// <summary>Fondo de las zonas que van encima del papel.</summary>
    public static readonly Color Tarjeta = Color.White;

    /// <summary>Texto secundario. Sobre papel da 6,1:1, que cumple AA de sobra.</summary>
    public static readonly Color Apagado = Color.FromArgb(0x5C, 0x55, 0x4E);

    /// <summary>Acciones: enviar, escanear, copiar. Nunca estado.</summary>
    public static readonly Color Acento = Color.FromArgb(0xC2, 0x52, 0x1E);

    /// <summary>Lo destructivo y lo que hay que arreglar: firewall, caducidad.</summary>
    public static readonly Color Peligro = Color.FromArgb(0x8C, 0x2F, 0x10);

    /// <summary>Fondo de los avisos de peligro, para que no griten.</summary>
    public static readonly Color PeligroSuave = Color.FromArgb(0xFA, 0xF1, 0xEC);

    /// <summary>Separadores y bordes pasivos.</summary>
    public static readonly Color Linea = Color.FromArgb(0xDD, 0xD6, 0xCD);

    /// <summary>Fondo de la caja del texto de emparejar.</summary>
    public static readonly Color Caja = Color.FromArgb(0xF7, 0xF7, 0xF7);
}

/// <summary>
/// Las fuentes del PC: Segoe UI para todo, y **Consolas para cualquier huella**.
///
/// Lo de la huella es una regla dura, no una preferencia: dos huellas se comparan
/// carácter a carácter mirando dos pantallas a la vez, y con una tipografía de anchura
/// variable la O y el 0, o el 1 y la l, se parecen lo justo para que alguien dé por buena
/// una que no lo es.
/// </summary>
internal static class Tipos
{
    public static Font Normal(float tamano = 9f) => new("Segoe UI", tamano);

    public static Font Fuerte(float tamano = 9f) => new("Segoe UI", tamano, FontStyle.Bold);

    public static Font Huella(float tamano = 12f) => new("Consolas", tamano, FontStyle.Bold);

    /// <summary>Para el JSON del plan B, que no se lee: se copia.</summary>
    public static Font Codigo(float tamano = 8f) => new("Consolas", tamano);
}
