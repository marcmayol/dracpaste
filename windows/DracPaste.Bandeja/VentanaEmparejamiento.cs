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
    private readonly Panel _barra;
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

        // Primero lo que se hace, después la app: es como titula sus ventanas Windows, y
        // es lo que se lee cuando la barra de tareas recorta el título.
        Text = "Emparejar un móvil — DracPaste";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Paleta.Papel;
        ForeColor = Paleta.Tinta;
        Font = Tipos.Normal();
        Icon = IconosDeBandeja.Base;

        // La ventana crece con lo que se le añade en vez de repartir 620 px fijos entre
        // más cosas: la primera versión de esto dejó la caja del texto alternativo sin
        // sitio y los botones montados sobre su propia etiqueta.
        var cortafuegos = PanelDeCortafuegos();
        ClientSize = new Size(420, 626 + (cortafuegos.Visible ? cortafuegos.Height : 0));

        // El botón del móvil se llama «Escanear el código del PC». Antes aquí ponía
        // «Emparejar un PC», que es un botón que ya no existe: quien lo buscara en la
        // pantalla del móvil no lo encontraría.
        var explicacion = new Label
        {
            Text = "Abre DracPaste en el móvil, pulsa «Escanear el código del PC»\n"
                   + "y apunta con la cámara a este código.",
            Dock = DockStyle.Top,
            Height = 44,
            Padding = new Padding(18, 12, 18, 0),
            TextAlign = ContentAlignment.TopLeft,
            Font = Tipos.Normal(9.5f),
        };

        var esteP = new Label
        {
            Text = $"Este PC: {qr.Name} · {qr.Ip}:{qr.Port}",
            Dock = DockStyle.Top,
            Height = 26,
            Padding = new Padding(18, 0, 18, 0),
            TextAlign = ContentAlignment.TopLeft,
            ForeColor = Paleta.Apagado,
        };

        _imagenQr = new PictureBox
        {
            Image = GenerarQr(qr.ASerializar()),
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Top,
            Height = 320,
            BackColor = Paleta.Tarjeta,
        };

        var hayHuella = !string.IsNullOrWhiteSpace(huella);

        var huellaEtiqueta = new Label
        {
            Text = hayHuella ? $"Huella: {huella}" : "Comprueba la huella al terminar",
            Dock = DockStyle.Top,
            Height = 32,
            // Consolas en los dos casos: aunque todavía no haya huella que enseñar, el
            // sitio donde va a aparecer ya se ve como lo que es.
            Font = hayHuella ? Tipos.Huella(13f) : Tipos.Huella(11f),
            TextAlign = ContentAlignment.MiddleCenter,
        };

        var explicacionHuella = new Label
        {
            Text = hayHuella
                ? "El móvil debe mostrar esta misma huella al terminar."
                : "El móvil enseñará una huella; este PC mostrará la misma.",
            Dock = DockStyle.Top,
            // 28 y no 24: a 420 px de ancho, este texto se iba a dos líneas y la segunda
            // se comía la cuenta atrás de debajo.
            Height = 28,
            TextAlign = ContentAlignment.MiddleCenter,
            ForeColor = Paleta.Apagado,
        };

        _caducaEn = DateTime.UtcNow.AddMilliseconds(Protocolo.Protocolo.ValidezTokenMs);

        _caducidad = new Label
        {
            Dock = DockStyle.Left,
            AutoSize = true,
            TextAlign = ContentAlignment.MiddleLeft,
            Padding = new Padding(0, 4, 0, 0),
        };

        // A la derecha y en gris: es una promesa, no una alarma. Enterarse de que el
        // código se renueva solo cambia lo que hace el usuario cuando ve poco tiempo.
        var promesa = new Label
        {
            Text = "se renueva solo al caducar",
            Dock = DockStyle.Right,
            AutoSize = true,
            TextAlign = ContentAlignment.MiddleRight,
            ForeColor = Paleta.Apagado,
            Padding = new Padding(0, 4, 0, 0),
        };

        var filaCaducidad = new Panel { Dock = DockStyle.Top, Height = 26, Padding = new Padding(18, 0, 18, 0) };
        filaCaducidad.Controls.Add(promesa);
        filaCaducidad.Controls.Add(_caducidad);

        // Una barra que se vacía se ve sin leer; la cuenta atrás en texto, en gris y en
        // mitad de la ventana, no la miraba nadie. El movimiento se coge con el rabillo
        // del ojo; un número, no.
        //
        // Dos paneles y no una ProgressBar: la de serie se pinta del verde de Windows, y
        // habría sido el único color de toda la ventana fuera de la paleta. Sigue siendo
        // un control de serie, sin dibujar nada a mano.
        _barra = new Panel { Dock = DockStyle.Left, BackColor = Paleta.Tinta };

        var canal = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Paleta.Caja,
            BorderStyle = BorderStyle.FixedSingle,
        };
        canal.Controls.Add(_barra);

        var marcoBarra = new Panel { Dock = DockStyle.Top, Height = 22, Padding = new Padding(18, 4, 18, 4) };
        marcoBarra.Controls.Add(canal);

        _textoAlternativo = new TextBox
        {
            Text = qr.ASerializar(),
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            // Este texto no se lee, se copia: en gris y pequeño, para que no compita con
            // el QR, que es por donde va a emparejar casi todo el mundo.
            Font = Tipos.Codigo(),
            BackColor = Paleta.Caja,
            ForeColor = Paleta.Apagado,
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
            ForeColor = Paleta.Apagado,
        };

        // Con Dock.Top, el último que se añade es el que queda más arriba.
        Controls.Add(marcoTexto);
        Controls.Add(pieTexto);
        Controls.Add(filaCaducidad);
        Controls.Add(marcoBarra);
        Controls.Add(explicacionHuella);
        Controls.Add(huellaEtiqueta);
        Controls.Add(_imagenQr);
        Controls.Add(esteP);
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
            BackColor = Paleta.PeligroSuave,
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

        var aviso = new Label
        {
            Text = "⚠",
            Dock = DockStyle.Left,
            Width = 26,
            ForeColor = Paleta.Peligro,
            Font = Tipos.Fuerte(12f),
            TextAlign = ContentAlignment.TopLeft,
        };

        var texto = new Label
        {
            Text = "El firewall va a bloquear al móvil. Verá este PC, pero la conexión morirá "
                   + "en un tiempo de espera.",
            Dock = DockStyle.Fill,
            ForeColor = Paleta.Peligro,
            Font = Tipos.Fuerte(8.5f),
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
        panel.Controls.Add(aviso);
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

        _caducidad.Text = $"El código caduca en {quedan.Minutes}:{quedan.Seconds:D2}";
        _caducidad.ForeColor = Paleta.Tinta;

        // El ancho se calcula sobre el del canal, que es quien conoce el tamaño real tras
        // aplicar el escalado de DPI.
        var canal = _barra.Parent;
        if (canal is not null)
        {
            var fraccion = quedan.TotalMilliseconds / Protocolo.Protocolo.ValidezTokenMs;
            _barra.Width = (int)Math.Clamp(canal.ClientSize.Width * fraccion, 0, canal.ClientSize.Width);
        }
    }

    /// <summary>
    /// Tapa el QR con un panel de tinta cuando el código ha caducado y **no** se ha
    /// podido renovar (sin red, normalmente).
    ///
    /// El QR muerto no puede seguir a la vista con aspecto de válido: alguien lo escanea,
    /// el móvil da un error que no explica nada, y a nadie se le ocurre que el problema
    /// era que el dibujo llevaba dos minutos caducado.
    /// </summary>
    private void MostrarCaducado()
    {
        var panel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Paleta.Tinta,
            Padding = new Padding(24, 38, 24, 38),
        };

        var titulo = new Label
        {
            Text = "El código ha caducado",
            Dock = DockStyle.Top,
            Height = 34,
            ForeColor = Color.White,
            Font = Tipos.Fuerte(13f),
            TextAlign = ContentAlignment.MiddleCenter,
        };

        var explicacion = new Label
        {
            Text = "No se pudo generar uno nuevo. Comprueba la red y vuelve a intentarlo.",
            Dock = DockStyle.Top,
            Height = 44,
            ForeColor = Color.Gainsboro,
            TextAlign = ContentAlignment.MiddleCenter,
        };

        var reintentar = new Button
        {
            Text = "Generar un código nuevo",
            Dock = DockStyle.Top,
            Height = 30,
            FlatStyle = FlatStyle.Flat,
            BackColor = Paleta.Tinta,
            ForeColor = Color.White,
            Font = Tipos.Fuerte(),
        };
        reintentar.FlatAppearance.BorderColor = Color.White;
        reintentar.Click += (_, _) =>
        {
            _imagenQr.Controls.Clear();
            _cuentaAtras.Start();
            Renovar();
        };

        panel.Controls.Add(reintentar);
        panel.Controls.Add(explicacion);
        panel.Controls.Add(titulo);

        // Dentro del PictureBox del QR: tapa justo lo que ha dejado de valer.
        _imagenQr.Controls.Add(panel);
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
            _barra.Width = 0;
            _caducidad.Text = "El código ha caducado";
            _caducidad.ForeColor = Paleta.Peligro;
            MostrarCaducado();
            Caducado?.Invoke();
            return;
        }

        var serializado = nuevo.ASerializar();
        var anterior = _imagenQr.Image;
        _imagenQr.Image = GenerarQr(serializado);
        anterior?.Dispose();
        _textoAlternativo.Text = serializado;

        _caducaEn = DateTime.UtcNow.AddMilliseconds(Protocolo.Protocolo.ValidezTokenMs);
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
