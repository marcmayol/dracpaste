using DracPaste.Bandeja.Nucleo;
using DracPaste.Protocolo.Seguridad;

namespace DracPaste.Bandeja.Tests;

public class IdentidadTests : IDisposable
{
    private readonly string _carpeta = Path.Combine(
        Path.GetTempPath(),
        "dracpaste-tests-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void LaPrimeraVezSeGeneraYSeGuarda()
    {
        var identidad = Identidad.CargarOCrear(_carpeta);

        Assert.Equal(32, identidad.Privada.Length);
        Assert.Equal(32, identidad.Publica.Length);
        Assert.Equal(32, identidad.DeviceId.Length); // 16 bytes en hex
        Assert.True(File.Exists(Path.Combine(_carpeta, "identidad.json")));
    }

    [Fact]
    public void LaSegundaVezSeCargaLaMisma()
    {
        // Si cambiara entre arranques, todos los móviles emparejados dejarían de
        // reconocer a este PC.
        var primera = Identidad.CargarOCrear(_carpeta);
        var segunda = Identidad.CargarOCrear(_carpeta);

        Assert.Equal(primera.DeviceId, segunda.DeviceId);
        Assert.Equal(primera.Privada, segunda.Privada);
        Assert.Equal(primera.Publica, segunda.Publica);
    }

    [Fact]
    public void LaPrivadaNoSeGuardaEnClaro()
    {
        var identidad = Identidad.CargarOCrear(_carpeta);
        var contenido = File.ReadAllText(Path.Combine(_carpeta, "identidad.json"));

        Assert.DoesNotContain(Convert.ToBase64String(identidad.Privada), contenido);
        Assert.DoesNotContain(Hex.ToHex(identidad.Privada), contenido);
        // La pública sí está: no es un secreto y hace falta para verificar la privada.
        Assert.Contains(Convert.ToBase64String(identidad.Publica), contenido);
    }

    [Fact]
    public void LaPublicaCorrespondeALaPrivada()
    {
        var identidad = Identidad.CargarOCrear(_carpeta);

        Assert.Equal(identidad.Publica, Cripto.ClavePublicaDe(identidad.Privada));
    }

    [Fact]
    public void ElDeviceIdNoDerivaDelNombreDelEquipo()
    {
        // Un identificador derivado del hardware o del nombre del equipo sería
        // persistente y rastreable, que es justo lo contrario de lo que busca la app.
        var a = Identidad.Generar();
        var b = Identidad.Generar();

        Assert.NotEqual(a.DeviceId, b.DeviceId);
        Assert.DoesNotContain(Environment.MachineName.ToLowerInvariant(), a.DeviceId);
    }

    [Fact]
    public void UnFicheroCorruptoNoImpideArrancar()
    {
        // Sin la privada no se puede recuperar nada, pero la app tiene que abrir igual:
        // se aparta el fichero y se genera una identidad nueva.
        Identidad.CargarOCrear(_carpeta);
        var ruta = Path.Combine(_carpeta, "identidad.json");
        File.WriteAllText(ruta, "esto no es json");

        var nueva = Identidad.CargarOCrear(_carpeta);

        Assert.Equal(32, nueva.Privada.Length);
        Assert.Single(Directory.GetFiles(_carpeta, "identidad.json.ilegible-*"));
    }

    [Fact]
    public void UnaPublicaManipuladaSeDetecta()
    {
        // Si alguien cambiara la pública del fichero, los emparejamientos fallarían más
        // tarde con un error incomprensible. Mejor detectarlo al cargar.
        Identidad.CargarOCrear(_carpeta);
        var ruta = Path.Combine(_carpeta, "identidad.json");
        var otraPublica = Convert.ToBase64String(Cripto.GenerarParDeClaves().Publica);
        var contenido = System.Text.RegularExpressions.Regex.Replace(
            File.ReadAllText(ruta),
            "\"publica\":\\s*\"[^\"]+\"",
            $"\"publica\": \"{otraPublica}\"");
        File.WriteAllText(ruta, contenido);

        Identidad.CargarOCrear(_carpeta);

        Assert.Single(Directory.GetFiles(_carpeta, "identidad.json.ilegible-*"));
    }

    [Fact]
    public void LaHuellaEsLaMismaQueVeraElMovil()
    {
        var pc = Identidad.Generar();
        var movil = Cripto.GenerarParDeClaves();

        Assert.Equal(Derivacion.Huella(movil.Publica, pc.Publica), pc.HuellaCon(movil.Publica));
    }

    public void Dispose()
    {
        if (Directory.Exists(_carpeta))
        {
            Directory.Delete(_carpeta, recursive: true);
        }
    }
}
