// Escribe el .ico multirresolucion de DracPaste a partir de la cabeza de Ladon.
//
// Las imagenes van como DIB, no como PNG: el formato ICO admite PNG dentro desde Vista,
// pero GDI+ —que es quien lo lee en WinForms— los interpreta como DIB de todas formas y
// el resultado es ruido de colores en pantalla.
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

internal static class Ico
{
    private const string Origen = @"C:\Users\marcm\DracPaste\design-handoff\assets\drac-head.png";
    private const string Destino = @"C:\Users\marcm\DracPaste\windows\DracPaste.Bandeja\Recursos\dracpaste.ico";

    private static readonly int[] Tamanos = { 16, 24, 32, 48, 64, 128, 256 };

    public static void Generar()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Destino)!);

        using var original = Image.FromFile(Origen);
        var imagenes = Tamanos.Select(t => (Lado: t, Datos: ComoDib(Reducir(original, t)))).ToList();

        using var salida = new FileStream(Destino, FileMode.Create);
        using var w = new BinaryWriter(salida);

        w.Write((ushort)0);                    // reservado
        w.Write((ushort)1);                    // tipo: icono
        w.Write((ushort)imagenes.Count);

        var desplazamiento = 6 + 16 * imagenes.Count;
        foreach (var (lado, datos) in imagenes)
        {
            w.Write((byte)(lado >= 256 ? 0 : lado));
            w.Write((byte)(lado >= 256 ? 0 : lado));
            w.Write((byte)0);                  // colores de paleta
            w.Write((byte)0);                  // reservado
            w.Write((ushort)1);                // planos
            w.Write((ushort)32);               // bits por pixel
            w.Write((uint)datos.Length);
            w.Write((uint)desplazamiento);
            desplazamiento += datos.Length;
        }

        foreach (var (_, datos) in imagenes)
        {
            w.Write(datos);
        }

        w.Flush();
        Console.WriteLine($"  dracpaste.ico ({new FileInfo(Destino).Length / 1024} KB, {imagenes.Count} tamaños)");
    }

    private static Bitmap Reducir(Image original, int lado)
    {
        var mapa = new Bitmap(lado, lado, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(mapa);
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.Clear(Color.Transparent);

        // 0,94 dela caja: un margen minimo para que la silueta no toque el borde.
        var disponible = lado * 0.94f;
        var escala = Math.Min(disponible / original.Width, disponible / original.Height);
        var ancho = original.Width * escala;
        var alto = original.Height * escala;
        g.DrawImage(original, (lado - ancho) / 2, (lado - alto) / 2, ancho, alto);

        return mapa;
    }

    private static byte[] ComoDib(Bitmap mapa)
    {
        using var flujo = new MemoryStream();
        using var w = new BinaryWriter(flujo);
        var lado = mapa.Width;

        // BITMAPINFOHEADER. La altura va doblada porque detras van los pixeles y la
        // mascara AND, aunque con 32 bits mande el canal alfa.
        w.Write(40);
        w.Write(lado);
        w.Write(lado * 2);
        w.Write((ushort)1);
        w.Write((ushort)32);
        w.Write(0);
        w.Write(lado * lado * 4);
        w.Write(0);
        w.Write(0);
        w.Write(0);
        w.Write(0);

        // BGRA y de abajo arriba, que es como lo guarda el formato.
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

        // La mascara AND, a ceros. Cada fila se alinea a 4 bytes.
        var bytesPorFila = (lado + 31) / 32 * 4;
        var fila = new byte[bytesPorFila];
        for (var y = 0; y < lado; y++)
        {
            w.Write(fila);
        }

        w.Flush();
        mapa.Dispose();
        return flujo.ToArray();
    }
}
