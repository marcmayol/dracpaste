using System.Diagnostics;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// El Firewall de Windows, que es lo primero que rompe DracPaste en un PC nuevo.
///
/// **Por qué esto existe.** Sin una regla que permita las conexiones entrantes, el móvil
/// ve el PC —el ping funciona— pero la conexión TCP muere en un timeout. La app del móvil
/// solo puede decir «el PC no contesta», y el usuario se pone a mirar el WiFi, que está
/// perfectamente. Es el fallo más caro posible: todo parece correcto y nada funciona.
///
/// Windows suele ofrecer su propio diálogo la primera vez que un programa escucha en un
/// puerto, pero no siempre: si la app arranca minimizada en la bandeja al iniciar sesión,
/// ese aviso puede no llegar a verse nunca.
///
/// Se consulta con la API COM del firewall, que se puede **leer sin ser administrador**.
/// Crear la regla sí lo necesita, y para eso se pide elevación explícitamente.
/// </summary>
internal static class Cortafuegos
{
    private const string NombreReglaTcp = "DracPaste (TCP entrante)";
    private const string NombreReglaMdns = "DracPaste (mDNS entrante)";

    /// <summary>
    /// ¿Hay alguna regla habilitada que deje entrar a este ejecutable?
    ///
    /// Devuelve <c>null</c> si no se puede saber (la API COM no está disponible). En ese
    /// caso no se avisa de nada: es peor dar una alarma falsa que callar.
    /// </summary>
    public static bool? HayReglaDeEntrada()
    {
        try
        {
            var tipo = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if (tipo is null)
            {
                return null;
            }

            dynamic? politica = Activator.CreateInstance(tipo);
            if (politica is null)
            {
                return null;
            }

            var rutaPropia = Environment.ProcessPath;
            if (string.IsNullOrEmpty(rutaPropia))
            {
                return null;
            }

            foreach (dynamic regla in politica.Rules)
            {
                try
                {
                    // Direction 1 = entrante, Action 1 = permitir.
                    if (regla.Enabled != true || regla.Direction != 1 || regla.Action != 1)
                    {
                        continue;
                    }

                    string? aplicacion = regla.ApplicationName as string;
                    if (!string.IsNullOrEmpty(aplicacion) &&
                        string.Equals(Path.GetFullPath(aplicacion), Path.GetFullPath(rutaPropia),
                            StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }
                catch (Exception)
                {
                    // Alguna regla del sistema no expone todas sus propiedades. Se ignora
                    // y se sigue con las demás.
                }
            }

            return false;
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Crea las reglas, pidiendo elevación. Devuelve si el usuario aceptó y salió bien.
    ///
    /// Se acotan al ejecutable, al puerto y a los perfiles privado y de dominio: en una
    /// red pública —un hotel, un aeropuerto— DracPaste no debe aceptar conexiones, y ahí
    /// tampoco tendría sentido usarlo.
    /// </summary>
    public static bool CrearReglas()
    {
        var exe = Environment.ProcessPath;
        if (string.IsNullOrEmpty(exe))
        {
            return false;
        }

        // Se borran primero las que hubiera: reinstalar en otra carpeta dejaría reglas
        // apuntando a un ejecutable que ya no existe.
        var ordenes = string.Join(" & ", new[]
        {
            $"netsh advfirewall firewall delete rule name=\"{NombreReglaTcp}\"",
            $"netsh advfirewall firewall delete rule name=\"{NombreReglaMdns}\"",
            $"netsh advfirewall firewall add rule name=\"{NombreReglaTcp}\" dir=in action=allow " +
                $"program=\"{exe}\" protocol=TCP localport={Protocolo.Protocolo.PuertoPreferido} " +
                "profile=private,domain",
            $"netsh advfirewall firewall add rule name=\"{NombreReglaMdns}\" dir=in action=allow " +
                $"program=\"{exe}\" protocol=UDP localport=5353 profile=private,domain",
        });

        try
        {
            var proceso = Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c {ordenes}",
                UseShellExecute = true,
                Verb = "runas",        // Pide elevación: crear reglas necesita administrador.
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            });

            if (proceso is null)
            {
                return false;
            }

            proceso.WaitForExit(30_000);
            return proceso.HasExited && proceso.ExitCode == 0;
        }
        catch (Exception)
        {
            // El usuario ha dicho que no al aviso de administrador, o una política lo
            // impide. No es un error de la app: simplemente no se pudo.
            return false;
        }
    }
}
