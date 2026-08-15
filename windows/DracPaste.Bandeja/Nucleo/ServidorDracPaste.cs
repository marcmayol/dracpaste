using System.Net;
using System.Net.Sockets;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// El servidor TCP del PC. El móvil siempre es quien llama; este lado escucha.
///
/// Una conexión puede ser de dos clases y se distinguen por el primer frame: un
/// <c>PAIR_REQUEST</c> abre un emparejamiento, un <c>HELLO</c> abre una sesión. Como el
/// primer frame de ambos va en claro, se puede mirar sin descifrar nada.
/// </summary>
public sealed class ServidorDracPaste : IAsyncDisposable
{
    private readonly Identidad _identidad;
    private readonly RegistroEmparejados _registro;
    private readonly GestorTokens _tokens;
    private readonly CancellationTokenSource _parada = new();

    private TcpListener? _escucha;
    private Task? _bucleAceptacion;
    private SesionActiva? _sesion;
    private int _sesionesEstablecidas;

    public ServidorDracPaste(Identidad identidad, RegistroEmparejados registro, GestorTokens tokens)
    {
        _identidad = identidad;
        _registro = registro;
        _tokens = tokens;
    }

    /// <summary>Puerto en el que se está escuchando de verdad.</summary>
    public int Puerto { get; private set; }

    /// <summary>
    /// Con la sincronización pausada, los clips no salen ni entran. La conexión se
    /// mantiene a propósito: el usuario sigue viendo que el PC y el móvil se ven, y así
    /// distingue «lo he pausado yo» de «se ha roto algo».
    /// </summary>
    public bool EnPausa { get; set; }

    /// <summary>Se dispara cuando llega un clip del móvil.</summary>
    public event Action<Clip>? ClipRecibido;

    /// <summary>Cambios de estado, para reflejarlos en la bandeja.</summary>
    public event Action<string>? EstadoCambiado;

    /// <summary>Se dispara al completarse un emparejamiento.</summary>
    public event Action<DispositivoEmparejado>? DispositivoEmparejado;

    /// <summary>Se dispara cuando un móvil se desempareja desde su lado.</summary>
    public event Action<string>? DispositivoOlvidado;

    /// <summary>¿Hay un móvil conectado ahora mismo?</summary>
    public bool HayMovilConectado => _sesion is { Viva: true };

    /// <summary>
    /// Cuántas sesiones se han establecido desde que arrancó. Sirve para distinguir una
    /// reconexión de la sesión que ya había, cosa que <see cref="HayMovilConectado"/> no
    /// puede: durante el relevo las dos están vivas un instante.
    /// </summary>
    public int SesionesEstablecidas => Volatile.Read(ref _sesionesEstablecidas);

    public string? NombreMovilConectado => _sesion?.Nombre;

    /// <summary>
    /// Empieza a escuchar. Intenta el puerto preferido y, si está ocupado, deja que el
    /// sistema elija: el puerto real viaja por mDNS, así que el móvil lo encuentra igual.
    /// </summary>
    /// <param name="puertoPreferido">
    /// Se puede forzar otro puerto. Los tests pasan 0 para que cada uno reciba el suyo y
    /// no compitan por el puerto fijo cuando corren en paralelo.
    /// </param>
    public void Arrancar(int puertoPreferido = Protocolo.Protocolo.PuertoPreferido)
    {
        try
        {
            _escucha = new TcpListener(IPAddress.Any, puertoPreferido);
            _escucha.Start();
        }
        catch (SocketException)
        {
            _escucha = new TcpListener(IPAddress.Any, 0);
            _escucha.Start();
        }

        Puerto = ((IPEndPoint)_escucha.LocalEndpoint).Port;
        _bucleAceptacion = Task.Run(() => BucleAceptacionAsync(_parada.Token));
        EstadoCambiado?.Invoke("Esperando al móvil");
    }

    /// <summary>Envía un clip al móvil conectado. Si no hay ninguno, no hace nada.</summary>
    public async Task<bool> EnviarClipAsync(Clip clip, CancellationToken ct = default)
    {
        if (EnPausa)
        {
            return false;
        }

        var sesion = _sesion;
        if (sesion is null || !sesion.Viva)
        {
            return false;
        }

        try
        {
            await sesion.EnviarAsync(clip, ct).ConfigureAwait(false);
            return true;
        }
        catch (Exception)
        {
            // Si el envío falla, la conexión está muerta aunque el socket aún no lo
            // sepa. El móvil reconectará; aquí solo se limpia.
            sesion.Cerrar();
            _sesion = null;
            EstadoCambiado?.Invoke("Esperando al móvil");
            return false;
        }
    }

    /// <summary>
    /// Desempareja un móvil: le avisa si está conectado y borra su clave de este PC.
    ///
    /// El orden importa. Se manda el <c>UNPAIR</c> **antes** de olvidar el dispositivo,
    /// porque después de borrar la clave ya no habría forma de cifrarle nada. Si el móvil
    /// no está conectado, se le olvida igual: se encontrará con que este PC ya no le
    /// reconoce, que es justo lo que se busca.
    /// </summary>
    public async Task DesemparejarAsync(string deviceId, CancellationToken ct = default)
    {
        var sesion = _sesion;
        if (sesion is { Viva: true } && sesion.DeviceIdRemoto == deviceId)
        {
            try
            {
                await sesion.EnviarAsync(new Unpair(), ct).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Si no llega el aviso, el móvil se enterará al reconectar y fallar el
                // handshake. No es motivo para dejar de desemparejar por este lado.
            }

            sesion.Cerrar();
            _sesion = null;
        }

        _registro.Olvidar(deviceId);
        EstadoCambiado?.Invoke(_registro.Todos.Count == 0 ? "Sin emparejar" : "Esperando al móvil");
    }

