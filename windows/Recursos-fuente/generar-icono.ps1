# Genera DracPaste.Bandeja\Recursos\dracpaste.ico a partir de codigo, sin binarios
# opacos en el repositorio: el icono se puede reconstruir y auditar.
#
#   powershell -ExecutionPolicy Bypass -File windows\Recursos-fuente\generar-icono.ps1
#
# Marca: cuadrado redondeado verde dragon (#0F3A2E) con dos flechas doradas (#D9A441)
# enfrentadas, que es lo que hace la app: algo va y algo vuelve. Se mantiene legible
# a 16 px, que es el tamano al que se ve casi siempre (bandeja del sistema).

Add-Type -AssemblyName System.Drawing

$destino = Join-Path $PSScriptRoot "..\DracPaste.Bandeja\Recursos"
New-Item -ItemType Directory -Force -Path $destino | Out-Null
$rutaIco = Join-Path $destino "dracpaste.ico"

$verde = [System.Drawing.Color]::FromArgb(255, 15, 58, 46)
$oro = [System.Drawing.Color]::FromArgb(255, 217, 164, 65)

# Las dos flechas, sobre una rejilla de 32x32 que luego se escala a cada tamano.
$flechaDerecha = @(7, 12, 19, 12, 19, 8.5, 25, 14, 19, 19.5, 19, 16, 7, 16)
$flechaIzquierda = @(25, 20, 13, 20, 13, 16.5, 7, 22, 13, 27.5, 13, 24, 25, 24)

function New-Lienzo {
    param([int]$px, $colorFondo, $colorFlecha, [double[]]$arriba, [double[]]$abajo)

    $bmp = New-Object System.Drawing.Bitmap($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $u = $px / 32.0

    $radio = [Math]::Max(2, [int](5 * $u))
    $d = $radio * 2
    $ruta = New-Object System.Drawing.Drawing2D.GraphicsPath
    $ruta.AddArc(0, 0, $d, $d, 180, 90)
    $ruta.AddArc($px - $d, 0, $d, $d, 270, 90)
    $ruta.AddArc($px - $d, $px - $d, $d, $d, 0, 90)
    $ruta.AddArc(0, $px - $d, $d, $d, 90, 90)
    $ruta.CloseFigure()

    $pincelFondo = New-Object System.Drawing.SolidBrush($colorFondo)
    $g.FillPath($pincelFondo, $ruta)

    $pincelFlecha = New-Object System.Drawing.SolidBrush($colorFlecha)
    foreach ($poligono in @($arriba, $abajo)) {
        $puntos = New-Object System.Collections.Generic.List[System.Drawing.PointF]
        for ($i = 0; $i -lt $poligono.Length; $i += 2) {
            $puntos.Add((New-Object System.Drawing.PointF([float]($poligono[$i] * $u), [float]($poligono[$i + 1] * $u))))
        }
        $g.FillPolygon($pincelFlecha, $puntos.ToArray())
    }

    $g.Dispose()
    $pincelFondo.Dispose()
    $pincelFlecha.Dispose()
    $ruta.Dispose()
    return $bmp
}

# Empaquetado ICO con PNG embebido (soportado desde Windows Vista).
$tamanos = @(16, 24, 32, 48, 64, 128, 256)
$pngs = New-Object System.Collections.Generic.List[byte[]]
foreach ($t in $tamanos) {
    $bmp = New-Lienzo -px $t -colorFondo $verde -colorFlecha $oro -arriba $flechaDerecha -abajo $flechaIzquierda
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs.Add($ms.ToArray())
    $ms.Dispose()
    $bmp.Dispose()
}

$salida = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($salida)
$w.Write([UInt16]0)
$w.Write([UInt16]1)
$w.Write([UInt16]$tamanos.Count)

$desplazamiento = 6 + (16 * $tamanos.Count)
for ($i = 0; $i -lt $tamanos.Count; $i++) {
    $t = $tamanos[$i]
    $dim = [Byte]$(if ($t -ge 256) { 0 } else { $t })
    $w.Write($dim)
    $w.Write($dim)
    $w.Write([Byte]0)
    $w.Write([Byte]0)
    $w.Write([UInt16]1)
    $w.Write([UInt16]32)
    $w.Write([UInt32]$pngs[$i].Length)
    $w.Write([UInt32]$desplazamiento)
    $desplazamiento += $pngs[$i].Length
}
foreach ($png in $pngs) { $w.Write($png) }
$w.Flush()
[System.IO.File]::WriteAllBytes($rutaIco, $salida.ToArray())
$w.Dispose()
$salida.Dispose()

Write-Host "Icono escrito en $rutaIco ($((Get-Item $rutaIco).Length) bytes)"
