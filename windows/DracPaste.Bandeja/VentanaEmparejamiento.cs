using DracPaste.Bandeja.Nucleo;
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
    private readonly ProgressBar _barra;
    private readonly PictureBox _imagenQr;
    private readonly TextBox _textoAlternativo;

    /// <summary>
    /// Cómo pedir un código nuevo cuando el actual caduca. Sin esto la ventana solo sabe
    /// avisar de que ha caducado, que es lo que hacía antes.
    /// </summary>
    private readonly Func<DatosQr>? _renovar;

    private DateTime _caducaEn;

    /// <param name="huella">
    /// La huella del par, si ya se conoce. Al abrir la ventana todavía no: depende de la
    /// clave pública del móvil, que llega al emparejar. En ese caso se explica que
    /// aparecerá después, en vez de enseñar un hueco.
    /// </param>
    /// <param name="renovar">
    /// Emite un código nuevo. Se llama sola al caducar el anterior: dos minutos dan para
    /// poco —coger el móvil, abrir la app, dar permiso a la cámara y apuntar—, y mandar a
    /// cerrar y reabrir la ventana era hacerle perder el tiempo a quien ya iba tarde.
    /// </param>
    public VentanaEmparejamiento(DatosQr qr, string? huella = null, Func<DatosQr>? renovar = null)
    {
        _renovar = renovar;

        Text = "DracPaste · emparejar un móvil";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.White;

        // La ventana crece con lo que se le añade en vez de repartir 620 px fijos entre
        // más cosas: la primera versión de esto dejó la caja del texto alternativo sin
        // sitio y los botones montados sobre su propia etiqueta.
        var cortafuegos = PanelDeCortafuegos();
        ClientSize = new Size(420, 626 + (cortafuegos.Visible ? cortafuegos.Height : 0));

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

        _imagenQr = new PictureBox
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

        // Una barra que se vacía se ve sin leer; la cuenta atrás en texto, en gris y en
        // mitad de la ventana, no la miraba nadie.
        _barra = new ProgressBar
        {
            Dock = DockStyle.Top,
            Height = 6,
            Style = ProgressBarStyle.Continuous,
            Maximum = Protocolo.Protocolo.ValidezTokenMs,
            Value = Protocolo.Protocolo.ValidezTokenMs,
        };

        _textoAlternativo = new TextBox
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
            Clipboard.SetText(_textoAlternativo.Text);
            copiar.Text = "Copiado";
        };

        botones.Controls.Add(cerrar);
        botones.Controls.Add(copiar);

        var marcoTexto = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16, 4, 16, 8) };
        marcoTexto.Controls.Add(_textoAlternativo);

        var pieTexto = new Label
        {
            Text = "Si la cámara no lo lee, pega este texto en el móvil:",
            Dock = DockStyle.Top,
            Height = 22,
            Padding = new Padding(16, 0, 0, 0),
            ForeColor = Color.DimGray,
        };

        // Con Dock.Top, el último que se añade es el que queda más arriba.
        Controls.Add(marcoTexto);
        Controls.Add(pieTexto);
        Controls.Add(_barra);
        Controls.Add(_caducidad);
        Controls.Add(explicacionHuella);
        Controls.Add(huellaEtiqueta);
        Controls.Add(_imagenQr);
        Controls.Add(explicacion);
        Controls.Add(cortafuegos);
        Controls.Add(botones);

        AcceptButton = cerrar;
        CancelButton = cerrar;

        _cuentaAtras = new System.Windows.Forms.Timer { Interval = 1000 };
        _cuentaAtras.Tick += (_, _) => ActualizarCaducidad();
        _cuentaAtras.Start();
        ActualizarCaducidad();
    }

    /// <summary>Se dispara cuando el token caduca y no se ha podido renovar.</summary>
    public event Action? Caducado;

    /// <summary>
    /// El aviso de que el firewall va a bloquear al móvil, dentro de la ventana.
    ///
    /// Antes esto solo era un globo de la bandeja, y un globo se lo come cualquiera. Sin
    /// la regla, el móvil ve el PC pero la conexión muere en un tiempo de espera, y desde
    /// el móvil eso parece un problema de red: quien empareja se pone a mirar el WiFi.
    /// Aquí está justo donde se va a intentar el emparejamiento.
    /// </summary>
    private Panel PanelDeCortafuegos()
    {
        var panel = new Panel
        {
            Dock = DockStyle.Top,
            Height = 74,
            BackColor = Color.FromArgb(250, 241, 236),
            Padding = new Padding(16, 8, 16, 8),
            // null = no se ha podido averiguar; callar es mejor que una alarma falsa.
            Visible = Cortafuegos.HayReglaDeEntrada() == false,
        };

        var boton = new Button
        {
            Text = "Permitir en el firewall de Windows…",
            Dock = DockStyle.Bottom,
            Height = 26,
            AutoSize = false,
        };

        var texto = new Label
        {
            Text = "El firewall va a bloquear al móvil: verá este PC, pero la conexión morirá "
                   + "en un tiempo de espera.",
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(140, 47, 16),
            Font = new Font(FontFamily.GenericSansSerif, 8.5f, FontStyle.Bold),
        };

        boton.Click += (_, _) =>
        {
            if (Cortafuegos.CrearReglas())
            {
                panel.Visible = false;
                return;
            }

            MessageBox.Show(
                "No se han podido crear las reglas. Hace falta aceptar el aviso de "
                + "administrador de Windows.",
                "DracPaste",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        };

        panel.Controls.Add(texto);
        panel.Controls.Add(boton);
        return panel;
    }

    private void ActualizarCaducidad()
    {
        var quedan = _caducaEn - DateTime.UtcNow;

        if (quedan <= TimeSpan.Zero)
        {
            Renovar();
            return;
        }

        _caducidad.Text = $"El código caduca en {quedan.Minutes}:{quedan.Seconds:D2} · se renueva solo";
        _caducidad.ForeColor = Color.DimGray;

        // El valor se recorta al máximo: entre el tick de un segundo y el reloj real hay
        // milisegundos de diferencia, y pasarse hace que ProgressBar lance.
        _barra.Value = Math.Clamp((int)quedan.TotalMilliseconds, 0, _barra.Maximum);
    }

    /// <summary>
    /// Pide un código nuevo y reinicia la cuenta atrás sin cerrar la ventana.
    /// </summary>
    private void Renovar()
    {
        var nuevo = _renovar?.Invoke();

        if (nuevo is null)
        {
            _cuentaAtras.Stop();
            _barra.Value = 0;
            _caducidad.Text = "El código ha caducado. Cierra y vuelve a abrir esta ventana.";
            _caducidad.ForeColor = Color.Firebrick;
            Caducado?.Invoke();
            return;
        }

        var serializado = nuevo.ASerializar();
        var anterior = _imagenQr.Image;
        _imagenQr.Image = GenerarQr(serializado);
        anterior?.Dispose();
        _textoAlternativo.Text = serializado;

        _caducaEn = DateTime.UtcNow.AddMilliseconds(Protocolo.Protocolo.ValidezTokenMs);
        _barra.Value = _barra.Maximum;
        ActualizarCaducidad();
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
