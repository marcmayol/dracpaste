using System.Text;
using DracPaste.Protocolo.Mensajes;
using DracPaste.Protocolo.Red;
using Xunit;

namespace DracPaste.Protocolo.Tests;

public class MensajesTests
{
    [Fact]
    public void UnClipVaYVuelveConSuTextoIntacto()
    {
        var clip = Clip.DeTexto("hola mundo", 1_755_100_000_000);
        var decodificado = (Clip)CodecMensajes.Decodificar(CodecMensajes.Codificar(clip));

        Assert.Equal("hola mundo", decodificado.Texto());
        Assert.Equal(Protocolo.TipoTexto, decodificado.Type);
        Assert.Equal(1_755_100_000_000, decodificado.TimestampMs);
        Assert.Equal(clip.OriginId, decodificado.OriginId);
    }

    [Fact]
    public void LosAcentosYEmojisSobrevivenAlViaje()
    {
        // El caso real: se copia un texto en catalán o con un emoji y llega mutilado
        // porque alguien asumió ASCII en algún punto del camino.
        const string original = "Això és una prova — ñ, ü, 汉字 y 🐉";
        var decodificado = (Clip)CodecMensajes.Decodificar(CodecMensajes.Codificar(Clip.DeTexto(original)));

        Assert.Equal(original, decodificado.Texto());
    }

    [Fact]
    public void ElOriginIdDependeSoloDelContenido()
    {
        var a = Clip.DeTexto("mismo texto", 1);
        var b = Clip.DeTexto("mismo texto", 999_999);
        var c = Clip.DeTexto("otro texto", 1);

        Assert.Equal(a.OriginId, b.OriginId);
        Assert.NotEqual(a.OriginId, c.OriginId);
        Assert.Equal(32, a.OriginId.Length); // 16 bytes en hex
    }

    [Fact]
    public void NoSeCreanClipsVacios()
    {
        var e = Assert.Throws<ProtocoloException>(() => Clip.DeTexto(string.Empty));
        Assert.Contains("vacíos", e.Message);
    }

    [Fact]
    public void NoSeCreanClipsPorEncimaDelMaximo()
    {
        var e = Assert.Throws<ProtocoloException>(
            () => Clip.DeTexto(new string('a', Protocolo.MaxClipBytes + 1)));
        Assert.Contains("máximo", e.Message);
    }

    [Fact]
    public void ElMaximoSeMideEnBytesNoEnCaracteres()
    {
        // Un emoji ocupa cuatro bytes: contar caracteres dejaría pasar clips de cuatro
        // veces el máximo.
        var justoPorEncima = string.Concat(Enumerable.Repeat("🐉", (Protocolo.MaxClipBytes / 4) + 1));

        var e = Assert.Throws<ProtocoloException>(() => Clip.DeTexto(justoPorEncima));
        Assert.Contains("máximo", e.Message);
    }

    [Fact]
    public void LosMensajesDelHandshakeVanYVuelven()
    {
        var hello = new Hello { DeviceId = "aabb", Challenge = "Y2hhbGxlbmdl" };
        Assert.Equal(hello, CodecMensajes.Decodificar(CodecMensajes.Codificar(hello)));

        var serverHello = new ServerHello { DeviceId = "ccdd", Challenge = "b3Rybw==" };
        Assert.Equal(serverHello, CodecMensajes.Decodificar(CodecMensajes.Codificar(serverHello)));

        var auth = new Auth { Echo = "ZWNv" };
        Assert.Equal(auth, CodecMensajes.Decodificar(CodecMensajes.Codificar(auth)));

        var authOk = new AuthOk { Echo = "ZWNv" };
        Assert.Equal(authOk, CodecMensajes.Decodificar(CodecMensajes.Codificar(authOk)));
    }

    [Fact]
    public void LosMensajesDeEmparejamientoVanYVuelven()
    {
        var peticion = new PairRequest { Pk = "cGs=", DeviceId = "0011", Name = "Pixel", Token = "dG9rZW4=" };
        Assert.Equal(peticion, CodecMensajes.Decodificar(CodecMensajes.Codificar(peticion)));

        var confirmacion = new PairConfirm { DeviceId = "2233", Name = "PC", Fingerprint = "A3F2-9C71" };
        Assert.Equal(confirmacion, CodecMensajes.Decodificar(CodecMensajes.Codificar(confirmacion)));

        var ack = new PairAck { Fingerprint = "A3F2-9C71" };
        Assert.Equal(ack, CodecMensajes.Decodificar(CodecMensajes.Codificar(ack)));
    }

