$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$iconDir = Join-Path (Split-Path -Parent $PSScriptRoot) "resources\icons"
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$states = [ordered]@{
    default      = "#9AA3AE"
    connecting   = "#E6A23C"
    connected    = "#43B856"
    disconnected = "#9AA3AE"
    error        = "#DE6255"
}

function New-RoundedPath([float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $radius * 2
    $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
    $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
    $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function Fill-RoundedRectangle($graphics, $brush, [float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = New-RoundedPath $x $y $width $height $radius
    $graphics.FillPath($brush, $path)
    $path.Dispose()
}

function Draw-RoundedRectangle($graphics, $pen, [float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
    $path = New-RoundedPath $x $y $width $height $radius
    $graphics.DrawPath($pen, $path)
    $path.Dispose()
}

function New-IconBitmap([int]$size, [string]$signalColor) {
    $bitmap = [Drawing.Bitmap]::new($size, $size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.Clear([Drawing.Color]::Transparent)

    $scale = $size / 256.0
    $white = [Drawing.SolidBrush]::new([Drawing.Color]::White)
    $border = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml("#E5E8EC"), [Math]::Max(1, 2 * $scale))
    $signal = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($signalColor))

    $inset = 6 * $scale
    Fill-RoundedRectangle $graphics $white $inset $inset ($size - 2 * $inset) ($size - 2 * $inset) (48 * $scale)
    Draw-RoundedRectangle $graphics $border $inset $inset ($size - 2 * $inset) ($size - 2 * $inset) (48 * $scale)

    foreach ($bar in @(@(44, 148, 40, 48), @(100, 104, 40, 92), @(156, 60, 40, 136))) {
        Fill-RoundedRectangle $graphics $signal ($bar[0] * $scale) ($bar[1] * $scale) ($bar[2] * $scale) ($bar[3] * $scale) (10 * $scale)
    }

    $signal.Dispose(); $border.Dispose(); $white.Dispose(); $graphics.Dispose()
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

function Write-Svg([string]$path, [string]$signalColor) {
    $svg = @"
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" role="img" aria-label="ESurfingDialer signal">
  <rect x="6" y="6" width="244" height="244" rx="48" fill="#FFFFFF" stroke="#E5E8EC" stroke-width="2"/>
  <rect x="44" y="148" width="40" height="48" rx="10" fill="$signalColor"/>
  <rect x="100" y="104" width="40" height="92" rx="10" fill="$signalColor"/>
  <rect x="156" y="60" width="40" height="136" rx="10" fill="$signalColor"/>
</svg>
"@
    [IO.File]::WriteAllText($path, $svg, [Text.UTF8Encoding]::new($false))
}

if (-not (Test-Path -LiteralPath $iconDir)) { New-Item -ItemType Directory -Path $iconDir | Out-Null }
foreach ($state in $states.Keys) {
    $pngs = [Collections.Generic.List[byte[]]]::new()
    foreach ($size in $sizes) {
        $bitmap = New-IconBitmap $size $states[$state]
        $pngs.Add((ConvertTo-PngBytes $bitmap))
        $bitmap.Dispose()
    }
    Write-Ico (Join-Path $iconDir "ESurfingDialer-$state.ico") $pngs.ToArray() $sizes
    Write-Svg (Join-Path $iconDir "ESurfingDialer-$state.svg") $states[$state]
}

Write-Host "Generated white three-bar signal icon family in $iconDir"
