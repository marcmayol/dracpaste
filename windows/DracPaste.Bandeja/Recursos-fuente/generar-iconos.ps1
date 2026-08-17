# Genera los iconos de DracPaste a partir de la cabeza de Ladon.
Add-Type -AssemblyName System.Drawing

$origen = "C:\Users\marcm\DracPaste\design-handoff\assets\drac-head.png"
$res = "C:\Users\marcm\DracPaste\android\app\src\main\res"
$recursos = "C:\Users\marcm\DracPaste\windows\DracPaste.Bandeja\Recursos"
if (-not (Test-Path $recursos)) { New-Item -ItemType Directory $recursos | Out-Null }

function Dibujar([int]$lado, [double]$ocupacion, [System.Drawing.Color]$color) {
    $o = [System.Drawing.Image]::FromFile($origen)
    $mapa = New-Object System.Drawing.Bitmap $lado, $lado
    $g = [System.Drawing.Graphics]::FromImage($mapa)
    $g.InterpolationMode = "HighQualityBicubic"
    $g.SmoothingMode = "AntiAlias"
    $g.PixelOffsetMode = "HighQuality"
    $g.Clear([System.Drawing.Color]::Transparent)

    $disponible = $lado * $ocupacion
    $escala = [Math]::Min($disponible / $o.Width, $disponible / $o.Height)
    $w = $o.Width * $escala
    $h = $o.Height * $escala

    if ($color -eq [System.Drawing.Color]::Empty) {
        $g.DrawImage($o, ($lado - $w) / 2, ($lado - $h) / 2, $w, $h)
    } else {
        # Recolorear manteniendo el canal alfa: la silueta es plana, asi que basta
        # con sustituir el color y respetar la transparencia.
        $m = New-Object System.Drawing.Imaging.ColorMatrix
        $m.Matrix00 = 0; $m.Matrix11 = 0; $m.Matrix22 = 0
        $m.Matrix40 = $color.R / 255
        $m.Matrix41 = $color.G / 255
        $m.Matrix42 = $color.B / 255
        $atr = New-Object System.Drawing.Imaging.ImageAttributes
        $atr.SetColorMatrix($m)
        $destino = New-Object System.Drawing.Rectangle ([int](($lado - $w) / 2)), ([int](($lado - $h) / 2)), ([int]$w), ([int]$h)
        $g.DrawImage($o, $destino, 0, 0, $o.Width, $o.Height, "Pixel", $atr)
    }

    $g.Dispose()
    $o.Dispose()
    return $mapa
}

# 1. Icono del lanzador. La zona segura del icono adaptativo es 66 de 108 dp: si el
#    dibujo se sale de ahi, los lanzadores redondos le cortan los cuernos.
Remove-Item "$res\drawable\ic_launcher_foreground.xml" -ErrorAction SilentlyContinue
$frente = Dibujar 432 0.58 ([System.Drawing.Color]::Empty)
$frente.Save("$res\drawable\ic_launcher_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
$frente.Dispose()
Write-Host "  ic_launcher_foreground.png"

# 2. Icono de notificacion: Android lo aplana y lo tine, solo cuenta el alfa.
Remove-Item "$res\drawable\ic_notificacion.xml" -ErrorAction SilentlyContinue
$aviso = Dibujar 96 0.92 ([System.Drawing.Color]::White)
$aviso.Save("$res\drawable\ic_notificacion.png", [System.Drawing.Imaging.ImageFormat]::Png)
$aviso.Dispose()
Write-Host "  ic_notificacion.png"

# El .ico de Windows lo genera Ico.cs, no este script: ver LEEME.md.
