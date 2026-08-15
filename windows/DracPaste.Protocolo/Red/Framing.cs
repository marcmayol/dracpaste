using System.Buffers.Binary;

namespace DracPaste.Protocolo.Red;

/// <summary>
/// Framing del protocolo (<c>docs/protocol.md</c> §1): <c>[longitud uint32 BE][payload]</c>.
///
/// Un socket TCP entrega bytes, no mensajes: sin este envoltorio, dos clips copiados
/// seguidos pueden llegar pegados en la misma lectura o partidos entre dos.
/// </summary>
public static class Framing
{
    /// <summary>Escribe un frame completo. No cierra ni vacía el flujo.</summary>
    public static async Task EscribirAsync(Stream salida, byte[] payload, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(payload);
        if (payload.Length == 0)
        {
            throw new ProtocoloException("No se envían frames vacíos");
        }

        if (payload.Length > Protocolo.MaxFrameBytes)
        {
            throw new ProtocoloException(
                $"El frame ocupa {payload.Length} bytes y el máximo es {Protocolo.MaxFrameBytes}");
        }

        var cabecera = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(cabecera, (uint)payload.Length);
        await salida.WriteAsync(cabecera, ct).ConfigureAwait(false);
        await salida.WriteAsync(payload, ct).ConfigureAwait(false);
        await salida.FlushAsync(ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Lee un frame completo, esperando hasta tenerlo entero.
    ///
    /// La longitud se valida <b>antes</b> de reservar memoria: un <c>length</c> de 4 GB
    /// en la cabecera no puede convertirse en una petición de 4 GB de RAM. Cualquiera en
    /// la red puede abrir un socket y mandar esos cuatro bytes.
    /// </summary>
    public static async Task<byte[]> LeerAsync(Stream entrada, CancellationToken ct = default)
    {
        var cabecera = await LeerExactamenteAsync(entrada, 4, ct).ConfigureAwait(false);
        var longitud = BinaryPrimitives.ReadUInt32BigEndian(cabecera);

        if (longitud == 0)
        {
            throw new ProtocoloException("Longitud de frame inválida: 0");
        }

        if (longitud > Protocolo.MaxFrameBytes)
        {
            throw new ProtocoloException(
                $"El frame anunciado ocupa {longitud} bytes y el máximo es {Protocolo.MaxFrameBytes}");
        }

        return await LeerExactamenteAsync(entrada, (int)longitud, ct).ConfigureAwait(false);
    }

    private static async Task<byte[]> LeerExactamenteAsync(Stream entrada, int cuantos, CancellationToken ct)
    {
        var destino = new byte[cuantos];
        var leidos = 0;
        while (leidos < cuantos)
        {
            var n = await entrada.ReadAsync(destino.AsMemory(leidos, cuantos - leidos), ct).ConfigureAwait(false);
            if (n <= 0)
            {
                throw new EndOfStreamException($"La conexión se cerró tras {leidos} de {cuantos} bytes");
            }

            leidos += n;
        }

        return destino;
    }
}

/// <summary>
/// Algo que no encaja con <c>docs/protocol.md</c>. Siempre acaba en cerrar la conexión.
/// </summary>
public sealed class ProtocoloException : Exception
{
    public ProtocoloException(string mensaje, Exception? causa = null) : base(mensaje, causa) { }
}