    private async Task BucleAceptacionAsync(CancellationToken ct)
    {
        var escucha = _escucha!;
        while (!ct.IsCancellationRequested)
        {
            TcpClient cliente;
            try
            {
                cliente = await escucha.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                // El listener se ha parado mientras esperábamos una conexión. Es lo que
                // pasa al cerrar la app, y hay que tratarlo como un final normal: si se
                // dejara escapar, cerrar DracPaste terminaría con una excepción.
                return;
            }
            catch (SocketException)
            {
                continue;
            }

            // Cada conexión va por su cuenta: un móvil que se cuelga a mitad del
            // handshake no puede impedir que el de verdad conecte.
            _ = Task.Run(() => AtenderAsync(cliente, ct), CancellationToken.None);
        }
    }

    private async Task AtenderAsync(TcpClient cliente, CancellationToken ct)
    {
        try
        {
            cliente.NoDelay = true; // Un clip es pequeño: esperar a llenar el buffer solo añade latencia.
            var flujo = cliente.GetStream();

            var primerFrame = await Framing.LeerAsync(flujo, ct).ConfigureAwait(false);
            var mensaje = CodecMensajes.Decodificar(primerFrame);

            switch (mensaje)
            {
                case PairRequest peticion:
                    await AtenderEmparejamientoAsync(cliente, flujo, peticion, ct).ConfigureAwait(false);
                    break;

                case Hello hello:
                    await AtenderSesionAsync(cliente, flujo, hello, ct).ConfigureAwait(false);
                    break;

                default:
                    // Cualquier otra cosa como primer frame es alguien que no habla este
                    // protocolo. Se corta sin contestar.
                    cliente.Dispose();
                    break;
            }
        }
        catch (Exception)
        {
            cliente.Dispose();
        }
    }

    private async Task AtenderEmparejamientoAsync(
        TcpClient cliente,
        NetworkStream flujo,
        PairRequest peticion,
        CancellationToken ct)
    {
        using (cliente)
        {
            // El primer frame ya se consumió, así que se reinyecta para que
            // Emparejamiento vea el diálogo completo desde el principio.
            using var conElPrimerFrame = new FlujoConPrefacio(flujo, CodecMensajes.Codificar(peticion));

            var resultado = await Emparejamiento.AceptarAsync(
                conElPrimerFrame,
                _identidad.Privada,
                _identidad.DeviceId,
                _identidad.Nombre,
                _tokens.Consumir,
                ct).ConfigureAwait(false);

            var dispositivo = new DispositivoEmparejado
            {
                DeviceId = resultado.DeviceIdRemoto,
                Nombre = resultado.NombreRemoto,
                PublicaBase64 = Convert.ToBase64String(resultado.PublicaRemota),
                Huella = resultado.Huella,
                EmparejadoEn = DateTimeOffset.Now,
            };

            _registro.Guardar(dispositivo);
            DispositivoEmparejado?.Invoke(dispositivo);
            EstadoCambiado?.Invoke($"Emparejado con {dispositivo.Nombre}");
        }
    }

    private async Task AtenderSesionAsync(
        TcpClient cliente,
        NetworkStream flujo,
        Hello hello,
        CancellationToken ct)
    {
        using var conElPrimerFrame = new FlujoConPrefacio(flujo, CodecMensajes.Codificar(hello));

        var sesion = await Handshake.AceptarAsync(
            conElPrimerFrame,
            _identidad.DeviceId,
            id => _registro.ClaveParDe(id, _identidad),
            ct).ConfigureAwait(false);

        var dispositivo = _registro.Buscar(sesion.DeviceIdRemoto);
        var nombre = dispositivo?.Nombre ?? sesion.DeviceIdRemoto;

        // Si ya había una sesión, esta la sustituye: el móvil ha reconectado y la
        // anterior es un socket zombi que nadie va a cerrar desde el otro lado.
        _sesion?.Cerrar();
        var activa = new SesionActiva(cliente, conElPrimerFrame, sesion, nombre);

        // Si el desemparejamiento lo inicia el móvil, este PC tiene que borrar su clave
        // igual que si lo hubiera pedido el usuario aquí.
        activa.Desemparejado += id =>
        {
            _registro.Olvidar(id);
            DispositivoOlvidado?.Invoke(id);
        };

        _sesion = activa;
        Interlocked.Increment(ref _sesionesEstablecidas);
        EstadoCambiado?.Invoke($"Conectado con {nombre}");

        try
        {
            await activa.BucleAsync(ClipRecibido, ct).ConfigureAwait(false);
        }
        finally
        {
            if (ReferenceEquals(_sesion, activa))
            {
                _sesion = null;
                EstadoCambiado?.Invoke("Esperando al móvil");
            }

            activa.Cerrar();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _parada.CancelAsync().ConfigureAwait(false);
        _sesion?.Cerrar();
        _escucha?.Stop();

        if (_bucleAceptacion is not null)
        {
            try
            {
                await _bucleAceptacion.ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Cerrar no puede fallar. Lo que quede a medias en el bucle de aceptación
                // ya no importa: el listener está parado y las sesiones, cerradas.
            }
        }

        _parada.Dispose();
    }
}
