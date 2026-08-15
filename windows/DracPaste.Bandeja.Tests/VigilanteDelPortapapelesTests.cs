using DracPaste.Bandeja.Nucleo;

namespace DracPaste.Bandeja.Tests;

/// <summary>
/// El vigilante del portapapeles con el portapapeles <b>real</b> de Windows.
///
/// <b>Estos tests están desactivados a propósito.</b> No hay forma de probar
/// <c>AddClipboardFormatListener</c> sin escribir en el portapapeles del sistema, que es
/// uno solo y compartido con todo lo que el usuario esté haciendo: ejecutarlos sin avisar
/// le borraría lo que tuviera copiado, y si tenía algo con formato (una tabla, una imagen)
/// no se podría restaurar.
///
/// Para ejecutarlos, cuando no haya nada importante en el portapapeles:
///
///     dotnet test DracPaste.Bandeja.Tests --filter "Category=Portapapeles"
///
/// y quitar los <c>Skip</c> de este fichero. El equivalente que <b>sí</b> corre siempre es
/// <c>CicloAntiEcoTests</c>, que prueba la lógica de rebote sin tocar nada del sistema.
/// </summary>
[Trait("Category", "Portapapeles")]
public class VigilanteDelPortapapelesTests
{
    private const string Motivo =
        "Escribe en el portapapeles real del usuario. Ver el comentario de la clase.";

    [Fact(Skip = Motivo)]
    public void UnaCopiaDelUsuarioLlegaAlEvento()
    {
        var recibidos = EnHiloSta(vigilante =>
        {
            var vistos = new List<string>();
            vigilante.TextoCopiado += vistos.Add;

            Clipboard.SetText("copiado a mano");
            BombearMensajes(500);

            return vistos;
        });

        Assert.Contains("copiado a mano", recibidos);
    }

    [Fact(Skip = Motivo)]
    public void LoQueEscribeLaAppNoSeDevuelve()
    {
        // La prueba del bucle de eco sobre el portapapeles de verdad: si el formato
        // propio no funcionara, este texto volvería por el evento y acabaría rebotando
        // entre el PC y el móvil indefinidamente.
        var recibidos = EnHiloSta(vigilante =>
        {
            var vistos = new List<string>();
            vigilante.TextoCopiado += vistos.Add;

            vigilante.EscribirDesdeElMovil("llegado del móvil");
            BombearMensajes(500);

            return vistos;
        });

        Assert.Empty(recibidos);
    }

    [Fact(Skip = Motivo)]
    public void VariosAvisosPorUnaSolaCopiaSeUnenEnUno()
    {
        // Office y otras aplicaciones publican varios formatos y disparan un
        // WM_CLIPBOARDUPDATE por cada uno. Sin el debounce, un Ctrl+C enviaría el mismo
        // clip tres o cuatro veces.
        var recibidos = EnHiloSta(vigilante =>
        {
            var vistos = new List<string>();
            vigilante.TextoCopiado += vistos.Add;

            for (var i = 0; i < 4; i++)
            {
                Clipboard.SetText("una sola copia");
                Thread.Sleep(10);
            }

            BombearMensajes(500);
            return vistos;
        });

        Assert.Single(recibidos);
    }

    [Fact(Skip = Motivo)]
    public void EnPausaNoSaleNada()
    {
        var recibidos = EnHiloSta(vigilante =>
        {
            var vistos = new List<string>();
            vigilante.TextoCopiado += vistos.Add;
            vigilante.EnPausa = true;

            Clipboard.SetText("no debería salir");
            BombearMensajes(500);

            return vistos;
        });

        Assert.Empty(recibidos);
    }

    /// <summary>
    /// El portapapeles de Windows exige un hilo STA, y el vigilante necesita además que
    /// alguien bombee la cola de mensajes para que su ventana oculta reciba los avisos.
    /// </summary>
    private static T EnHiloSta<T>(Func<VigilanteDelPortapapeles, T> prueba)
    {
        T resultado = default!;
        Exception? fallo = null;

        var hilo = new Thread(() =>
        {
            try
            {
                using var vigilante = new VigilanteDelPortapapeles();
                resultado = prueba(vigilante);
            }
            catch (Exception e)
            {
                fallo = e;
            }
        });

        hilo.SetApartmentState(ApartmentState.STA);
        hilo.Start();
        hilo.Join(TimeSpan.FromSeconds(30));

        if (fallo is not null)
        {
            throw fallo;
        }

        return resultado;
    }

    private static void BombearMensajes(int milisegundos)
    {
        var hasta = DateTime.UtcNow.AddMilliseconds(milisegundos);
        while (DateTime.UtcNow < hasta)
        {
            Application.DoEvents();
            Thread.Sleep(10);
        }
    }
}
