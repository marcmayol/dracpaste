using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Seguridad;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja.Tests;

/// <summary>
/// Dibuja las ventanas a PNG para poder mirarlas.
///
/// Que una ventana compile no dice nada de cómo se ve: un panel mal acoplado deja
/// controles fuera del área visible, y un `Dock` en el orden equivocado tapa la mitad del
/// contenido. Nada de eso rompe un test corriente.
///
/// Las imágenes quedan en <c>bin/Debug/net8.0-windows/capturas/</c>. Los tests comprueban
/// además lo que sí se puede afirmar sin ojos: que la ventana se construye, que tiene el
/// tamaño esperado y que los controles caben dentro.
/// </summary>
[Trait("Category", "Interfaz")]
public class CapturaDeVentanasTests
{
    private static string CarpetaDeCapturas
    {
        get
        {
            var carpeta = Path.Combine(AppContext.BaseDirectory, "capturas");
            Directory.CreateDirectory(carpeta);
            return carpeta;
        }
    }

    [Fact]
    public void LaVentanaDeEmparejamientoSeDibujaEntera()
    {
        var qr = new DatosQr
        {
            Pk = Convert.ToBase64String(Cripto.GenerarParDeClaves().Publica),
            Ip = "192.168.1.40",
            Port = 47653,
            Token = Convert.ToBase64String(Cripto.Aleatorio(16)),
            Name = "PC-DESPACHO",
            DeviceId = Hex.ToHex(Cripto.Aleatorio(16)),
        };

        var (ancho, alto, controles) = EnHiloSta(() =>
        {
            // Sin huella: es como se abre de verdad, porque depende de la clave pública
            // del móvil y esa llega al emparejar.
            using var sinHuella = new VentanaEmparejamiento(qr);
            Capturar(sinHuella, "emparejamiento.png");

            using var conHuella = new VentanaEmparejamiento(qr, "A3F2-9C71");
            return Capturar(conHuella, "emparejamiento-con-huella.png");
        });

        Assert.Equal(420, ancho);
        Assert.Equal(620, alto);
        Assert.True(controles > 5, "La ventana debería tener el QR, la huella, el texto y los botones");
    }

    [Fact]
    public void LaVentanaDeAjustesSeDibujaEntera()
    {
        var carpeta = Path.Combine(Path.GetTempPath(), "dracpaste-capturas-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(carpeta);

        try
        {
            var identidad = Identidad.Generar("PC-DESPACHO");
            var registro = RegistroEmparejados.Cargar(carpeta);
            registro.Guardar(new DispositivoEmparejado
            {
                DeviceId = Hex.ToHex(Cripto.Aleatorio(16)),
                Nombre = "Pixel 8",
                PublicaBase64 = Convert.ToBase64String(Cripto.GenerarParDeClaves().Publica),
                Huella = "A3F2-9C71",
                EmparejadoEn = DateTimeOffset.Now,
            });
            registro.Guardar(new DispositivoEmparejado
            {
                DeviceId = Hex.ToHex(Cripto.Aleatorio(16)),
                Nombre = "Tablet del salón",
                PublicaBase64 = Convert.ToBase64String(Cripto.GenerarParDeClaves().Publica),
                Huella = "7B10-4EC2",
                EmparejadoEn = DateTimeOffset.Now.AddDays(-3),
            });

            var (ancho, alto, _) = EnHiloSta(() =>
            {
                var servidor = new ServidorDracPaste(identidad, registro, new GestorTokens());
                servidor.Arrancar(puertoPreferido: 0);
                try
                {
                    using var ventana = new VentanaAjustes(identidad, registro, servidor);
                    return Capturar(ventana, "ajustes.png");
                }
                finally
                {
                    // El analizador avisa de que bloquear sobre una tarea puede provocar
                    // un interbloqueo. Aquí no: esto corre en un hilo STA creado a mano,
                    // sin contexto de sincronización al que la continuación pueda querer
                    // volver. Y hace falta bloquear, porque el hilo tiene que seguir vivo
                    // hasta que el servidor termine de cerrarse.
#pragma warning disable xUnit1031
                    servidor.DisposeAsync().AsTask().GetAwaiter().GetResult();
#pragma warning restore xUnit1031
                }
            });

            Assert.Equal(560, ancho);
            Assert.Equal(420, alto);
        }
        finally
        {
            Directory.Delete(carpeta, recursive: true);
        }
    }

    /// <summary>
    /// Dibuja la ventana a un PNG. Se crea el handle sin mostrarla, para que ejecutar los
    /// tests no llene la pantalla de ventanas.
    /// </summary>
    private static (int Ancho, int Alto, int Controles) Capturar(Form ventana, string nombre)
    {
        // La ventana se coloca fuera de la pantalla y se muestra un instante: sin
        // mostrarla, los controles con Dock no se colocan y saldría un rectángulo vacío.
        ventana.StartPosition = FormStartPosition.Manual;
        ventana.Location = new Point(-10000, -10000);
        ventana.ShowInTaskbar = false;
        ventana.Show();
        Application.DoEvents();

        using var imagen = new Bitmap(ventana.Width, ventana.Height);
        ventana.DrawToBitmap(imagen, new Rectangle(0, 0, ventana.Width, ventana.Height));
        imagen.Save(Path.Combine(CarpetaDeCapturas, nombre), System.Drawing.Imaging.ImageFormat.Png);

        var resultado = (ventana.ClientSize.Width, ventana.ClientSize.Height, ContarControles(ventana));
        ventana.Hide();
        return resultado;
    }

    private static int ContarControles(Control raiz) =>
        raiz.Controls.Cast<Control>().Sum(hijo => 1 + ContarControles(hijo));

    private static T EnHiloSta<T>(Func<T> prueba)
    {
        T resultado = default!;
        Exception? fallo = null;

        var hilo = new Thread(() =>
        {
            try
            {
                resultado = prueba();
            }
            catch (Exception e)
            {
                fallo = e;
            }
        });

        hilo.SetApartmentState(ApartmentState.STA);
        hilo.Start();
        hilo.Join(TimeSpan.FromSeconds(60));

        if (fallo is not null)
        {
            throw fallo;
        }

        return resultado;
    }
}
