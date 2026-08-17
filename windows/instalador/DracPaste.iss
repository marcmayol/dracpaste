; Instalador de DracPaste para Windows (Inno Setup 6).
;
; Se compila con:
;   iscc windows\instalador\DracPaste.iss
;
; O, mas comodo, con scripts\publicar-windows.ps1, que antes publica el ejecutable.
;
; Se elige Inno Setup y no MSIX porque MSIX exige firmar el paquete con un certificado
; de confianza: sin el, Windows lo rechaza directamente y no hay forma de instalarlo. Un
; instalador clasico sin firmar solo enseña el aviso de SmartScreen, que el usuario puede
; saltarse. Como esto se distribuye fuera de la Store, es la unica via practica.

#define MiApp "DracPaste"
; La version la pasa scripts\publicar-windows.ps1 con /DMiVersion, sacandola del csproj.
; El valor de aqui es solo el respaldo para compilar el .iss a mano, y por eso conviene
; no fiarse de el: cuando estaba escrito aqui como unica fuente, se subio el proyecto a
; 1.4 y el instalador siguio saliendo con el 1.3 en su propio nombre.
#ifndef MiVersion
  #define MiVersion "1.4"
#endif
#define MiAutor "marcmayol.com"
#define MiEjecutable "DracPaste.exe"

[Setup]
AppId={{9F2B7C41-5E3A-4D18-9C6E-DR4CP4573001}
AppName={#MiApp}
AppVersion={#MiVersion}
AppPublisher={#MiAutor}
AppPublisherURL=https://marcmayol.com

; Instalacion por usuario, sin pedir administrador: la identidad de DracPaste se cifra
; con DPAPI del usuario actual, asi que una instalacion para toda la maquina no aportaria
; nada y solo añadiria una peticion de permisos que asusta.
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#MiApp}
DefaultGroupName={#MiApp}
DisableProgramGroupPage=yes
DisableDirPage=auto

OutputDir=..\..\dist
OutputBaseFilename=DracPaste-{#MiVersion}-instalador
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

UninstallDisplayIcon={app}\{#MiEjecutable}
SetupIconFile=..\DracPaste.Bandeja\Recursos\dracpaste.ico

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "arranque"; Description: "Arrancar DracPaste al iniciar sesion"; GroupDescription: "Inicio:"

[Files]
; La publicacion autocontenida trae su propio runtime: el usuario no tiene que instalar
; .NET antes, que es la primera piedra con la que tropieza quien solo quiere una app.
Source: "..\..\dist\publicado\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MiApp}"; Filename: "{app}\{#MiEjecutable}"
Name: "{userstartup}\{#MiApp}"; Filename: "{app}\{#MiEjecutable}"; Tasks: arranque

[Run]
; Las reglas del firewall, antes de abrir la app.
;
; Sin ellas el movil ve el PC —el ping funciona— pero la conexion TCP muere en un timeout,
; y desde el movil eso parece un problema de red. Es el fallo mas caro posible: todo
; parece bien y nada funciona.
;
; runascurrentuser NO: crear reglas necesita administrador, y esta instalacion corre sin
; privilegios. Se eleva solo este paso; si el usuario lo rechaza, la instalacion sigue
; adelante y la propia app avisa despues y ofrece crearlas desde su menu.
; Se borran antes las que hubiera: "add rule" con un nombre que ya existe ANADE otra
; regla en vez de reemplazarla, asi que al actualizar se irian acumulando —incluidas las
; viejas, que solo valian para redes privadas—.
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""DracPaste (TCP entrante)"""; \
    Flags: runhidden waituntilterminated; \
    Check: not EsSilencioso

Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""DracPaste (mDNS entrante)"""; \
    Flags: runhidden waituntilterminated; \
    Check: not EsSilencioso

Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""DracPaste (TCP entrante)"" dir=in action=allow program=""{app}\{#MiEjecutable}"" protocol=TCP localport=47653 profile=any"; \
    StatusMsg: "Permitiendo DracPaste en el firewall…"; \
    Flags: runhidden waituntilterminated; \
    Check: not EsSilencioso

Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""DracPaste (mDNS entrante)"" dir=in action=allow program=""{app}\{#MiEjecutable}"" protocol=UDP localport=5353 profile=any"; \
    StatusMsg: "Permitiendo el descubrimiento en la red local…"; \
    Flags: runhidden waituntilterminated; \
    Check: not EsSilencioso

Filename: "{app}\{#MiEjecutable}"; Description: "Abrir DracPaste"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Al desinstalar se quitan: dejar reglas apuntando a un ejecutable que ya no existe es
; basura en la configuracion del usuario.
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""DracPaste (TCP entrante)"""; \
    Flags: runhidden; RunOnceId: "BorrarReglaTcp"
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""DracPaste (mDNS entrante)"""; \
    Flags: runhidden; RunOnceId: "BorrarReglaMdns"

[UninstallDelete]
; La identidad y los emparejamientos se quedan a proposito: quien reinstala no tiene que
; volver a emparejar todos sus moviles. Se borran solo si el usuario lo pide.
Type: dirifempty; Name: "{localappdata}\DracPaste"

[Code]
// En instalacion silenciosa no se pide elevacion: un aviso de administrador que nadie
// va a ver dejaria el instalador colgado esperando.
function EsSilencioso(): Boolean;
begin
  Result := WizardSilent;
end;

// Al desinstalar se ofrece borrar las claves. No se hace sin preguntar: si alguien
// reinstala, perder los emparejamientos de todos sus moviles seria una sorpresa
// desagradable.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Carpeta: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    Carpeta := ExpandConstant('{localappdata}\DracPaste');
    if DirExists(Carpeta) then
    begin
      if MsgBox('¿Borrar tambien las claves de emparejamiento?' + #13#10 + #13#10 +
                'Si eliges que no y vuelves a instalar DracPaste, tus moviles seguiran ' +
                'emparejados. Si eliges que si, habra que emparejarlos otra vez.',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      begin
        DelTree(Carpeta, True, True, True);
      end;
    end;
  end;
end;
