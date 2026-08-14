using System.Threading;

namespace DracPaste.Bandeja;

internal static class Program
{
    /// <summary>
    /// Nombre del mutex que garantiza una sola instancia. Va con el ámbito de sesión
    /// (no "Global\") a propósito: dos usuarios distintos en el mismo equipo pueden
    /// tener cada uno su DracPaste con sus propias claves.
    /// </summary>
    private const string NombreMutex = "DracPaste.InstanciaUnica";

    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(initiallyOwned: true, NombreMutex, out var esPrimera);
        if (!esPrimera)
        {
            // Ya hay una instancia: no se muestra ningún error, simplemente se sale.
            // El usuario que hace doble clic dos veces no ha cometido ninguna falta.
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new ContextoBandeja());
    }
}
