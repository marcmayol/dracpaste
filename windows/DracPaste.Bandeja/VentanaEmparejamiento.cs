using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja;

/// <summary>
/// La ventana de emparejamiento.
///
/// En la Fase 1 muestra el JSON del QR en texto seleccionable, que es lo que permite
/// emparejar pegándolo a mano en el móvil mientras el escáner de cámara no existe. El QR
/// gráfico llega en la Fase 4 y ocupará el hueco central sin cambiar nada más.
/// </summary>
internal sealed class VentanaEmparejamiento : Form
{
    public VentanaEmparejamiento(DatosQr qr)
    {
        Text = "DracPaste · emparejar un móvil";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(560, 340);

        var explicacion = new Label
        {
            Text = $"""
                    Este PC: {qr.Name} ({qr.Ip}:{qr.Port})

                    Abre DracPaste en el móvil y pega este texto en la pantalla de
                    emparejamiento. El código caduca en dos minutos y solo vale una vez.
                    """,
            Dock = DockStyle.Top,
            Height = 90,
            Padding = new Padding(12, 12, 12, 0),
        };

        var contenido = new TextBox
        {
            Text = qr.ASerializar(),
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            Font = new Font(FontFamily.GenericMonospace, 9f),
            Margin = new Padding(12),
        };
        contenido.SelectAll();

        var botones = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = 52,
            Padding = new Padding(12, 10, 12, 10),
        };

        var cerrar = new Button { Text = "Cerrar", DialogResult = DialogResult.OK, AutoSize = true };
        var copiar = new Button { Text = "Copiar al portapapeles", AutoSize = true };
        copiar.Click += (_, _) =>
        {
            Clipboard.SetText(contenido.Text);
            copiar.Text = "Copiado";
        };

        botones.Controls.Add(cerrar);
        botones.Controls.Add(copiar);

        var marco = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12, 0, 12, 0) };
        marco.Controls.Add(contenido);

        Controls.Add(marco);
        Controls.Add(botones);
        Controls.Add(explicacion);

        AcceptButton = cerrar;
        CancelButton = cerrar;
    }
}
