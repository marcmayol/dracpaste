# Prueba cruzada Kotlin <-> C# por un socket real.
#
#   powershell -ExecutionPolicy Bypass -File scripts\prueba-cruzada.ps1
#
# Arranca el servidor de Windows (el mismo ServidorDracPaste que usa la app de bandeja),
# le pasa al cliente Kotlin el JSON del QR que imprime, y comprueba que los dos textos
# cruzan en las dos direcciones.
#
# Es la unica prueba que demuestra que Bouncy Castle (Android) y libsodium (Windows) se
# entienden de verdad. Los vectores de docs/protocol.md verifican que las primitivas dan
# los mismos bytes, pero no que el dialogo completo funcione entre dos lenguajes.
#
# Sale con 0 si todo va bien y con 1 si algo falla.

$ErrorActionPreference = "Stop"
$raiz = Split-Path -Parent $PSScriptRoot

# El SDK de .NET esta instalado para el usuario (docs/decisions.md D-001).
$env:PATH = "$env:USERPROFILE\.dotnet;$env:PATH"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"

Write-Host "== Compilando los dos lados ==" -ForegroundColor Cyan
& dotnet build "$raiz\windows\DracPaste.sln" -v quiet --nologo
if ($LASTEXITCODE -ne 0) { Write-Error "No compila el lado de Windows"; exit 1 }

Push-Location "$raiz\android"
& .\gradlew.bat :protocolo:testClasses -q --console=plain
$gradleOk = $LASTEXITCODE -eq 0
Pop-Location
if (-not $gradleOk) { Write-Error "No compila el lado de Android"; exit 1 }

Write-Host "== Arrancando el servidor de Windows ==" -ForegroundColor Cyan
$exe = "$raiz\windows\DracPaste.PruebaCruzada\bin\Debug\net8.0-windows\DracPaste.PruebaCruzada.exe"
if (-not (Test-Path $exe)) { Write-Error "No se encuentra $exe"; exit 1 }

$salidaServidor = New-TemporaryFile
$errorServidor = New-TemporaryFile
$servidor = Start-Process -FilePath $exe -PassThru -NoNewWindow `
    -RedirectStandardOutput $salidaServidor -RedirectStandardError $errorServidor

# El servidor imprime "QR=..." en cuanto esta escuchando.
$qr = $null
$limite = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $limite -and -not $qr) {
    Start-Sleep -Milliseconds 200
    $linea = Get-Content $salidaServidor -ErrorAction SilentlyContinue | Where-Object { $_ -like "QR=*" } | Select-Object -First 1
    if ($linea) { $qr = $linea.Substring(3) }
    if ($servidor.HasExited) { break }
}

if (-not $qr) {
    Write-Host "--- salida del servidor ---" -ForegroundColor Yellow
    Get-Content $salidaServidor, $errorServidor -ErrorAction SilentlyContinue
    if (-not $servidor.HasExited) { Stop-Process -Id $servidor.Id -Force }
    Write-Error "El servidor no llego a imprimir el QR"
    exit 1
}

Write-Host "QR recibido del servidor" -ForegroundColor Green

Write-Host "== Lanzando el cliente Kotlin ==" -ForegroundColor Cyan

# En base64: al pasar --args a Gradle, las comillas dobles del JSON se pierden por el
# camino y el cliente recibe algo que ya no es JSON.
$qrBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($qr))

Push-Location "$raiz\android"
& .\gradlew.bat :protocolo:clienteDePrueba -q --console=plain "--args=$qrBase64"
$clienteOk = $LASTEXITCODE -eq 0
Pop-Location

# El servidor termina solo tras el intercambio; se le da margen.
$termino = $servidor.WaitForExit(20000)
if (-not $termino) {
    Stop-Process -Id $servidor.Id -Force
}

$salida = Get-Content $salidaServidor -ErrorAction SilentlyContinue

# Se mira la ultima linea de la salida y no solo $servidor.ExitCode: el objeto que
# devuelve Start-Process -PassThru no siempre trae el codigo de salida actualizado, y
# dar la prueba por fallida cuando en realidad fue bien es peor que no tenerla.
$servidorOk = $termino -and ($salida -contains "RESULTADO: OK")

Write-Host "--- salida del servidor ---" -ForegroundColor Yellow
$salida
$errores = Get-Content $errorServidor -ErrorAction SilentlyContinue
if ($errores) {
    Write-Host "--- errores del servidor ---" -ForegroundColor Red
    $errores
}

Remove-Item $salidaServidor, $errorServidor -Force -ErrorAction SilentlyContinue

if ($clienteOk -and $servidorOk) {
    Write-Host ""
    Write-Host "PRUEBA CRUZADA SUPERADA: Kotlin y C# se entienden por un socket real." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "PRUEBA CRUZADA FALLIDA (cliente: $clienteOk, servidor: $servidorOk)" -ForegroundColor Red
exit 1
