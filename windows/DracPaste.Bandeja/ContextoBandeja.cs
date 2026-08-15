using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Sesion;
using Microsoft.Win32;
using AntiEco = DracPaste.Protocolo.Sesion.AntiEco;

namespace DracPaste.Bandeja;

/// <summary>
/// La aplicación vive aquí: un icono en la bandeja y ninguna ventana principal.
/// Las ventanas (emparejar, ajustes) se abren bajo demanda desde el menú.
/// </summary>
internal sealed class ContextoBandeja : ApplicationContext
{
    private readonly NotifyIcon _icono;
    private readonly ToolStripMenuItem _estadoMenu;
    private readonly ToolStripMenuItem _avisoCortafuegos;

    private readonly Identidad _identidad;
    private readonly RegistroEmparejados _registro;
    private readonly GestorTokens _tokens;
    private readonly ServidorDracPaste _servidor;
    private readonly AnuncioMdns _mdns;
    private readonly VigilanteDelPortapapeles _portapapeles;
    private readonly AntiEco _antiEco = new();

    public ContextoBandeja()
    {
        _identidad = Identidad.CargarOCrear();
        _registro = RegistroEmparejados.Cargar();
        _tokens = new GestorTokens();
        _servidor = new ServidorDracPaste(_identidad, _registro, _tokens);
        _mdns = new AnuncioMdns(_identidad);

        _estadoMenu = new ToolStripMenuItem("Arrancando…") { Enabled = false };

        _avisoCortafuegos = new ToolStripMenuItem(
            "Permitir en el firewall de Windows…", null, (_, _) => ArreglarCortafuegos())
        {
            Visible = false,
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add(_estadoMenu);
        menu.Items.Add(_avisoCortafuegos);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Emparejar un móvil…", null, (_, _) => AbrirEmparejamiento());
        menu.Items.Add("Ajustes…", null, (_, _) => AbrirAjustes());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Salir", null, (_, _) => Salir());

        _icono = new NotifyIcon
        {
            Icon = CargarIcono(),
            Text = "DracPaste",
            Visible = true,
            ContextMenuStrip = menu,
        };

        _servidor.EstadoCambiado += estado => EnElHiloDeLaInterfaz(() => MostrarEstado(estado));
        _servidor.ClipRecibido += ClipDelMovil;
        _servidor.DispositivoEmparejado += dispositivo => EnElHiloDeLaInterfaz(() =>
            _icono.ShowBalloonTip(
                4000,
                "DracPaste",
                $"Emparejado con {dispositivo.Nombre} · huella {dispositivo.Huella}",
                ToolTipIcon.Info));

        _portapapeles = new VigilanteDelPortapapeles();
        _portapapeles.TextoCopiado += TextoCopiadoEnElPc;
        _portapapeles.DemasiadoGrande += caracteres => EnElHiloDeLaInterfaz(() =>
            _icono.ShowBalloonTip(
                4000,
                "DracPaste",
                $"Ese texto ocupa demasiado ({caracteres:N0} caracteres) y no se ha enviado al móvil.",
                ToolTipIcon.Warning));

        _servidor.Arrancar();
        _mdns.Anunciar(_servidor.Puerto);

        // Al despertar de suspensión hay que reanunciar el servicio y reactivar la
        // escucha: mientras el PC dormía, los anuncios caducaron en las cachés mDNS de
        // la red y el móvil dejó de ver el servicio.
        SystemEvents.PowerModeChanged += AlCambiarModoDeEnergia;

        MostrarEstado(_registro.Todos.Count == 0 ? "Sin emparejar" : "Esperando al móvil");
        ComprobarCortafuegos();
    }

    /// <summary>
    /// Avisa si el firewall va a impedir que el móvil llegue.
    ///
    /// Sin regla, el móvil ve el PC pero la conexión muere en un timeout, y desde el
    /// móvil eso parece un problema de red. Vale más molestar aquí una vez que dejar al
    /// usuario mirando el WiFi.
    /// </summary>
    private void ComprobarCortafuegos()
    {
        var hayRegla = Cortafuegos.HayReglaDeEntrada();

        // null = no se ha podido averiguar. Callar es mejor que dar una alarma falsa.
        if (hayRegla != false)
        {
            _avisoCortafuegos.Visible = false;
            return;
        }

        _avisoCortafuegos.Visible = true;
        _icono.ShowBalloonTip(
            8000,
            "DracPaste · falta un permiso",
            "El firewall de Windows va a bloquear al móvil. Abre el menú de DracPaste y " +
            "pulsa «Permitir en el firewall de Windows…».",
            ToolTipIcon.Warning);
    }

    private void ArreglarCortafuegos()
    {
        if (Cortafuegos.CrearReglas())
        {
            _avisoCortafuegos.Visible = false;
            MessageBox.Show(
                "Listo. El móvil ya puede conectarse con este PC.",
                "DracPaste",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        MessageBox.Show(
            """
            No se han podido crear las reglas del firewall.

            Hace falta aceptar el aviso de administrador de Windows. Si lo has rechazado,
            vuelve a intentarlo desde el menú.

            También puedes hacerlo a mano en «Firewall de Windows Defender → Permitir una
            aplicación», marcando DracPaste en la columna de redes privadas.
            """,
            "DracPaste",
            MessageBoxButtons.OK,
            MessageBoxIcon.Warning);
    }

    /// <summary>Actualiza el estado que se ve en el menú y en el tooltip del icono.</summary>
    public void MostrarEstado(string estado)
    {
        _estadoMenu.Text = estado;
        // El tooltip de la bandeja trunca a 63 caracteres; se recorta aquí para que no
        // se pierda silenciosamente el final del mensaje.
        var texto = $"DracPaste · {estado}";
        _icono.Text = texto.Length <= 63 ? texto : texto[..60] + "…";
    }

    /// <summary>
    /// Una copia hecha en este PC. Se envía al móvil salvo que sea el eco de algo que
    /// acaba de llegar de él.
    /// </summary>
    private void TextoCopiadoEnElPc(string texto)
    {
        if (!_antiEco.DebeReenviar(Clip.OrigenDe(texto)))
        {
            return;
        }

        // El envío es asíncrono, pero el evento del portapapeles llega en el hilo de la
        // interfaz: bloquearlo aquí congelaría el escritorio del usuario mientras dura
        // una escritura de red.
        _ = Task.Run(async () =>
        {
            var enviado = await _servidor.EnviarClipAsync(Clip.DeTexto(texto)).ConfigureAwait(false);
            if (!enviado)
            {
                // Sin cola de clips (docs/protocol.md §8): no se guarda nada pendiente.
                // El estado de la bandeja ya dice que no hay móvil conectado.
                EnElHiloDeLaInterfaz(() => MostrarEstado("Sin móvil conectado · el último clip no se envió"));
            }
        });
    }

    /// <summary>Un clip que llega del móvil: se escribe en el portapapeles de Windows.</summary>
    private void ClipDelMovil(Clip clip)
    {
        var texto = clip.Texto();

        EnElHiloDeLaInterfaz(() =>
        {
            // Se anota antes de escribir: el aviso del portapapeles puede llegar en el
            // mismo instante, y si la marca no estuviera ya puesta, el clip se devolvería
            // al móvil y empezaría el bucle.
            _antiEco.MarcarRecibido(clip.OriginId);
            _portapapeles.EscribirDesdeElMovil(texto);
        });
    }

    private static string Resumir(string texto)
    {
        var unaLinea = texto.ReplaceLineEndings(" ").Trim();
        return unaLinea.Length <= 80 ? unaLinea : unaLinea[..77] + "…";
    }

    private static Icon CargarIcono()
    {
        var ruta = Path.Combine(AppContext.BaseDirectory, "Recursos", "dracpaste.ico");
        return File.Exists(ruta) ? new Icon(ruta) : SystemIcons.Application;
    }

    private void AbrirEmparejamiento()
    {
        var ip = AnuncioMdns.IpLocal();
        if (ip is null)
        {
            MessageBox.Show(
                "No se encuentra ninguna red local activa. Conecta el PC al WiFi o al cable y vuelve a intentarlo.",
                "DracPaste",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            return;
        }

        var qr = new DatosQr
        {
            Pk = Convert.ToBase64String(_identidad.Publica),
            Ip = ip.ToString(),
            Port = _servidor.Puerto,
            Token = Convert.ToBase64String(_tokens.Emitir()),
            Name = _identidad.Nombre,
            DeviceId = _identidad.DeviceId,
        };

        // La huella depende de la clave pública del móvil, que todavía no se conoce: se
        // calcula al emparejar y se enseña entonces en el globo de la bandeja.
        using var ventana = new VentanaEmparejamiento(qr);

        // Al cerrar la ventana, el código deja de valer aunque no haya caducado: quien
        // ha dejado de mirar la pantalla no espera que su QR siga sirviendo.
        ventana.ShowDialog();
        _tokens.Revocar();
    }

    private void AbrirAjustes()
    {
        using var ventana = new VentanaAjustes(_identidad, _registro, _servidor);
        ventana.PausaCambiada += pausado =>
        {
            _servidor.EnPausa = pausado;
            // También se para el vigilante: sin esto, seguiría leyendo el portapapeles de
            // cada copia para acabar descartándola.
            _portapapeles.EnPausa = pausado;
        };

        ventana.ShowDialog();
        MostrarEstado(EstadoActual());
    }

    private string EstadoActual()
    {
        if (_servidor.EnPausa)
        {
            return "En pausa";
        }

        if (_registro.Todos.Count == 0)
        {
            return "Sin emparejar";
        }

        return EstadoDeConexion();
    }

    private string EstadoDeConexion() =>
        _servidor.HayMovilConectado
            ? $"Conectado con {_servidor.NombreMovilConectado}"
            : "Esperando al móvil";

    private void AlCambiarModoDeEnergia(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode != PowerModes.Resume)
        {
            return;
        }

        try
        {
            _mdns.Reanunciar();
            MostrarEstado(EstadoActual());
        }
        catch (Exception)
        {
            // Justo al despertar, la red puede no estar lista todavía. No es motivo para
            // que la app se caiga: el móvil reintenta por su cuenta con backoff.
        }
    }

    private void EnElHiloDeLaInterfaz(Action accion)
    {
        if (_icono.ContextMenuStrip is { IsHandleCreated: true } menu)
        {
            menu.BeginInvoke(accion);
        }
        else
        {
            accion();
        }
    }

    private void Salir()
    {
        _icono.Visible = false;
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            SystemEvents.PowerModeChanged -= AlCambiarModoDeEnergia;
            _portapapeles.Dispose();
            _mdns.Dispose();
            _servidor.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _icono.Visible = false;
            _icono.Dispose();
        }

        base.Dispose(disposing);
    }
}
