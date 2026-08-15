using DracPaste.Protocolo.Red;
using Xunit;

namespace DracPaste.Protocolo.Tests;

public class FramingTests
{
    [Fact]
    public async Task UnFrameEscritoSeVuelveALeerIgual()
    {
        var payload = "un clip cualquiera"u8.ToArray();
        using var buffer = new MemoryStream();
        await Framing.EscribirAsync(buffer, payload);
        buffer.Position = 0;

        Assert.Equal(payload, await Framing.LeerAsync(buffer));
    }

    [Fact]
    public async Task LaCabeceraSonCuatroBytesBigEndian()
    {
        using var buffer = new MemoryStream();
        await Framing.EscribirAsync(buffer, new byte[258]);
        var bytes = buffer.ToArray();

        Assert.Equal(0, bytes[0]);
        Assert.Equal(0, bytes[1]);
        Assert.Equal(1, bytes[2]);
        Assert.Equal(2, bytes[3]);
        Assert.Equal(4 + 258, bytes.Length);
    }

    [Fact]
    public async Task DosFramesSeguidosSeSeparanBien()
    {
        // El caso que justifica que haya framing: TCP entrega bytes, no mensajes, y dos
        // clips copiados seguidos pueden llegar pegados en la misma lectura.
        using var buffer = new MemoryStream();
        await Framing.EscribirAsync(buffer, "primero"u8.ToArray());
        await Framing.EscribirAsync(buffer, "segundo"u8.ToArray());
        buffer.Position = 0;

        Assert.Equal("primero", System.Text.Encoding.UTF8.GetString(await Framing.LeerAsync(buffer)));
        Assert.Equal("segundo", System.Text.Encoding.UTF8.GetString(await Framing.LeerAsync(buffer)));
    }

    [Fact]
    public async Task UnFramePartidoEnTrozosSeReensambla()
    {
        var payload = new byte[5000];
        for (var i = 0; i < payload.Length; i++)
        {
            payload[i] = (byte)(i % 251);
        }

        using var origen = new MemoryStream();
        await Framing.EscribirAsync(origen, payload);
        using var tacaneo = new FlujoTacano(origen.ToArray(), bytesPorLectura: 3);

        Assert.Equal(payload, await Framing.LeerAsync(tacaneo));
    }

    [Fact]
    public async Task UnaLongitudDesmedidaSeRechazaSinReservarMemoria()
    {
        // Cualquiera en la red puede abrir un socket y mandar estos cuatro bytes: si se
        // creyeran, serían casi 2 GB de reserva de memoria.
        using var entrada = new MemoryStream(new byte[] { 0x7F, 0xFF, 0xFF, 0xFF });

        var e = await Assert.ThrowsAsync<ProtocoloException>(() => Framing.LeerAsync(entrada));
        Assert.Contains("máximo", e.Message);
    }

    [Fact]
    public async Task UnaLongitudDeCeroSeRechaza()
    {
        using var entrada = new MemoryStream(new byte[] { 0, 0, 0, 0 });

        var e = await Assert.ThrowsAsync<ProtocoloException>(() => Framing.LeerAsync(entrada));
        Assert.Contains("inválida", e.Message);
    }

    [Fact]
    public async Task NoSeEscribenFramesVacios()
    {
        using var buffer = new MemoryStream();

        var e = await Assert.ThrowsAsync<ProtocoloException>(
            () => Framing.EscribirAsync(buffer, Array.Empty<byte>()));
        Assert.Contains("vacíos", e.Message);
    }

    [Fact]
    public async Task NoSeEscribenFramesPorEncimaDelMaximo()
    {
        using var buffer = new MemoryStream();

        var e = await Assert.ThrowsAsync<ProtocoloException>(
            () => Framing.EscribirAsync(buffer, new byte[Protocolo.MaxFrameBytes + 1]));
        Assert.Contains("máximo", e.Message);
    }

    [Fact]
    public async Task UnaConexionCortadaAMediasSeDistingueDeUnErrorDeProtocolo()
    {
        // El otro extremo anuncia 100 bytes y se va tras 10. Eso es una desconexión, no
        // un mensaje mal formado: quien llama tiene que poder reconectar en vez de
        // desemparejar.
        using var origen = new MemoryStream();
        await Framing.EscribirAsync(origen, new byte[100]);
        using var truncado = new MemoryStream(origen.ToArray()[..14]);

        var e = await Assert.ThrowsAsync<EndOfStreamException>(() => Framing.LeerAsync(truncado));
        Assert.Contains("cerró", e.Message);
    }

    /// <summary>Un flujo que entrega los bytes a cuentagotas, como haría una red lenta.</summary>
    private sealed class FlujoTacano : Stream
    {
        private readonly byte[] _datos;
        private readonly int _bytesPorLectura;
        private int _pos;

        public FlujoTacano(byte[] datos, int bytesPorLectura)
        {
            _datos = datos;
            _bytesPorLectura = bytesPorLectura;
        }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => _datos.Length;

        public override long Position { get => _pos; set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_pos >= _datos.Length)
            {
                return 0;
            }

            var n = Math.Min(Math.Min(_bytesPorLectura, count), _datos.Length - _pos);
            Array.Copy(_datos, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }

        public override void Flush() { }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
