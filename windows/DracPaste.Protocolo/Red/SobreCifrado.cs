using System.Buffers.Binary;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Protocolo.Red;

/// <summary>
/// El sobre cifrado que envuelve cada mensaje de sesión
/// (<c>docs/protocol.md</c> §2.4 y §2.5).
///
/// El payload de un frame cifrado es <c>[contador uint64 BE][ciphertext || tag]</c>. El
/// contador viaja en claro porque el receptor lo necesita para reconstruir el nonce,
/// pero queda autenticado de forma implícita: si alguien lo cambia, el nonce cambia y el
/// tag deja de verificar.
///
/// <b>No es seguro para varios hilos</b>: cada dirección de una conexión tiene el suyo y
/// lo usa un solo hilo.
/// </summary>
public sealed class SobreCifrado
{
    private readonly byte[] _clave;

    public SobreCifrado(byte[] clave)
    {
        ArgumentNullException.ThrowIfNull(clave);
        _clave = clave;
    }

    /// <summary>Contador de lo que se ha enviado por esta dirección.</summary>
    public long ContadorSalida { get; private set; }

    /// <summary>
    /// Último contador aceptado en esta dirección. Empieza en -1 porque el primer
    /// mensaje legítimo lleva el 0.
    /// </summary>
    public long UltimoContadorAceptado { get; private set; } = -1;

    /// <summary>Cifra un mensaje y consume un contador.</summary>
    public byte[] Sellar(byte[] textoPlano)
    {
        var contador = ContadorSalida;
        var nonce = Cripto.NonceDeContador(contador);
        var cifrado = Cripto.Cifrar(_clave, nonce, textoPlano);
        ContadorSalida = contador + 1;

        var salida = new byte[8 + cifrado.Length];
        BinaryPrimitives.WriteInt64BigEndian(salida, contador);
        Buffer.BlockCopy(cifrado, 0, salida, 8, cifrado.Length);
        return salida;
    }

    /// <summary>
    /// Descifra un sobre y verifica que no sea una repetición.
    /// </summary>
    /// <exception cref="ProtocoloException">
    /// Si el contador no avanza. Un contador repetido o hacia atrás es alguien
    /// reinyectando un mensaje anterior: el clip que ya se copió una vez volvería a
    /// aparecer en el portapapeles.
    /// </exception>
    public byte[] Abrir(byte[] sobre)
    {
        ArgumentNullException.ThrowIfNull(sobre);
        if (sobre.Length < 8 + Cripto.TamTag)
        {
            throw new ProtocoloException($"Sobre cifrado demasiado corto: {sobre.Length} bytes");
        }

        var contador = BinaryPrimitives.ReadInt64BigEndian(sobre);
        if (contador < 0)
        {
            throw new ProtocoloException("Contador de nonce negativo");
        }

        if (contador <= UltimoContadorAceptado)
        {
            throw new ProtocoloException(
                $"Contador repetido o retrocedido: {contador} tras {UltimoContadorAceptado}");
        }

        var nonce = Cripto.NonceDeContador(contador);
        var textoPlano = Cripto.Descifrar(_clave, nonce, sobre[8..]);
        UltimoContadorAceptado = contador;
        return textoPlano;
    }

    /// <summary>Borra la clave cuando la sesión termina.</summary>
    public void Limpiar() => Cripto.Limpiar(_clave);
}
