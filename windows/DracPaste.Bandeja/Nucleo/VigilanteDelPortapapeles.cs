using System.Runtime.InteropServices;
using DracPaste.Protocolo.Mensajes;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Vigila el portapapeles de Windows y avisa de cada copia nueva.
///
/// Usa <c>AddClipboardFormatListener</c> sobre una ventana oculta *message-only*: el
/// sistema avisa cuando algo cambia, en vez de tener la app preguntando cada pocos
/// milisegundos. Sin polling no hay consumo cuando nadie copia nada, que es casi todo el
/// tiempo.
///
/// Dos protecciones contra el bucle de eco (<c>docs/protocol.md</c> §6):
///
/// 1. Un **formato de portapapeles propio** que se pega a todo lo que escribe esta app.
///    Si un aviso trae ese formato, el cambio es obra nuestra y se descarta sin más.
/// 2. El **`origin_id`** del último clip recibido, por si el formato propio se pierde por
///    el camino: algunos gestores de portapapeles copian solo el texto y tiran el resto.
///
/// Son dos mecanismos distintos a propósito, porque si falla el único que hay, el
/// resultado no es un fallo silencioso sino un bucle infinito entre los dos aparatos.
/// </summary>
internal sealed class VigilanteDelPortapapeles : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;

    private readonly VentanaOculta _ventana;
    private readonly System.Windows.Forms.Timer _debounce;
    private readonly uint _formatoPropio;

    private string? _ultimoTextoEnviado;

    public VigilanteDelPortapapeles()
    {
        _formatoPropio = RegisterClipboardFormat(Protocolo.Protocolo.FormatoMarcaPropia);

        _ventana = new VentanaOculta(AlCambiarElPortapapeles);
        AddClipboardFormatListener(_ventana.Handle);

        // Varias aplicaciones disparan más de un WM_CLIPBOARDUPDATE por una sola copia
        // (Office manda uno por cada formato que publica). Sin el debounce, un Ctrl+C en
        // Word enviaría el mismo clip tres o cuatro veces.
        _debounce = new System.Windows.Forms.Timer { Interval = Protocolo.Protocolo.DebounceClipboardMs };
        _debounce.Tick += (_, _) =>
        {
            _debounce.Stop();
            LeerYAvisar();
        };
    }

    /// <summary>Se dispara con el texto de cada copia que debe viajar al móvil.</summary>
    public event Action<string>? TextoCopiado;

    /// <summary>Mientras esté en pausa, lo que se copie en el PC no sale de aquí.</summary>
    public bool EnPausa { get; set; }

    /// <summary>
    /// Escribe en el portapapeles un texto que llega del móvil, marcándolo como propio
    /// para que el aviso que provoque no se devuelva.
    /// </summary>
    public void EscribirDesdeElMovil(string texto)
    {
        _ultimoTextoEnviado = texto;

        var datos = new DataObject();
        datos.SetText(texto, TextDataFormat.UnicodeText);
        // El contenido de la marca da igual: lo que importa es que el formato esté.
        datos.SetData(Protocolo.Protocolo.FormatoMarcaPropia, new MemoryStream(new byte[] { 1 }));

        // El portapapeles de Windows puede estar tomado por otra aplicación durante unos
        // milisegundos. Se reintenta un poco antes de rendirse, en vez de perder el clip.
        for (var intento = 0; intento < 5; intento++)
        {
            try
            {
                Clipboard.SetDataObject(datos, copy: true);
                return;
            }
            catch (ExternalException)
            {
                Thread.Sleep(30);
            }
        }
    }

    private void AlCambiarElPortapapeles()
    {
        _debounce.Stop();
        _debounce.Start();
    }

    private void LeerYAvisar()
    {
        if (EnPausa)
        {
            return;
        }

        try
        {
            var datos = Clipboard.GetDataObject();
            if (datos is null)
            {
                return;
            }

            // Primera barrera: lo ha escrito esta app.
            if (datos.GetDataPresent(Protocolo.Protocolo.FormatoMarcaPropia))
            {
                return;
            }

            if (!datos.GetDataPresent(DataFormats.UnicodeText))
            {
                // Una imagen o un fichero. v1 solo transporta texto; en v2 entrarán aquí.
                return;
            }

            if (datos.GetData(DataFormats.UnicodeText) is not string texto || string.IsNullOrEmpty(texto))
            {
                return;
            }

            // Segunda barrera: coincide con lo último que se escribió desde el móvil,
            // aunque la marca propia se haya perdido por el camino.
            if (texto == _ultimoTextoEnviado)
            {
                _ultimoTextoEnviado = null;
                return;
            }

            if (System.Text.Encoding.UTF8.GetByteCount(texto) > Protocolo.Protocolo.MaxClipBytes)
            {
                DemasiadoGrande?.Invoke(texto.Length);
                return;
            }

            TextoCopiado?.Invoke(texto);
        }
        catch (ExternalException)
        {
            // Otra aplicación tenía el portapapeles tomado justo en ese instante. El
            // siguiente cambio volverá a intentarlo; no hay nada que hacer aquí.
        }
    }

    /// <summary>Se dispara cuando una copia pasa del máximo y no se envía.</summary>
    public event Action<int>? DemasiadoGrande;

    /// <summary>
    /// El `origin_id` de lo último que se escribió desde el móvil, para que quien
    /// coordine pueda cruzarlo con el anti-eco compartido.
    /// </summary>
    public string? OrigenDelUltimoRecibido =>
        _ultimoTextoEnviado is null ? null : Clip.OrigenDe(_ultimoTextoEnviado);

    public void Dispose()
    {
        RemoveClipboardFormatListener(_ventana.Handle);
        _debounce.Dispose();
        _ventana.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern uint RegisterClipboardFormat(string formato);

    /// <summary>
    /// Una ventana *message-only*: existe solo para recibir mensajes del sistema, no se
    /// dibuja ni aparece en la barra de tareas. Es lo que necesita
    /// <c>AddClipboardFormatListener</c>, que exige un HWND.
    /// </summary>
    private sealed class VentanaOculta : NativeWindow, IDisposable
    {
        private readonly Action _alCambiar;

        public VentanaOculta(Action alCambiar)
        {
            _alCambiar = alCambiar;
            CreateHandle(new CreateParams());
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_CLIPBOARDUPDATE)
            {
                _alCambiar();
            }

            base.WndProc(ref m);
        }

        public void Dispose() => DestroyHandle();
    }
}
