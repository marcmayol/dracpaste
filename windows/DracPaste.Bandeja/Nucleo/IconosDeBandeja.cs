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
        // Un icono con varios tamaños dentro, como cualquier .ico del sistema: Windows
        // coge el que necesita en cada sitio en vez de reescalar uno solo.
        return IconoCon(new[] { 16, 20, 24, 32, 48 }, lado => Pintar(estado, barraOscura, lado));
    }

    private static Bitmap Pintar(EstadoBandeja estado, bool barraOscura, int lado)
    {
        // Sobre barra oscura la silueta va en crema; sobre barra clara, en tinta. El
        // dibujo es plano, así que recolorearlo no le quita nada.
        var silueta = barraOscura ? Color.FromArgb(0xEC, 0xE5, 0xDC) : Color.FromArgb(0x1A, 0x1A, 0x1A);
        var contraria = barraOscura ? Color.FromArgb(0x1A, 0x1A, 0x1A) : Color.White;

        var lienzo = new Bitmap(lado, lado, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(lienzo))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;

            using var original = Base.ToBitmap();

            // La silueta va siempre sólida. Antes los estados «sin móvil» y «sin
            // emparejar» se dibujaban al 45 %, y a 16 px eso no era un dragón atenuado:
            // era un dragón que no estaba. Lo que distingue los estados es la marca de la
            // esquina, que es forma y sobrevive a cualquier tamaño.
            Recolorear(g, original, lado, silueta, 1f);

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

        return lienzo;
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
    /// <summary>
    /// Monta un icono de verdad, con sus tamaños dentro, escribiendo el formato a mano.
    ///
    /// La vía corta —<c>Bitmap.GetHicon()</c> + <c>Icon.FromHandle</c>— no sirve aquí, y
    /// costó verlo: produce un icono que se pinta bien en una ventana, pero que al pasar
    /// por el guardado que hace el sistema pierde el canal alfa. En la bandeja eso se
    /// traduce en un icono roto o en un dragón que sencillamente no está, sin ningún
    /// error por ninguna parte. Escribiendo el fichero se controla el alfa de cada píxel.
    /// </summary>
    private static Icon IconoCon(int[] tamanos, Func<int, Bitmap> pintar)
    {
        var imagenes = tamanos.Select(lado =>
        {
            using var mapa = pintar(lado);
            return (Lado: lado, Datos: ComoDib(mapa));
        }).ToList();

        using var flujo = new MemoryStream();
        using (var w = new BinaryWriter(flujo, System.Text.Encoding.UTF8, leaveOpen: true))
        {
            w.Write((ushort)0);                 // reservado
            w.Write((ushort)1);                 // tipo: icono
            w.Write((ushort)imagenes.Count);

            var desplazamiento = 6 + 16 * imagenes.Count;
            foreach (var (lado, datos) in imagenes)
            {
                w.Write((byte)lado);
                w.Write((byte)lado);
                w.Write((byte)0);               // colores de paleta
                w.Write((byte)0);               // reservado
                w.Write((ushort)1);             // planos
                w.Write((ushort)32);            // bits por píxel
                w.Write((uint)datos.Length);
                w.Write((uint)desplazamiento);
                desplazamiento += datos.Length;
            }

            foreach (var (_, datos) in imagenes)
            {
                w.Write(datos);
            }
        }

        flujo.Position = 0;
        return new Icon(flujo);
    }

    /// <summary>
    /// Un mapa de bits en el formato que guarda un .ico: cabecera, píxeles BGRA de abajo
    /// arriba y una máscara AND que con 32 bits no manda, pero tiene que estar.
    /// </summary>
    private static byte[] ComoDib(Bitmap mapa)
    {
        var lado = mapa.Width;
        using var flujo = new MemoryStream();
        using var w = new BinaryWriter(flujo);

        w.Write(40);                            // tamaño de la cabecera
        w.Write(lado);
        w.Write(lado * 2);                      // alto doblado: píxeles + máscara
        w.Write((ushort)1);
        w.Write((ushort)32);
        w.Write(0);
        w.Write(lado * lado * 4);
        w.Write(0);
        w.Write(0);
        w.Write(0);
        w.Write(0);

        for (var y = lado - 1; y >= 0; y--)
        {
            for (var x = 0; x < lado; x++)
            {
                var c = mapa.GetPixel(x, y);
                w.Write(c.B);
                w.Write(c.G);
                w.Write(c.R);
                w.Write(c.A);
            }
        }

        var bytesPorFila = (lado + 31) / 32 * 4;
        var fila = new byte[bytesPorFila];
        for (var y = 0; y < lado; y++)
        {
            w.Write(fila);
        }

        w.Flush();
        return flujo.ToArray();
    }
}
