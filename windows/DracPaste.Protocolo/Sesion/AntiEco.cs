namespace DracPaste.Protocolo.Sesion;

/// <summary>
/// Evita el bucle de eco entre los dos portapapeles (<c>docs/protocol.md</c> §6).
///
/// El problema que resuelve: el PC envía un clip, el móvil lo escribe en su portapapeles,
/// el listener del móvil lo detecta como un cambio y lo devuelve al PC, que lo escribe en
/// el suyo, y así indefinidamente. Sin esto, copiar una vez deja los dos dispositivos
/// dándose el mismo texto para siempre.
///
/// La regla es la misma en los dos lados: antes de escribir un clip recibido se anota su
/// <c>origin_id</c>; si el cambio local que llega después tiene ese mismo
/// <c>origin_id</c>, es el eco de lo que se acaba de escribir y no se reenvía.
///
/// La marca <b>caduca</b>, y ese detalle importa: si no caducara, un usuario que vuelve a
/// copiar el mismo texto a mano media hora después vería que no se sincroniza y no
/// entendería por qué.
///
/// En Windows hay además una segunda barrera independiente: el formato de portapapeles
/// propio <c>DracPasteOrigin</c>. Dos mecanismos distintos porque el fallo de uno solo
/// produce un bucle infinito visible para el usuario.
/// </summary>
public sealed class AntiEco
{
    private readonly long _ventanaMs;
    private readonly Func<long> _reloj;

    private string? _ultimoOrigen;
    private long _marcadoEn;

    public AntiEco(long ventanaMs = Protocolo.VentanaAntiEcoMs, Func<long>? reloj = null)
    {
        _ventanaMs = ventanaMs;
        _reloj = reloj ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    /// <summary>Se llama justo antes de escribir en el portapapeles un clip recibido.</summary>
    public void MarcarRecibido(string originId)
    {
        _ultimoOrigen = originId;
        _marcadoEn = _reloj();
    }

    /// <summary>
    /// ¿Hay que reenviar este cambio del portapapeles local?
    ///
    /// Consume la marca cuando reconoce el eco: si el usuario copia dos veces seguidas el
    /// mismo texto a mano, la segunda sí viaja.
    /// </summary>
    public bool DebeReenviar(string originId)
    {
        var marcado = _ultimoOrigen;
        if (marcado is null)
        {
            return true;
        }

        if (_reloj() - _marcadoEn > _ventanaMs)
        {
            _ultimoOrigen = null;
            return true;
        }

        if (marcado == originId)
        {
            _ultimoOrigen = null;
            return false;
        }

        return true;
    }

    /// <summary>Al desconectar o desemparejar, la marca deja de tener sentido.</summary>
    public void Olvidar()
    {
        _ultimoOrigen = null;
        _marcadoEn = 0;
    }
}
