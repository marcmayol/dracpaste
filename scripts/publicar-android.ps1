# Genera el APK firmado de DracPaste.
#
#   powershell -ExecutionPolicy Bypass -File scripts\publicar-android.ps1
#
# La primera vez crea la keystore si no existe, preguntando la contrasena. La keystore NO
# se versiona: esta en el .gitignore y vive fuera del repositorio.
#
# ATENCION: si se pierde la keystore, no hay forma de publicar una actualizacion que
# Android acepte encima de la instalada. Hay que guardarla en un sitio seguro.
#
# El APK queda en dist\.

$ErrorActionPreference = "Stop"
$raiz = Split-Path -Parent $PSScriptRoot
$android = Join-Path $raiz "android"
$dist = Join-Path $raiz "dist"

$keystore = Join-Path $env:USERPROFILE "dracpaste-release.jks"
$propiedades = Join-Path $android "keystore.properties"

New-Item -ItemType Directory -Force -Path $dist | Out-Null

Write-Host "== Comprobando que todo pasa antes de publicar ==" -ForegroundColor Cyan
Push-Location $android
& .\gradlew.bat :protocolo:test :app:testDebugUnitTest --console=plain -q
$testsOk = $LASTEXITCODE -eq 0
Pop-Location
if (-not $testsOk) { Write-Error "Hay tests en rojo. No se publica."; exit 1 }

# ------------------------------------------------------------------- Keystore

if (-not (Test-Path $keystore)) {
    Write-Host ""
    Write-Host "No hay keystore de firma en $keystore" -ForegroundColor Yellow
    Write-Host "Se va a crear una. Guardala bien: sin ella no se pueden publicar"
    Write-Host "actualizaciones que Android acepte encima de la version instalada."
    Write-Host ""

    $clave = Read-Host "Contrasena para la keystore" -AsSecureString
    $claveTexto = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($clave))

    $keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (-not (Test-Path $keytool)) { $keytool = "keytool" }

    & $keytool -genkeypair -v `
        -keystore $keystore `
        -alias dracpaste `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass $claveTexto -keypass $claveTexto `
        -dname "CN=DracPaste, OU=marcmayol.com, O=marcmayol.com, C=ES"

    if ($LASTEXITCODE -ne 0) { Write-Error "No se pudo crear la keystore"; exit 1 }

    # Sin BOM: Properties.load de Gradle no lo entiende y la firma acabaria siendo la de
    # depuracion sin que nada avisara.
    $contenido = @(
        "storeFile=$($keystore -replace '\\', '/')",
        "storePassword=$claveTexto",
        "keyAlias=dracpaste",
        "keyPassword=$claveTexto"
    ) -join "`n"
    [System.IO.File]::WriteAllText($propiedades, $contenido, (New-Object System.Text.UTF8Encoding($false)))

    Write-Host "Keystore creada en $keystore" -ForegroundColor Green
}

if (-not (Test-Path $propiedades)) {
    Write-Error "Falta $propiedades. Borra la keystore y vuelve a ejecutar, o escribelo a mano."
    exit 1
}

# ----------------------------------------------------------------------- APK

Write-Host "== Compilando el APK de publicacion ==" -ForegroundColor Cyan
Push-Location $android
& .\gradlew.bat :app:assembleRelease --console=plain
$buildOk = $LASTEXITCODE -eq 0
Pop-Location
if (-not $buildOk) { Write-Error "Fallo la compilacion del APK"; exit 1 }

$apk = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { Write-Error "No se encuentra el APK en $apk"; exit 1 }

# Se comprueba que va firmado de verdad: un keystore.properties mal leido produce un APK
# firmado con la clave de depuracion, y eso no se nota hasta que falla la instalacion.
$apksigner = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Filter "apksigner.bat" -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1

if ($apksigner) {
    Write-Host "== Verificando la firma ==" -ForegroundColor Cyan
    $firma = & $apksigner.FullName verify --print-certs $apk 2>&1 | Out-String
    if ($firma -match "CN=Android Debug") {
        Write-Error "El APK esta firmado con la clave de DEPURACION. Revisa keystore.properties."
        exit 1
    }
    Write-Host ($firma -split "`n" | Select-Object -First 4) -ForegroundColor Green
}

$version = (Select-String -Path "$android\app\build.gradle.kts" -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$destino = Join-Path $dist "DracPaste-$version.apk"
Copy-Item $apk $destino -Force

Write-Host ""
Write-Host ("APK firmado: {0} ({1:N1} MB)" -f $destino, ((Get-Item $destino).Length / 1MB)) -ForegroundColor Green
