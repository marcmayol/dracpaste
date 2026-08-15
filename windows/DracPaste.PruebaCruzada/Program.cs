using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.PruebaCruzada;

/// <summary>
/// El lado de Windows de la prueba cruzada.
///
/// Arranca el servidor **real** —el mismo <c>ServidorDracPaste</c> que usa la app de
/// bandeja—, imprime el JSON del QR y espera a que el cliente Kotlin se empareje, le
/// mande un clip y reciba el suyo.
///
/// Es la única prueba que demuestra que las dos implementaciones se entienden por un
/// socket de verdad. Los vectores de <c>docs/protocol.md</c> §7 comprueban que las
/// primitivas coinciden, pero no que el diálogo completo funcione entre lenguajes
/// distintos.
///
/// Usa una carpeta temporal para no tocar la identidad real del usuario.
/// </summary>
internal static class Program
{
    private const string TextoDeIda = "desde-kotlin-àéî-🐉";
    private const string TextoDeVuelta = "desde-csharp-àéî-🐉";

    private static async Task<int> Main(string[] args)
    {
        var carpeta = Path.Combine(Path.GetTempPath(), "dracpaste-cruzada-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(carpeta);

        try
        {
            return await EjecutarAsync(carpeta, args).ConfigureAwait(false);
        }
        finally
        {
            try
            {
                Directory.Delete(carpeta, recursive: true);
            }
            catch (IOException)
            {
                // Un fichero recién cerrado puede seguir bloqueado un instante en
                // Windows. No es motivo para dar la prueba por fallida.
            }
        }
    }

    private static async Task<int> EjecutarAsync(string carpeta, string[] args)
    {
        var identidad = Identidad.CargarOCrear(carpeta);
        var registro = RegistroEmparejados.Cargar(carpeta);
        var tokens = new GestorTokens();

        await using var servidor = new ServidorDracPaste(identidad, registro, tokens);

        var recibido = new TaskCompletionSource<Clip>();
        servidor.ClipRecibido += clip => recibido.TrySetResult(clip);
        servidor.DispositivoEmparejado += d =>
            Console.WriteLine($"EMPAREJADO: {d.Nombre} · huella {d.Huella}");

        // Puerto efímero por defecto: la prueba no debe pelearse con una instancia real
        // de DracPaste que el usuario tenga abierta. Se puede fijar uno para poder
        // redirigirlo con `adb reverse` y probar contra el APK del emulador.
        var puerto = args.Length > 0 && int.TryParse(args[0], out var elegido) ? elegido : 0;
        servidor.Arrancar(puertoPreferido: puerto);

        var qr = new DatosQr
        {
            Pk = Convert.ToBase64String(identidad.Publica),
            Ip = "127.0.0.1",
            Port = servidor.Puerto,
            Token = Convert.ToBase64String(tokens.Emitir()),
            // Sin espacios a propósito: así el JSON se puede teclear con `adb shell
            // input text` para probar contra el APK real del emulador.
            Name = "PC-de-prueba",
            DeviceId = identidad.DeviceId,
        };

        // El script lee esta línea y se la pasa al cliente Kotlin.
        Console.WriteLine("QR=" + qr.ASerializar());
        Console.Out.Flush();

        var clip = await EsperarAsync(recibido.Task, TimeSpan.FromSeconds(60), "el clip del cliente Kotlin")
            .ConfigureAwait(false);

        if (clip is null)
        {
            return 1;
        }

        Console.WriteLine($"RECIBIDO: {clip.Texto()}");

        if (clip.Texto() != TextoDeIda)
        {
            Console.Error.WriteLine($"El texto llegó cambiado. Esperado: '{TextoDeIda}'");
            return 1;
        }

        if (clip.OriginId != Clip.OrigenDe(TextoDeIda))
        {
            Console.Error.WriteLine(
                "El origin_id no coincide: el anti-eco no funcionaría entre los dos lados");
            return 1;
        }

        // Y ahora en la otra dirección.
        if (!await servidor.EnviarClipAsync(Clip.DeTexto(TextoDeVuelta)).ConfigureAwait(false))
        {
            Console.Error.WriteLine("No se pudo enviar el clip de vuelta: no hay sesión viva");
            return 1;
        }

        Console.WriteLine($"ENVIADO: {TextoDeVuelta}");

        // Se da un momento al cliente para leerlo antes de cerrar el servidor.
        await Task.Delay(1500).ConfigureAwait(false);

        Console.WriteLine("RESULTADO: OK");
        return 0;
    }

    private static async Task<T?> EsperarAsync<T>(Task<T> tarea, TimeSpan plazo, string queSeEspera)
        where T : class
    {
        var terminada = await Task.WhenAny(tarea, Task.Delay(plazo)).ConfigureAwait(false);
        if (terminada != tarea)
        {
            Console.Error.WriteLine($"Se agotó la espera de {queSeEspera}");
            return null;
        }

        return await tarea.ConfigureAwait(false);
    }
}
