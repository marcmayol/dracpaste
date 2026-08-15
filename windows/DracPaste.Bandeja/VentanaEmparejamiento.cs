using DracPaste.Protocolo.Sesion;
using QRCoder;

namespace DracPaste.Bandeja;

/// <summary>
/// La ventana de emparejamiento: un QR grande, la huella y el texto por si el escáner
/// falla.
///
/// El QR no es un lujo: el JSON lleva una clave pública de 32 bytes y un token de 16, y
/// copiarlos a mano es tedioso y fácil de estropear. Pero el texto sigue estando debajo,
/// porque una cámara sucia o una pantalla con brillo bajo no pueden dejar al usuario sin
/// poder emparejar.
/// </summary>
internal sealed class VentanaEmparejamiento : Form
{
    private readonly System.Windows.Forms.Timer _cuentaAtras;
    private readonly Label _caducidad;
    private DateTime _caducaEn;

    /// <param name="huella">
    /// La huella del par, si ya se conoce. Al abrir la ventana todavía no: depende de la
    /// clave pública del móvil, que llega al emparejar. En ese caso se explica que
    /// aparecerá después, en vez de enseñar un hueco.
    /// </param>
    public VentanaEmparejamiento(DatosQr qr, string? huella = null)
    {
        Text = "DracPaste · emparejar un móvil";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(420, 620);
        BackColor = Color.White;

        var explicacion = new Label
        {
            Text = $"""
                    Abre DracPaste en el móvil, pulsa «Emparejar un PC»
                    y apunta con la cámara a este código.

                    Este PC: {qr.Name} · {qr.Ip}:{qr.Port}
                    """,
            Dock = DockStyle.Top,
            // 88 y no 72: con cuatro líneas, la última quedaba cortada por la mitad.
            Height = 88,
            Padding = new Padding(16, 12, 16, 0),
            TextAlign = ContentAlignment.TopLeft,
        };

        var imagenQr = new PictureBox
        {
            Image = GenerarQr(qr.ASerializar()),
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Top,
            Height = 320,
            BackColor = Color.White,
        };

        var hayHuella = !string.IsNullOrWhiteSpace(huella);

        var huellaEtiqueta = new Label
        {
            Text = hayHuella ? $"Huella: {huella}" : "Comprueba la huella al terminar",
            Dock = DockStyle.Top,
            Height = 32,
            Font = hayHuella
                ? new Font(FontFamily.GenericMonospace, 12f, FontStyle.Bold)
                : new Font(FontFamily.GenericSansSerif, 9f, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleCenter,
        };

        var explicacionHuella = new Label
        {
            Text = hayHuella
                ? "El móvil debe mostrar esta misma huella al terminar."
                : "Al terminar aparecerá aquí y en el móvil: deben coincidir.",
            Dock = DockStyle.Top,
            // 28 y no 24: a 420 px de ancho, este texto se iba a dos líneas y la segunda
            // se comía la cuenta atrás de debajo.
            Height = 28,
            TextAlign = ContentAlignment.MiddleCenter,
            ForeColor = Color.DimGray,
        };

        _caducaEn = DateTime.UtcNow.AddMilliseconds(Protocolo.Protocolo.ValidezTokenMs);
        _caducidad = new Label
        {
            Dock = DockStyle.Top,
            Height = 24,
            TextAlign = ContentAlignment.MiddleCenter,
            ForeColor = Color.DimGray,
        };

        var textoAlternativo = new TextBox
        {
            Text = qr.ASerializar(),
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            Font = new Font(FontFamily.GenericMonospace, 8f),
            BackColor = Color.WhiteSmoke,
            BorderStyle = BorderStyle.FixedSingle,
            // Sin esto, el texto aparece resaltado en azul al abrirse la ventana, como si
            // el usuario ya lo hubiera seleccionado.
            TabStop = false,
            WordWrap = true,
        };

        var botones = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = 52,
            Padding = new Padding(12, 10, 12, 10),
        };

        var cerrar = new Button { Text = "Cerrar", DialogResult = DialogResult.OK, AutoSize = true };
        var copiar = new Button { Text = "Copiar el texto", AutoSize = true };
        copiar.Click += (_, _) =>
        {
            Clipboard.SetText(textoAlternativo.Text);
            copiar.Text = "Copiado";
        };

        botones.Controls.Add(cerrar);
        botones.Controls.Add(copiar);

        var marcoTexto = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16, 4, 16, 8) };
        marcoTexto.Controls.Add(textoAlternativo);

        var pieTexto = new Label
        {
            Text = "Si la cámara no lo lee, pega este texto en el móvil:",
            Dock = DockStyle.Top,
            Height = 22,
            Padding = new Padding(16, 0, 0, 0),
            ForeColor = Color.DimGray,
        };

        Controls.Add(marcoTexto);
        Controls.Add(pieTexto);
        Controls.Add(_caducidad);
        Controls.Add(explicacionHuella);
        Controls.Add(huellaEtiqueta);
        Controls.Add(imagenQr);
        Controls.Add(explicacion);
        Controls.Add(botones);

        AcceptButton = cerrar;
        CancelButton = cerrar;

        _cuentaAtras = new System.Windows.Forms.Timer { Interval = 1000 };
        _cuentaAtras.Tick += (_, _) => ActualizarCaducidad();
        _cuentaAtras.Start();
        ActualizarCaducidad();
    }

    /// <summary>Se dispara cuando el token caduca y la ventana deja de valer.</summary>
    public event Action? Caducado;

    private void ActualizarCaducidad()
    {
        var quedan = _caducaEn - DateTime.UtcNow;

        if (quedan <= TimeSpan.Zero)
        {
            _cuentaAtras.Stop();
            _caducidad.Text = "El código ha caducado. Cierra y vuelve a abrir esta ventana.";
            _caducidad.ForeColor = Color.Firebrick;
            Caducado?.Invoke();
            return;
        }

        _caducidad.Text = $"Caduca en {quedan.Minutes}:{quedan.Seconds:D2}";
    }

    /// <summary>
    /// El QR con corrección de errores media: el JSON ronda los 250 caracteres, y subir
    /// a alta haría el código tan denso que costaría leerlo desde medio metro.
    /// </summary>
    private static Bitmap GenerarQr(string contenido)
    {
        using var generador = new QRCodeGenerator();
        using var datos = generador.CreateQrCode(contenido, QRCodeGenerator.ECCLevel.M);
        using var qr = new PngByteQRCode(datos);
        using var flujo = new MemoryStream(qr.GetGraphic(20));
        return new Bitmap(flujo);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _cuentaAtras.Dispose();
        }

        base.Dispose(disposing);
    }
}
