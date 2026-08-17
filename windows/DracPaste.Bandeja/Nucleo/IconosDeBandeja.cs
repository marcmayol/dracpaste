using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>Lo que el icono de la bandeja tiene que contar de un vistazo.</summary>
internal enum EstadoBandeja
{
    /// <summary>Hay un móvil conectado: el portapapeles está compartido.</summary>
    Conectado,

    /// <summary>Hay móviles emparejados pero ninguno a la escucha ahora mismo.</summary>
    SinMovil,

    /// <summary>Todavía no se ha emparejado nada.</summary>
    SinEmparejar,

    /// <summary>El usuario ha pausado la sincronización.</summary>
    Pausa,
}

/// <summary>
/// Las variantes del icono de la bandeja, una por estado.
///
/// Se derivan del icono base en tiempo de ejecución en lugar de guardar cuatro
/// <c>.ico</c> ya dibujados. El diseño pide lo segundo y tiene razón —a 16 px, un icono
/// dibujado a mano gana siempre—, pero la cabeza definitiva todavía no está vectorizada:
/// derivarlas aquí evita tener que rehacer cuatro ficheros cuando llegue, y el día que
/// llegue basta con sustituir esta clase por la carga de los <c>.ico</c>.
///
/// La regla que sí se respeta es la que importa: **los estados se distinguen por forma**,
/// no por color ni por opacidad a secas. A 16 píxeles y en monocromo, un icono al 38 % y
/// uno normal se parecen demasiado en una pantalla con poco brillo.
/// </summary>
internal static class IconosDeBandeja
{
    private static readonly Dictionary<(EstadoBandeja, bool), Icon> Cache = new();
    private static Icon? _base;

    /// <summary>
    /// ¿La barra de tareas es oscura? Windows 11 la pone así de fábrica.
    ///
    /// Importa porque la cabeza de Ladón está dibujada en negro: sobre una barra oscura
    /// desaparece del todo, y el usuario se queda sin saber si la app está corriendo. La
    /// clave del registro es de solo lectura y no hace falta ningún permiso especial.
    /// </summary>
    private static bool BarraOscura()
    {
        try
        {
            using var clave = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");

            // 1 = barra clara. Si la clave no está, Windows la trata como clara.
            return clave?.GetValue("SystemUsesLightTheme") is int valor && valor == 0;
        }
        catch (Exception)
        {
            return false;
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);

    /// <summary>El icono tal cual está en disco, sin variantes.</summary>
    public static Icon Base
    {
        get
        {
            if (_base is not null)
            {
                return _base;
            }

            var ruta = Path.Combine(AppContext.BaseDirectory, "Recursos", "dracpaste.ico");
            _base = File.Exists(ruta) ? new Icon(ruta) : SystemIcons.Application;
            return _base;
        }
    }

    public static Icon Para(EstadoBandeja estado)
    {
        var oscura = BarraOscura();

        if (Cache.TryGetValue((estado, oscura), out var guardado))
        {
            return guardado;
        }

        var icono = Dibujar(estado, oscura);
        Cache[(estado, oscura)] = icono;
        return icono;
    }

    private static Icon Dibujar(EstadoBandeja estado, bool barraOscura)
    {
        // 32 y no 16: Windows escoge el tamaño que necesita, y partir de uno grande deja
        // mejor resultado al reducir que dibujar directamente en 16.
        const int lado = 32;

        // Sobre barra oscura la silueta va en crema; sobre barra clara, en tinta. El
        // dibujo es plano, así que recolorearlo no le quita nada.
        var silueta = barraOscura ? Color.FromArgb(0xEC, 0xE5, 0xDC) : Color.FromArgb(0x1A, 0x1A, 0x1A);
        var contraria = barraOscura ? Color.FromArgb(0x1A, 0x1A, 0x1A) : Color.White;

        using var lienzo = new Bitmap(lado, lado, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(lienzo))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;

            using var original = Base.ToBitmap();
            Recolorear(
                g,
                original,
                lado,
                silueta,
                estado is EstadoBandeja.Conectado or EstadoBandeja.Pausa ? 1f : 0.45f);

            switch (estado)
            {
                case EstadoBandeja.SinEmparejar:
                    // Una interrogación no cabe legible; un hueco redondo sí, y dice
                    // «falta algo» sin depender de leer nada.
                    DibujarAnillo(g, lado, silueta, contraria);
                    break;

                case EstadoBandeja.SinMovil:
                    DibujarAspa(g, lado, silueta, contraria);
                    break;

                case EstadoBandeja.Pausa:
                    DibujarPausa(g, lado, silueta, contraria);
                    break;
            }
        }

        return DesdeMapa(lienzo);
    }

