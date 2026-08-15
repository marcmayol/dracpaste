using DracPaste.Bandeja.Nucleo;

namespace DracPaste.Bandeja;

/// <summary>
/// Los móviles emparejados con este PC, con su huella y la opción de desemparejarlos.
///
/// Desemparejar desde aquí manda un <c>UNPAIR</c> si el móvil está conectado, para que
/// borre sus claves también. Si no lo está, este PC lo olvida igual: el móvil se
/// encontrará con que ya no le reconocen y su handshake fallará, que es exactamente lo
/// que se busca.
/// </summary>
internal sealed class VentanaAjustes : Form
{
    private readonly RegistroEmparejados _registro;
    private readonly ServidorDracPaste _servidor;
    private readonly Identidad _identidad;
    private readonly ListView _lista;

    public VentanaAjustes(Identidad identidad, RegistroEmparejados registro, ServidorDracPaste servidor)
    {
        _identidad = identidad;
        _registro = registro;
        _servidor = servidor;

        Text = "DracPaste · ajustes";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(560, 420);

        var cabecera = new Label
        {
            Text = $"""
                    Este PC: {identidad.Nombre}
                    Escuchando en el puerto {servidor.Puerto}
                    """,
            Dock = DockStyle.Top,
            Height = 52,
            Padding = new Padding(16, 12, 16, 0),
        };

        _lista = new ListView
        {
            Dock = DockStyle.Fill,
            View = View.Details,
            FullRowSelect = true,
            MultiSelect = false,
            HideSelection = false,
        };
        // Los anchos suman 508, que es lo que queda de los 560 de la ventana tras los
        // márgenes y el borde: con más, aparecía una barra de desplazamiento horizontal
        // y la última columna quedaba cortada.
        _lista.Columns.Add("Móvil", 180);
        _lista.Columns.Add("Huella", 100);
        _lista.Columns.Add("Emparejado", 130);
        _lista.Columns.Add("Estado", 98);

        var botones = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = 52,
            Padding = new Padding(12, 10, 12, 10),
        };

        var cerrar = new Button { Text = "Cerrar", DialogResult = DialogResult.OK, AutoSize = true };
        var desemparejar = new Button { Text = "Desemparejar", AutoSize = true };
        desemparejar.Click += (_, _) => Desemparejar();

        botones.Controls.Add(cerrar);
        botones.Controls.Add(desemparejar);

        var privacidad = new Label
        {
            Text = "DracPaste no envía nada fuera de tu red local y no guarda historial de clips.",
            Dock = DockStyle.Bottom,
            Height = 32,
            Padding = new Padding(16, 8, 16, 0),
            ForeColor = Color.DimGray,
        };

        var marco = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16, 8, 16, 8) };
        marco.Controls.Add(_lista);

        Controls.Add(marco);
        Controls.Add(privacidad);
        Controls.Add(botones);
        Controls.Add(cabecera);

        AcceptButton = cerrar;
        Refrescar();
    }

    private void Refrescar()
    {
        _lista.Items.Clear();

        foreach (var movil in _registro.Todos.OrderByDescending(d => d.EmparejadoEn))
        {
            var conectado = _servidor.HayMovilConectado && _servidor.NombreMovilConectado == movil.Nombre;
            var fila = new ListViewItem(movil.Nombre);
            fila.SubItems.Add(movil.Huella);
            fila.SubItems.Add(movil.EmparejadoEn.ToString("dd/MM/yyyy HH:mm"));
            fila.SubItems.Add(conectado ? "Conectado" : "—");
            fila.Tag = movil.DeviceId;
            _lista.Items.Add(fila);
        }

        if (_lista.Items.Count == 0)
        {
            var vacio = new ListViewItem("Ningún móvil emparejado todavía");
            vacio.ForeColor = Color.DimGray;
            _lista.Items.Add(vacio);
        }
    }

    private void Desemparejar()
    {
        if (_lista.SelectedItems.Count == 0 || _lista.SelectedItems[0].Tag is not string deviceId)
        {
            return;
        }

        var nombre = _lista.SelectedItems[0].Text;
        var respuesta = MessageBox.Show(
            $"""
             ¿Desemparejar {nombre}?

             Este PC borrará su clave y dejará de aceptar sus conexiones. Si el móvil está
             conectado, borrará la suya también.

             Para volver a usarlo habrá que emparejarlo de nuevo con un código.
             """,
            "DracPaste",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);

        if (respuesta != DialogResult.Yes)
        {
            return;
        }

        // Se avisa al móvil antes de olvidarlo: después de borrar la clave ya no habría
        // forma de mandarle un mensaje cifrado.
        _ = _servidor.DesemparejarAsync(deviceId);
        Refrescar();
    }
}
