using Microsoft.Win32;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Arrancar DracPaste al iniciar sesión en Windows.
///
/// Se usa la clave <c>Run</c> del registro **del usuario actual**, no la de la máquina:
/// no requiere permisos de administrador, y cada usuario del equipo decide por su cuenta.
/// Es coherente con el resto de la app, donde la identidad también es por usuario (DPAPI).
///
/// Se evita a propósito el Programador de tareas: crea entradas que sobreviven a la
/// desinstalación y que el usuario no encuentra si algún día quiere quitarlas.
/// </summary>
internal static class ArranqueConWindows
{
    private const string Clave = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Nombre = "DracPaste";

    public static bool Activo
    {
        get
        {
            try
            {
                using var registro = Registry.CurrentUser.OpenSubKey(Clave, writable: false);
                return registro?.GetValue(Nombre) is not null;
            }
            catch (Exception)
            {
                return false;
            }
        }
    }

    /// <summary>@return si la operación salió bien.</summary>
    public static bool Activar(bool activar)
    {
        try
        {
            using var registro = Registry.CurrentUser.OpenSubKey(Clave, writable: true);
            if (registro is null)
            {
                return false;
            }

            if (activar)
            {
                // Entre comillas: sin ellas, una ruta con espacios —y "Program Files" los
                // tiene— haría que Windows intentara ejecutar solo el primer trozo.
                registro.SetValue(Nombre, $"\"{RutaDelEjecutable()}\"");
            }
            else
            {
                registro.DeleteValue(Nombre, throwOnMissingValue: false);
            }

            return true;
        }
        catch (Exception)
        {
            // Una política de empresa puede bloquear la escritura en esa clave. No es
            // motivo para que la app falle: simplemente no se puede ofrecer la opción.
            return false;
        }
    }

    private static string RutaDelEjecutable() =>
        Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "DracPaste.exe");
}