    /// <summary>
    /// Pinta la silueta del color indicado, conservando el canal alfa y aplicando la
    /// opacidad que toque.
    /// </summary>
    private static void Recolorear(Graphics g, Image original, int lado, Color color, float opacidad)
    {
        var matriz = new ColorMatrix
        {
            // Los tres canales de color se anulan y se sustituyen por el color pedido:
            // lo único que sobrevive del original es la forma.
            Matrix00 = 0,
            Matrix11 = 0,
            Matrix22 = 0,
            Matrix33 = opacidad,
            Matrix40 = color.R / 255f,
            Matrix41 = color.G / 255f,
            Matrix42 = color.B / 255f,
        };

        using var atributos = new ImageAttributes();
        atributos.SetColorMatrix(matriz);

        g.DrawImage(
            original,
            new Rectangle(0, 0, lado, lado),
            0,
            0,
            original.Width,
            original.Height,
            GraphicsUnit.Pixel,
            atributos);
    }

    /// <summary>Un aro hueco en la esquina: hay PC, pero nadie escuchando.</summary>
    private static void DibujarAnillo(Graphics g, int lado, Color trazoColor, Color fondoColor)
    {
        var caja = Esquina(lado);
        using var fondo = new SolidBrush(fondoColor);
        using var trazo = new Pen(trazoColor, lado / 12f);
        g.FillEllipse(fondo, caja);
        g.DrawEllipse(trazo, caja);
    }

    private static void DibujarAspa(Graphics g, int lado, Color trazoColor, Color fondoColor)
    {
        var caja = Esquina(lado);
        using var fondo = new SolidBrush(fondoColor);
        g.FillEllipse(fondo, caja);

        using var trazo = new Pen(trazoColor, lado / 10f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        var margen = caja.Width * 0.28f;
        g.DrawLine(trazo, caja.Left + margen, caja.Top + margen, caja.Right - margen, caja.Bottom - margen);
        g.DrawLine(trazo, caja.Right - margen, caja.Top + margen, caja.Left + margen, caja.Bottom - margen);
    }

    private static void DibujarPausa(Graphics g, int lado, Color trazoColor, Color fondoColor)
    {
        var caja = Esquina(lado);
        using var fondo = new SolidBrush(fondoColor);
        using var trazo = new Pen(trazoColor, lado / 16f);
        g.FillRectangle(fondo, caja);
        g.DrawRectangle(trazo, caja.X, caja.Y, caja.Width, caja.Height);

        using var barras = new SolidBrush(trazoColor);
        var ancho = caja.Width / 5f;
        var alto = caja.Height * 0.5f;
        var y = caja.Top + (caja.Height - alto) / 2f;
        g.FillRectangle(barras, caja.Left + caja.Width * 0.28f, y, ancho, alto);
        g.FillRectangle(barras, caja.Right - caja.Width * 0.28f - ancho, y, ancho, alto);
    }

    /// <summary>
    /// La marca ocupa poco más de un tercio del icono y se pega abajo a la derecha, que
    /// es donde el sistema no la solapa con nada.
    /// </summary>
    private static RectangleF Esquina(int lado)
    {
        var tamano = lado * 0.46f;
        return new RectangleF(lado - tamano - 1, lado - tamano - 1, tamano, tamano);
    }

    /// <summary>
    /// <see cref="Bitmap.GetHicon"/> reserva un handle del sistema que hay que liberar a
    /// mano; se copia el icono y se suelta enseguida para no ir dejando handles sueltos.
    /// </summary>
    private static Icon DesdeMapa(Bitmap mapa)
    {
        var handle = mapa.GetHicon();
        try
        {
            using var temporal = Icon.FromHandle(handle);
            return (Icon)temporal.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }
}
