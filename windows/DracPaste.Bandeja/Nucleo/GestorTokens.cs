using System.Collections.Concurrent;
using DracPaste.Protocolo;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Los tokens efímeros del emparejamiento (<c>docs/protocol.md</c> §3.1).
///
/// El token es lo único que separa "emparejarse con este PC" de "estar en la misma
/// red": demuestra que quien lo presenta ha tenido delante la pantalla donde se mostró
/// el QR. De ahí las dos reglas que impone esta clase: caduca a los dos minutos y solo
/// vale una vez.
/// </summary>
public sealed class GestorTokens
{
    private readonly ConcurrentDictionary<string, DateTimeOffset> _vivos = new();
    private readonly Func<DateTimeOffset> _reloj;
    private readonly long _validezMs;

    public GestorTokens(Func<DateTimeOffset>? reloj = null, long validezMs = Protocolo.Protocolo.ValidezTokenMs)
    {
        _reloj = reloj ?? (() => DateTimeOffset.UtcNow);
        _validezMs = validezMs;
    }

    /// <summary>Crea un token nuevo para mostrar en un QR.</summary>
    public byte[] Emitir()
    {
        LimpiarCaducados();
        var token = Cripto.Aleatorio(16);
        _vivos[Hex.ToHex(token)] = _reloj().AddMilliseconds(_validezMs);
        return token;
    }

    /// <summary>
    /// Comprueba el token y lo invalida en el mismo paso.
    ///
    /// Que las dos cosas sean atómicas no es un detalle: si primero se comprobara y
    /// después se borrara, dos peticiones simultáneas podrían colarse con el mismo
    /// token.
    /// </summary>
    public bool Consumir(byte[] token)
    {
        LimpiarCaducados();

        if (!_vivos.TryRemove(Hex.ToHex(token), out var caducaEn))
        {
            return false;
        }

        return caducaEn > _reloj();
    }

    /// <summary>Invalida todos los tokens: se cierra la ventana de emparejamiento.</summary>
    public void Revocar() => _vivos.Clear();

    public int Vivos
    {
        get
        {
            LimpiarCaducados();
            return _vivos.Count;
        }
    }

    private void LimpiarCaducados()
    {
        var ahora = _reloj();
        foreach (var (clave, caducaEn) in _vivos)
        {
            if (caducaEn <= ahora)
            {
                _vivos.TryRemove(clave, out _);
            }
        }
    }
}
