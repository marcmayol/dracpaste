using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Bandeja.Tests;

public class RegistroEmparejadosTests : IDisposable
{
    private readonly string _carpeta = Path.Combine(
        Path.GetTempPath(),
        "dracpaste-tests-" + Guid.NewGuid().ToString("N"));

    private static DispositivoEmparejado Movil(string deviceId, byte[] publica, string nombre = "Pixel") => new()
    {
        DeviceId = deviceId,
        Nombre = nombre,
        PublicaBase64 = Convert.ToBase64String(publica),
        Huella = "A3F2-9C71",
        EmparejadoEn = DateTimeOffset.Now,
    };

    [Fact]
    public void LoGuardadoSobreviveAlReinicio()
    {
        var publica = Cripto.GenerarParDeClaves().Publica;
        RegistroEmparejados.Cargar(_carpeta).Guardar(Movil("aabb", publica));

        var recargado = RegistroEmparejados.Cargar(_carpeta);

        Assert.Single(recargado.Todos);
        Assert.Equal("Pixel", recargado.Buscar("aabb")!.Nombre);
    }

    [Fact]
    public void SeAdmitenVariosMoviles()
    {
        // El protocolo es por pareja: cada dispositivo tiene su clave y desemparejar a
        // uno no puede afectar a los demás.
        var registro = RegistroEmparejados.Cargar(_carpeta);
        registro.Guardar(Movil("aaaa", Cripto.GenerarParDeClaves().Publica, "Pixel"));
        registro.Guardar(Movil("bbbb", Cripto.GenerarParDeClaves().Publica, "Tablet"));

        registro.Olvidar("aaaa");

        Assert.Single(registro.Todos);
        Assert.Null(registro.Buscar("aaaa"));
        Assert.NotNull(registro.Buscar("bbbb"));
    }

    [Fact]
    public void OlvidarLoQueNoEstaNoRompeNada()
    {
        Assert.False(RegistroEmparejados.Cargar(_carpeta).Olvidar("no-existe"));
    }

    [Fact]
    public void LaClaveDeParSeRecalculaDesdeLoGuardado()
    {
        // No se guarda ningún secreto: la clave de par sale de la privada del PC y la
        // pública del móvil cada vez que hace falta.
        var pc = Identidad.Generar();
        var movil = Cripto.GenerarParDeClaves();
        var registro = RegistroEmparejados.Cargar(_carpeta);
        registro.Guardar(Movil("aabb", movil.Publica));

        var desdeElRegistro = registro.ClaveParDe("aabb", pc);
        var enElMovil = Derivacion.ClavePar(movil.Privada, pc.Publica);

        Assert.Equal(enElMovil, desdeElRegistro);
    }

    [Fact]
    public void UnDesconocidoNoTieneClave()
    {
        Assert.Null(RegistroEmparejados.Cargar(_carpeta).ClaveParDe("nadie", Identidad.Generar()));
    }

    [Fact]
    public void ElFicheroNoContieneSecretos()
    {
        var registro = RegistroEmparejados.Cargar(_carpeta);
        registro.Guardar(Movil("aabb", Cripto.GenerarParDeClaves().Publica));
        var contenido = File.ReadAllText(Path.Combine(_carpeta, "emparejados.json"));

        // Solo públicas y metadatos: quien lea este fichero no puede descifrar nada.
        Assert.Contains("publica", contenido);
        Assert.DoesNotContain("privada", contenido);
    }

    [Fact]
    public void UnRegistroCorruptoNoImpideArrancar()
    {
        File.WriteAllText(Path.Combine(_carpeta, "emparejados.json"), "{no es json");

        var registro = RegistroEmparejados.Cargar(_carpeta);

        Assert.Empty(registro.Todos);
        Assert.Single(Directory.GetFiles(_carpeta, "emparejados.json.ilegible-*"));
    }

    public RegistroEmparejadosTests() => Directory.CreateDirectory(_carpeta);

    public void Dispose()
    {
        if (Directory.Exists(_carpeta))
        {
            Directory.Delete(_carpeta, recursive: true);
        }
    }
}

public class GestorTokensTests
{
    [Fact]
    public void UnTokenReciennEmitidoVale()
    {
        var gestor = new GestorTokens();
        var token = gestor.Emitir();

        Assert.True(gestor.Consumir(token));
    }

    [Fact]
    public void UnTokenSoloValeUnaVez()
    {
        // Es lo que impide que alguien que vio el QR por encima del hombro empareje su
        // propio móvil después del legítimo.
        var gestor = new GestorTokens();
        var token = gestor.Emitir();

        Assert.True(gestor.Consumir(token));
        Assert.False(gestor.Consumir(token));
    }

    [Fact]
    public void UnTokenInventadoNoVale()
    {
        var gestor = new GestorTokens();
        gestor.Emitir();

        Assert.False(gestor.Consumir(Cripto.Aleatorio(16)));
    }

    [Fact]
    public void UnTokenCaducaALosDosMinutos()
    {
        var ahora = DateTimeOffset.UtcNow;
        var gestor = new GestorTokens(reloj: () => ahora, validezMs: 120_000);
        var token = gestor.Emitir();

        ahora = ahora.AddMilliseconds(120_001);

        Assert.False(gestor.Consumir(token));
    }

    [Fact]
    public void JustoAntesDeCaducarTodaviaVale()
    {
        var ahora = DateTimeOffset.UtcNow;
        var gestor = new GestorTokens(reloj: () => ahora, validezMs: 120_000);
        var token = gestor.Emitir();

        ahora = ahora.AddMilliseconds(119_000);

        Assert.True(gestor.Consumir(token));
    }

    [Fact]
    public void CerrarLaVentanaRevocaLosTokensPendientes()
    {
        var gestor = new GestorTokens();
        var token = gestor.Emitir();

        gestor.Revocar();

        Assert.False(gestor.Consumir(token));
        Assert.Equal(0, gestor.Vivos);
    }

    [Fact]
    public void VariosTokensConvivenSinPisarse()
    {
        // El usuario puede abrir la ventana de emparejar dos veces seguidas.
        var gestor = new GestorTokens();
        var primero = gestor.Emitir();
        var segundo = gestor.Emitir();

        Assert.True(gestor.Consumir(segundo));
        Assert.True(gestor.Consumir(primero));
    }

    [Fact]
    public void SoloUnoDeVariosIntentosSimultaneosGana()
    {
        // Dos peticiones a la vez con el mismo token: si comprobar y consumir no fueran
        // el mismo paso, las dos se colarían.
        var gestor = new GestorTokens();
        var token = gestor.Emitir();
        var exitos = 0;

        Parallel.For(0, 32, _ =>
        {
            if (gestor.Consumir(token))
            {
                Interlocked.Increment(ref exitos);
            }
        });

        Assert.Equal(1, exitos);
    }
}
