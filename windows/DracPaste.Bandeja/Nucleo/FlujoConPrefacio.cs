namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Un flujo que primero entrega unos bytes que ya se habían leído y luego sigue con el
/// original.
///
/// Hace falta porque el servidor tiene que mirar el primer frame para saber si la
/// conexión es un emparejamiento o una sesión, y al mirarlo lo consume. En vez de pasar
/// ese frame por parámetro y complicar la firma de <c>Handshake</c> y
/// <c>Emparejamiento</c> —que quedarían con un caso especial solo para el primer
/// mensaje—, se devuelve al principio del flujo y esas clases leen como si nada hubiera
/// pasado.
/// </summary>
internal sealed class FlujoConPrefacio : Stream
{
    private readonly Stream _original;
    private readonly byte[] _prefacio;
    private int _consumidoDelPrefacio;

    /// <param name="payloadDelPrimerFrame">
    /// El <b>payload</b> del frame ya leído. Aquí se le vuelve a poner su cabecera de
    /// longitud, porque lo que se devuelve al flujo tiene que ser el frame completo.
    /// </param>
    public FlujoConPrefacio(Stream original, byte[] payloadDelPrimerFrame)
    {
        _original = original;
        _prefacio = new byte[4 + payloadDelPrimerFrame.Length];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(
            _prefacio, (uint)payloadDelPrimerFrame.Length);
        Buffer.BlockCopy(payloadDelPrimerFrame, 0, _prefacio, 4, payloadDelPrimerFrame.Length);
    }

    public override bool CanRead => true;

    public override bool CanSeek => false;

    public override bool CanWrite => _original.CanWrite;

    public override long Length => throw new NotSupportedException();

    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (_consumidoDelPrefacio < _prefacio.Length)
        {
            var n = Math.Min(count, _prefacio.Length - _consumidoDelPrefacio);
            Buffer.BlockCopy(_prefacio, _consumidoDelPrefacio, buffer, offset, n);
            _consumidoDelPrefacio += n;
            return n;
        }

        return _original.Read(buffer, offset, count);
    }

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        if (_consumidoDelPrefacio < _prefacio.Length)
        {
            var n = Math.Min(buffer.Length, _prefacio.Length - _consumidoDelPrefacio);
            _prefacio.AsSpan(_consumidoDelPrefacio, n).CopyTo(buffer.Span);
            _consumidoDelPrefacio += n;
            return ValueTask.FromResult(n);
        }

        return _original.ReadAsync(buffer, ct);
    }

    public override void Write(byte[] buffer, int offset, int count) =>
        _original.Write(buffer, offset, count);

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) =>
        _original.WriteAsync(buffer, ct);

    public override void Flush() => _original.Flush();

    public override Task FlushAsync(CancellationToken ct) => _original.FlushAsync(ct);

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        // El flujo original pertenece al TcpClient, que lo cierra él: aquí solo se
        // envuelve.
        base.Dispose(disposing);
    }
}
