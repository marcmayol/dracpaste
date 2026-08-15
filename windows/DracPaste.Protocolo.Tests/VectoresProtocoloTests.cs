using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Seguridad;
using Xunit;

namespace DracPaste.Protocolo.Tests;

/// <summary>
/// Vectores de prueba de <c>docs/protocol.md</c> §7.
///
/// Este fichero tiene un gemelo en Kotlin (<c>VectoresProtocoloTest</c>) con <b>las mismas
/// constantes</b>. Es lo que sustituye a "conectar un móvil y ver si se entienden": una
/// implementación usa libsodium y la otra Bouncy Castle, y si alguna se desviara del
/// protocolo, uno de los dos ficheros se pondría en rojo sin que haga falta hardware
/// (<c>docs/decisions.md</c> D-003).
///
/// Si cambia un valor de aquí, cambia el protocolo: primero el documento, después los dos
/// ficheros de test.
/// </summary>
public class VectoresProtocoloTests
{
    // Claves de prueba del RFC 7748 §6.1. NO usar fuera de los tests.
    private static readonly byte[] PrivMovil =
        Hex.FromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");

    private static readonly byte[] PrivPc =
        Hex.FromHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");

    private static readonly byte[] PubMovil =
        Hex.FromHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");

    private static readonly byte[] PubPc =
        Hex.FromHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");

    private static readonly byte[] RetoMovil = Hex.FromHex("000102030405060708090a0b0c0d0e0f");
    private static readonly byte[] RetoPc = Hex.FromHex("101112131415161718191a1b1c1d1e1f");

    private const string VectorClavePar =
        "7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74";

    private const string VectorClaveM2P =
        "f0dbcb2507a2f78763fb7fda468ffc6a9fc8a55630153130d0725f5ac54d66f3";

    private const string VectorClaveP2M =
        "bd975ac0e20687bfa1dd130670c6659a2a1f8854fa1c924870f5e482814a4715";

    private const string VectorCifradoHola = "678e67f72a09b0970f17bb20686f7545b9f5b1bb";
    private const string VectorHuella = "9962-5B51";
    private const string VectorRetoEmparejamientoMovil = "5af8673472d05d3ccd761485d419b651";
    private const string VectorRetoEmparejamientoPc = "6bbd531bbad346bd2162feb25261c2e2";
    private const string VectorOriginIdHola = "b221d9dbb083a7f33428d7c2a3c3198a";

    [Fact]
    public void LasPublicasSalenDeLasPrivadasSegunRfc7748()
    {
        Assert.Equal(PubMovil, Cripto.ClavePublicaDe(PrivMovil));
        Assert.Equal(PubPc, Cripto.ClavePublicaDe(PrivPc));
    }

    [Fact]
    public void LaClaveDeParEsLaMismaEnElMovilYEnElPc()
    {
        var desdeMovil = Derivacion.ClavePar(PrivMovil, PubPc);
        var desdePc = Derivacion.ClavePar(PrivPc, PubMovil);

        Assert.Equal(desdeMovil, desdePc);
        Assert.Equal(VectorClavePar, Hex.ToHex(desdeMovil));
    }

    [Fact]
    public void LasClavesDeSesionCoincidenConElVectorFijado()
    {
        var claves = Derivacion.ClavesDeSesion(Hex.FromHex(VectorClavePar), RetoMovil, RetoPc);

        Assert.Equal(VectorClaveM2P, Hex.ToHex(claves.MovilAPc));
        Assert.Equal(VectorClaveP2M, Hex.ToHex(claves.PcAMovil));
    }

    [Fact]
    public void LasDosDireccionesUsanClavesDistintas()
    {
        var claves = Derivacion.ClavesDeSesion(Hex.FromHex(VectorClavePar), RetoMovil, RetoPc);

        Assert.False(
            claves.MovilAPc.AsSpan().SequenceEqual(claves.PcAMovil),
            "Si las dos direcciones compartieran clave, un mensaje del PC podría reinyectarse como si viniera del móvil");
    }

    [Fact]
    public void ElCifradoAutenticadoProduceElVectorFijado()
    {
        var clave = Hex.FromHex(VectorClaveM2P);
        var nonce = Cripto.NonceDeContador(0);

        var cifrado = Cripto.Cifrar(clave, nonce, "hola"u8.ToArray());
        Assert.Equal(VectorCifradoHola, Hex.ToHex(cifrado));

        // Y el viaje de vuelta.
        Assert.Equal("hola", System.Text.Encoding.UTF8.GetString(Cripto.Descifrar(clave, nonce, cifrado)));
    }

    [Fact]
    public void ElNonceSeConstruyeConElContadorEnBigEndian()
    {
        Assert.Equal("000000000000000000000000", Hex.ToHex(Cripto.NonceDeContador(0)));
        Assert.Equal("000000000000000000000001", Hex.ToHex(Cripto.NonceDeContador(1)));
        Assert.Equal("0000000000000000000000ff", Hex.ToHex(Cripto.NonceDeContador(255)));
        Assert.Equal("000000000000000100000000", Hex.ToHex(Cripto.NonceDeContador(4294967296L)));
    }

    [Fact]
    public void LaHuellaEsLaMismaSeMireDesdeDondeSeMire()
    {
        Assert.Equal(Derivacion.Huella(PubMovil, PubPc), Derivacion.Huella(PubPc, PubMovil));
        Assert.Equal(VectorHuella, Derivacion.Huella(PubMovil, PubPc));
    }

    [Fact]
    public void LosRetosDeEmparejamientoSeDerivanDelToken()
    {
        var token = Hex.FromHex("0f0e0d0c0b0a09080706050403020100");
        var (delMovil, delPc) = Derivacion.RetosDeEmparejamiento(token);

        Assert.Equal(16, delMovil.Length);
        Assert.Equal(16, delPc.Length);
        Assert.Equal(VectorRetoEmparejamientoMovil, Hex.ToHex(delMovil));
        Assert.Equal(VectorRetoEmparejamientoPc, Hex.ToHex(delPc));
    }

    [Fact]
    public void ElOriginIdEsElMismoQueEnKotlin()
    {
        // Si los dos lados no calculan el mismo origin_id, el anti-eco no funciona y los
        // clips rebotan entre el móvil y el PC indefinidamente.
        Assert.Equal(VectorOriginIdHola, Clip.OrigenDe("hola"));
    }

    [Fact]
    public void UnaClaveDeOrdenPequenoSeRechaza()
    {
        // El ataque: mandar una pública que fuerza un secreto compartido de ceros, que
        // el atacante ya conoce. libsodium lo detecta y aquí se convierte en excepción.
        var todoCeros = new byte[32];

        Assert.Throws<ClaveInvalidaException>(() => Derivacion.ClavePar(PrivMovil, todoCeros));
    }

    [Fact]
    public void ElSecretoNoDependeDeQuienLoCalcula()
    {
        // Recorrido completo del emparejamiento con los dos lados por separado: si el
        // orden de las claves públicas importara, estas dos líneas darían distinto.
        var token = Cripto.Aleatorio(16);
        var (retoM, retoP) = Derivacion.RetosDeEmparejamiento(token);

        var enElPc = Derivacion.ClavesDeSesion(Derivacion.ClavePar(PrivPc, PubMovil), retoM, retoP);
        var enElMovil = Derivacion.ClavesDeSesion(Derivacion.ClavePar(PrivMovil, PubPc), retoM, retoP);

        Assert.Equal(enElPc.MovilAPc, enElMovil.MovilAPc);
        Assert.Equal(enElPc.PcAMovil, enElMovil.PcAMovil);
    }
}
