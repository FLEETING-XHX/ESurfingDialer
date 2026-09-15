$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$iconDir = Join-Path (Split-Path -Parent $PSScriptRoot) "resources\icons"
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$states = @{
    default      = "#238BFF"
    connecting   = "#FFB51B"
    connected    = "#37C759"
    disconnected = "#AEB4BC"
    error        = "#FF3B44"
}

function Fill-RoundedRectangle($graphics, $brush, [float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $radius * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $graphics.FillPath($brush, $path)
    $path.Dispose()
}

function New-IconBitmap([int]$size, [string]$signalColor) {
    $bitmap = [Drawing.Bitmap]::new($size, $size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([Drawing.Color]::Transparent)

    $scale = $size / 256.0
    $dark = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml("#303338"))
    $coral = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml("#FF7658"))
    $white = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml("#FFFDF9"), [Math]::Max(2, 18 * $scale))
    $signal = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($signalColor), [Math]::Max(2, 12 * $scale))
    $white.StartCap = $white.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $white.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
    $signal.StartCap = $signal.EndCap = [Drawing.Drawing2D.LineCap]::Round

    $radius = 48 * $scale
    Fill-RoundedRectangle $graphics $dark 0 0 $size $size $radius

    $door = [Drawing.PointF[]]@(
        [Drawing.PointF]::new(48 * $scale, 62 * $scale),
        [Drawing.PointF]::new(106 * $scale, 48 * $scale),
        [Drawing.PointF]::new(106 * $scale, 208 * $scale),
        [Drawing.PointF]::new(48 * $scale, 194 * $scale)
    )
    $graphics.FillPolygon($coral, $door)
    $graphics.DrawLines($white, [Drawing.PointF[]]@(
        [Drawing.PointF]::new(48 * $scale, 62 * $scale),
        [Drawing.PointF]::new(106 * $scale, 48 * $scale),
        [Drawing.PointF]::new(106 * $scale, 208 * $scale),
        [Drawing.PointF]::new(48 * $scale, 194 * $scale),
        [Drawing.PointF]::new(48 * $scale, 62 * $scale)
    ))

    foreach ($bar in @(@(146, 166, 22), @(146, 128, 38), @(146, 90, 54))) {
        $x = $bar[0] * $scale
        $y = $bar[1] * $scale
        $height = $bar[2] * $scale
        $graphics.DrawLine($white, $x, $y, ($x + $height), $y)
        $graphics.DrawLine($signal, $x, $y, ($x + $height), $y)
    }

    $signal.Dispose(); $white.Dispose(); $dark.Dispose(); $coral.Dispose(); $graphics.Dispose()
    return $bitmap
}

function ConvertTo-PngBytes([Drawing.Bitmap]$bitmap) {
    $stream = [IO.MemoryStream]::new()
    $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
    $bytes = $stream.ToArray()
    $stream.Dispose()
    return $bytes
}

function Write-Ico([string]$path, [byte[][]]$pngImages, [int[]]$imageSizes) {
    $stream = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($stream)
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$pngImages.Count)
    $offset = 6 + (16 * $pngImages.Count)
    for ($i = 0; $i -lt $pngImages.Count; $i++) {
        $size = $imageSizes[$i]
        $width = if ($size -ge 256) { 0 } else { $size }
        $writer.Write([byte]$width); $writer.Write([byte]$width)
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$pngImages[$i].Length)
        $writer.Write([uint32]$offset)
        $offset += $pngImages[$i].Length
    }
    foreach ($png in $pngImages) { $writer.Write($png) }
    [IO.File]::WriteAllBytes($path, $stream.ToArray())
    $writer.Dispose(); $stream.Dispose()
}

if (-not (Test-Path $iconDir)) { New-Item -ItemType Directory -Path $iconDir | Out-Null }
foreach ($state in $states.Keys) {
    $pngs = [Collections.Generic.List[byte[]]]::new()
    foreach ($size in $sizes) {
        $bitmap = New-IconBitmap $size $states[$state]
        $pngs.Add((ConvertTo-PngBytes $bitmap))
        $bitmap.Dispose()
    }
    Write-Ico (Join-Path $iconDir "ESurfingDialer-$state.ico") $pngs.ToArray() $sizes
}

Write-Host "Generated icon family in $iconDir"
