using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Makaretu.Dns;

namespace DracPaste.Bandeja.Nucleo;

/// <summary>
/// Publica <c>_dracpaste._tcp</c> en la red local (<c>docs/protocol.md</c> §9).
///
/// Es lo que permite que el móvil encuentre al PC sin que nadie escriba una IP, y que lo
/// siga encontrando cuando el router le cambie la dirección. El <c>device_id</c> viaja en
/// los registros TXT: el móvil lo usa para reconocer a *su* PC y descartar cualquier otro
/// anuncio, incluido el de alguien que publique el mismo servicio a propósito.
/// </summary>
public sealed class AnuncioMdns : IDisposable
{
    private readonly Identidad _identidad;
    private ServiceDiscovery? _descubrimiento;
    private ServiceProfile? _perfil;
    private int _puerto;

    public AnuncioMdns(Identidad identidad)
    {
        _identidad = identidad;
    }

    public bool Anunciando => _descubrimiento is not null;

    /// <summary>Empieza a anunciarse en el puerto indicado.</summary>
    public void Anunciar(int puerto)
    {
        Detener();
        _puerto = puerto;

        // El nombre de instancia lleva el device_id para que dos PCs con el mismo nombre
        // de equipo —cosa que pasa— no colisionen en la red.
        var instancia = $"{Sanear(_identidad.Nombre)}-{_identidad.DeviceId[..8]}";
        _perfil = new ServiceProfile(instancia, Protocolo.Protocolo.ServicioMdns + ".local", (ushort)puerto);

        _perfil.AddProperty(Protocolo.Protocolo.TxtVersion, Protocolo.Protocolo.Version.ToString());
        _perfil.AddProperty(Protocolo.Protocolo.TxtId, _identidad.DeviceId);
        _perfil.AddProperty(Protocolo.Protocolo.TxtNombre, _identidad.Nombre);

        _descubrimiento = new ServiceDiscovery();
        _descubrimiento.Advertise(_perfil);
        _descubrimiento.Announce(_perfil);
    }

    /// <summary>
    /// Vuelve a anunciar el servicio.
    ///
    /// Hace falta al despertar de suspensión: mientras el PC dormía, los anuncios
    /// caducaron en las cachés mDNS de la red y el móvil dejó de ver el servicio. Sin
    /// esto, la reconexión dependería de que el móvil reintentara contra la última IP,
    /// que puede haber cambiado.
    /// </summary>
    public void Reanunciar()
    {
        if (_perfil is null)
        {
            return;
        }

        // Se rehace el ServiceDiscovery entero, no solo el anuncio: tras suspender, sus
        // sockets multicast están atados a interfaces de red que quizá ya no existen.
        Anunciar(_puerto);
    }

    public void Detener()
    {
        if (_descubrimiento is null)
        {
            return;
        }

        try
        {
            if (_perfil is not null)
            {
                _descubrimiento.Unadvertise(_perfil);
            }
        }
        catch (Exception)
        {
            // Si la red ya no está, el adiós no se puede enviar. No es motivo para que
            // la app falle al cerrarse.
        }

        _descubrimiento.Dispose();
        _descubrimiento = null;
    }

    /// <summary>
    /// La IP de este PC en la red local, para meterla en el QR.
    ///
    /// Se elige la de una interfaz activa que no sea loopback ni un adaptador virtual:
    /// con Docker, WSL o una VPN instalados hay varias, y poner en el QR la de un
    /// adaptador virtual daría una IP a la que el móvil no puede llegar.
    /// </summary>
    public static IPAddress? IpLocal()
    {
        var candidatas = NetworkInterface.GetAllNetworkInterfaces()
            .Where(i => i.OperationalStatus == OperationalStatus.Up)
            .Where(i => i.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211)
            .Where(i => !i.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase))
            .Where(i => !i.Description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase))
            .SelectMany(i => i.GetIPProperties().UnicastAddresses)
            .Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork)
            .Where(u => !IPAddress.IsLoopback(u.Address))
            .Select(u => u.Address)
            .ToList();

        // Las inalámbricas primero: es la red por la que estará el móvil.
        return candidatas.FirstOrDefault();
    }

    /// <summary>mDNS solo admite letras, dígitos y guiones en el nombre de instancia.</summary>
    private static string Sanear(string nombre)
    {
        var limpio = new string(nombre.Select(c => char.IsLetterOrDigit(c) ? c : '-').ToArray()).Trim('-');
        return string.IsNullOrEmpty(limpio) ? "DracPaste" : limpio;
    }

    public void Dispose() => Detener();
}
