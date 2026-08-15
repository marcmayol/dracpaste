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
#define MiVersion "0.1.0"
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
Filename: "{app}\{#MiEjecutable}"; Description: "Abrir DracPaste"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; La identidad y los emparejamientos se quedan a proposito: quien reinstala no tiene que
; volver a emparejar todos sus moviles. Se borran solo si el usuario lo pide.
Type: dirifempty; Name: "{localappdata}\DracPaste"

[Code]
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
