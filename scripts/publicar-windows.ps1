# Genera el instalador de Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\publicar-windows.ps1
#
# Publica el ejecutable autocontenido (con su propio runtime, para que el usuario no tenga
# que instalar .NET antes) y, si Inno Setup esta disponible, compila el instalador.
#
# El resultado queda en dist\.

$ErrorActionPreference = "Stop"
$raiz = Split-Path -Parent $PSScriptRoot

# El SDK de .NET esta instalado para el usuario (docs/decisions.md D-001).
$env:PATH = "$env:USERPROFILE\.dotnet;$env:PATH"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"

$destino = Join-Path $raiz "dist\publicado"

Write-Host "== Comprobando que todo pasa antes de publicar ==" -ForegroundColor Cyan
& dotnet test "$raiz\windows\DracPaste.sln" --nologo -v quiet
if ($LASTEXITCODE -ne 0) {
    Write-Error "Hay tests en rojo. No se publica."
    exit 1
}

Write-Host "== Publicando el ejecutable ==" -ForegroundColor Cyan
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }

# self-contained: el usuario no tiene que instalar .NET, que es la primera piedra con la
# que tropieza quien solo quiere una app.
# No se usa PublishSingleFile porque el icono de la bandeja se carga desde disco y el
# empaquetado en un solo fichero lo extrae a una carpeta temporal distinta en cada
# arranque.
& dotnet publish "$raiz\windows\DracPaste.Bandeja\DracPaste.Bandeja.csproj" `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:PublishReadyToRun=true `
    -o $destino `
    --nologo -v quiet

if ($LASTEXITCODE -ne 0) { Write-Error "Fallo la publicacion"; exit 1 }

$tamano = (Get-ChildItem $destino -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Publicado en {0} ({1:N0} MB)" -f $destino, $tamano) -ForegroundColor Green

# --------------------------------------------------------------------- Instalador

# La instalacion por usuario de winget lo deja en LOCALAPPDATA, no en Archivos de
# programa: hay que mirar los tres sitios.
$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $iscc) {
    Write-Host ""
    Write-Host "Inno Setup no esta instalado, asi que no se ha generado el instalador." -ForegroundColor Yellow
    Write-Host "El ejecutable publicado ya funciona tal cual desde $destino."
    Write-Host "Para generar el instalador: winget install JRSoftware.InnoSetup"
    exit 0
}

Write-Host "== Compilando el instalador ==" -ForegroundColor Cyan
& $iscc "$raiz\windows\instalador\DracPaste.iss"
if ($LASTEXITCODE -ne 0) { Write-Error "Fallo la compilacion del instalador"; exit 1 }

Get-ChildItem "$raiz\dist" -Filter "*.exe" | ForEach-Object {
    Write-Host ("Instalador: {0} ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) -ForegroundColor Green
}
