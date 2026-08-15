using System.Net.Sockets;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using DracPaste.Protocolo.Sesion;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Una sesión ya autenticada con un móvil: el bucle que lee mensajes y el envío.
///
/// Los envíos van serializados con un semáforo porque el <c>SobreCifrado</c> saliente
/// lleva un contador y dos hilos escribiendo a la vez se lo saltarían: dos mensajes con
/// el mismo nonce arruinan el cifrado de la sesión entera.
/// </summary>
internal sealed class SesionActiva
{
    private readonly TcpClient _cliente;
    private readonly Stream _flujo;
    private readonly SesionEstablecida _sesion;
    private readonly SemaphoreSlim _turnoDeEscritura = new(1, 1);
    private readonly CancellationTokenSource _cierre = new();

    private long _ultimoPingEnviado;

    public SesionActiva(TcpClient cliente, Stream flujo, SesionEstablecida sesion, string nombre)
    {
        _cliente = cliente;
        _flujo = flujo;
        _sesion = sesion;
        Nombre = nombre;
    }

    public string Nombre { get; }

    /// <summary>Quién está al otro lado. Lo fija el handshake, así que es de fiar.</summary>
    public string DeviceIdRemoto => _sesion.DeviceIdRemoto;

    public bool Viva => !_cierre.IsCancellationRequested && _cliente.Connected;

    /// <summary>Lee mensajes hasta que la conexión muere.</summary>
    public async Task BucleAsync(Action<Clip>? alRecibirClip, CancellationToken ct)
    {
        using var enlazado = CancellationTokenSource.CreateLinkedTokenSource(ct, _cierre.Token);
        var token = enlazado.Token;

        var latido = Task.Run(() => LatidoAsync(token), CancellationToken.None);

        try
        {
            while (!token.IsCancellationRequested)
            {
                var frame = await Framing.LeerAsync(_flujo, token).ConfigureAwait(false);
                var mensaje = CodecMensajes.Decodificar(_sesion.Entrante.Abrir(frame));

                switch (mensaje)
                {
                    case Clip clip when clip.EsTexto():
                        alRecibirClip?.Invoke(clip);
                        break;

                    case Clip:
                        // Un tipo que esta versión no transporta (imágenes, en v2). No
                        // es un error: se ignora y la sesión sigue.
                        break;

                    case Ping ping:
                        await EnviarCrudoAsync(new Pong { Seq = ping.Seq }, token).ConfigureAwait(false);
                        break;

                    case Pong:
                        Interlocked.Exchange(ref _ultimoPingEnviado, 0);
                        break;

                    case Bye:
                        return;

                    case Unpair:
                        Desemparejado?.Invoke(_sesion.DeviceIdRemoto);
                        return;

                    default:
                        // Mensaje de una versión más nueva: se ignora.
                        break;
                }
            }
        }
        finally
        {
            await _cierre.CancelAsync().ConfigureAwait(false);
            try
            {
                await latido.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Es la forma normal de terminar.
            }
        }
    }

    /// <summary>Se dispara si el móvil manda UNPAIR.</summary>
    public event Action<string>? Desemparejado;

    public Task EnviarAsync(Mensaje mensaje, CancellationToken ct) => EnviarCrudoAsync(mensaje, ct);

    private async Task EnviarCrudoAsync(Mensaje mensaje, CancellationToken ct)
    {
        await _turnoDeEscritura.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var sobre = _sesion.Saliente.Sellar(CodecMensajes.Codificar(mensaje));
            await Framing.EscribirAsync(_flujo, sobre, ct).ConfigureAwait(false);
        }
        finally
        {
            _turnoDeEscritura.Release();
        }
    }

    /// <summary>
    /// PING cada 15 s. Sin PONG en 10 s, la conexión se da por muerta: un socket TCP
    /// puede quedarse "abierto" durante minutos después de que el móvil haya
    /// desaparecido de la red, y en ese tiempo los clips se perderían en silencio.
    /// </summary>
    private async Task LatidoAsync(CancellationToken ct)
    {
        var seq = 0L;
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(Protocolo.Protocolo.IntervaloPingMs, ct).ConfigureAwait(false);

            var pendiente = Interlocked.Read(ref _ultimoPingEnviado);
            if (pendiente != 0 &&
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - pendiente > Protocolo.Protocolo.TimeoutPongMs)
            {
                Cerrar();
                return;
            }

            try
            {
                Interlocked.Exchange(ref _ultimoPingEnviado, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                await EnviarCrudoAsync(new Ping { Seq = ++seq }, ct).ConfigureAwait(false);
            }
            catch (Exception)
            {
                Cerrar();
                return;
            }
        }
    }

    public void Cerrar()
    {
        try
        {
            _cierre.Cancel();
        }
        catch (ObjectDisposedException)
        {
            return;
        }

        _sesion.Limpiar();
        _cliente.Dispose();
    }
}
