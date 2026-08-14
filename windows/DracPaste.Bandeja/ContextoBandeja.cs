using System.ComponentModel;

namespace DracPaste.Bandeja;

/// <summary>
/// La aplicación vive aquí: un icono en la bandeja y ninguna ventana principal.
/// Las ventanas (emparejar, ajustes) se abren bajo demanda desde el menú.
/// </summary>
internal sealed class ContextoBandeja : ApplicationContext
{
    private readonly NotifyIcon _icono;
    private readonly ToolStripMenuItem _estadoMenu;

    public ContextoBandeja()
    {
        _estadoMenu = new ToolStripMenuItem("Sin emparejar") { Enabled = false };

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

    private static Icon CargarIcono()
    {
        var ruta = Path.Combine(AppContext.BaseDirectory, "Recursos", "dracpaste.ico");
        return File.Exists(ruta) ? new Icon(ruta) : SystemIcons.Application;
    }

    private void AbrirEmparejamiento()
    {
        // Fase 4.
        MessageBox.Show(
            "El emparejamiento llega en la Fase 4.",
            "DracPaste",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private void AbrirAjustes()
    {
        // Fase 5.
        MessageBox.Show(
            "Los ajustes llegan en la Fase 5.",
            "DracPaste",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
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
            _icono.Visible = false;
            _icono.Dispose();
        }

        base.Dispose(disposing);
    }
}