    [Fact]
    public void PingYPongConservanLaSecuencia()
    {
        var ping = new Ping { Seq = 42 };
        Assert.Equal(ping, CodecMensajes.Decodificar(CodecMensajes.Codificar(ping)));

        var pong = new Pong { Seq = 42 };
        Assert.Equal(pong, CodecMensajes.Decodificar(CodecMensajes.Codificar(pong)));
    }

    [Fact]
    public void UnTipoDesconocidoNoRompeLaSesion()
    {
        // Una versión futura hablando de imágenes no puede tirar la conexión de un
        // cliente v1: se ignora y se sigue.
        var mensaje = CodecMensajes.Decodificar("""{"t":"IMAGEN","datos":"..."}"""u8.ToArray());

        Assert.IsType<MensajeDesconocido>(mensaje);
        Assert.Equal("IMAGEN", mensaje.T);
    }

    [Fact]
    public void UnCampoNuevoEnUnTipoConocidoNoRompeNada()
    {
        var json = """{"t":"PING","seq":7,"campo_del_futuro":true}"""u8.ToArray();

        Assert.Equal(new Ping { Seq = 7 }, CodecMensajes.Decodificar(json));
    }

    [Fact]
    public void UnClipDeTipoNoSoportadoSeReconoceComoTal()
    {
        var json = """{"t":"CLIP","type":"image/png","payload":"AAAA","timestamp_ms":1,"origin_id":"ab"}"""u8.ToArray();
        var clip = (Clip)CodecMensajes.Decodificar(json);

        Assert.False(clip.EsTexto());
    }

    [Fact]
    public void UnJsonInvalidoSeRechazaComoErrorDeProtocolo()
    {
        var e = Assert.Throws<ProtocoloException>(
            () => CodecMensajes.Decodificar("esto no es json"u8.ToArray()));
        Assert.Contains("JSON", e.Message);
    }

    [Fact]
    public void UnMensajeSinCampoTSeRechaza()
    {
        var e = Assert.Throws<ProtocoloException>(() => CodecMensajes.Decodificar("""{"seq":1}"""u8.ToArray()));
        Assert.Contains("'t'", e.Message);
    }

    [Fact]
    public void ElCampoTViajaConElNombreExactoDelProtocolo()
    {
        var json = Encoding.UTF8.GetString(CodecMensajes.Codificar(new Ping { Seq = 1 }));

        Assert.Contains("\"t\":\"PING\"", json);
        Assert.Contains("\"seq\":1", json);
    }

    [Fact]
    public void LosNombresConGuionBajoSeRespetanEnElCable()
    {
        // timestamp_ms y origin_id se llaman así en docs/protocol.md; si C# los
        // serializara en PascalCase, el lado Kotlin no los encontraría.
        var json = Encoding.UTF8.GetString(CodecMensajes.Codificar(Clip.DeTexto("x", 5)));
        Assert.Contains("\"timestamp_ms\":5", json);
        Assert.Contains("\"origin_id\":", json);

        var jsonHello = Encoding.UTF8.GetString(
            CodecMensajes.Codificar(new Hello { DeviceId = "ab", Challenge = "cd" }));
        Assert.Contains("\"device_id\":\"ab\"", jsonHello);
    }

    [Fact]
    public void UnMensajeDeKotlinSeDecodificaEnCSharp()
    {
        // El JSON exacto que produce el gemelo Kotlin. Si alguno de los dos cambiara el
        // nombre de un campo, esto se pondría en rojo.
        var deKotlin = """{"t":"CLIP","type":"text/plain","payload":"aG9sYQ==","timestamp_ms":1755100000000,"origin_id":"b221d9dbb083a7f33428d7c2a3c3198a"}"""u8.ToArray();
        var clip = (Clip)CodecMensajes.Decodificar(deKotlin);

        Assert.Equal("hola", clip.Texto());
        Assert.Equal(Clip.OrigenDe("hola"), clip.OriginId);
        Assert.True(clip.EsTexto());
    }
}
