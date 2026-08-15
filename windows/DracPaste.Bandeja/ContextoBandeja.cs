using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Sesion;
using Microsoft.Win32;

namespace DracPaste.Bandeja;

/// <summary>
/// La aplicación vive aquí: un icono en la bandeja y ninguna ventana principal.
/// Las ventanas (emparejar, ajustes) se abren bajo demanda desde el menú.
/// </summary>
internal sealed class ContextoBandeja : ApplicationContext
{
    private readonly NotifyIcon _icono;
    private readonly ToolStripMenuItem _estadoMenu;

    private readonly Identidad _identidad;
    private readonly RegistroEmparejados _registro;
    private readonly GestorTokens _tokens;
    private readonly ServidorDracPaste _servidor;
    private readonly AnuncioMdns _mdns;

    public ContextoBandeja()
    {
        _identidad = Identidad.CargarOCrear();
        _registro = RegistroEmparejados.Cargar();
        _tokens = new GestorTokens();
        _servidor = new ServidorDracPaste(_identidad, _registro, _tokens);
        _mdns = new AnuncioMdns(_identidad);

        _estadoMenu = new ToolStripMenuItem("Arrancando…") { Enabled = false };

        var menu = new ContextMenuStrip();
        menu.Items.Add(_estadoMenu);
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

        _servidor.Arrancar();
        _mdns.Anunciar(_servidor.Puerto);

        // Al despertar de suspensión hay que reanunciar el servicio y reactivar la
        // escucha: mientras el PC dormía, los anuncios caducaron en las cachés mDNS de
        // la red y el móvil dejó de ver el servicio.
        SystemEvents.PowerModeChanged += AlCambiarModoDeEnergia;

        MostrarEstado(_registro.Todos.Count == 0 ? "Sin emparejar" : "Esperando al móvil");
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

    private void ClipDelMovil(Clip clip)
    {
        // La escritura en el portapapeles llega en la Fase 3; de momento se avisa de que
        // el túnel funciona, que es lo que valida la Fase 1.
        EnElHiloDeLaInterfaz(() => _icono.ShowBalloonTip(
            3000,
            "DracPaste · clip recibido",
            Resumir(clip.Texto()),
            ToolTipIcon.Info));
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

        // El QR gráfico llega en la Fase 4. Por ahora se muestra el JSON, que es lo que
        // la Fase 1 necesita para emparejar pegándolo a mano en el móvil.
        using var ventana = new VentanaEmparejamiento(qr);
        ventana.ShowDialog();
    }

    private void AbrirAjustes()
    {
        var emparejados = _registro.Todos;
        var lista = emparejados.Count == 0
            ? "Ningún móvil emparejado todavía."
            : string.Join(
                Environment.NewLine,
                emparejados.Select(d => $"· {d.Nombre} — huella {d.Huella}"));

        MessageBox.Show(
            $"""
             Este PC: {_identidad.Nombre}
             Puerto: {_servidor.Puerto}
             Anuncio mDNS: {(_mdns.Anunciando ? "activo" : "detenido")}

             Móviles emparejados:
             {lista}

             Los ajustes completos llegan en la Fase 5.
             """,
            "DracPaste",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private void AlCambiarModoDeEnergia(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode != PowerModes.Resume)
        {
            return;
        }

        try
        {
            _mdns.Reanunciar();
            MostrarEstado(_servidor.HayMovilConectado
                ? $"Conectado con {_servidor.NombreMovilConectado}"
                : "Esperando al móvil");
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
            _mdns.Dispose();
            _servidor.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _icono.Visible = false;
            _icono.Dispose();
        }

        base.Dispose(disposing);
    }
}
