<#
.SYNOPSIS
    重新產生 MSI 安裝精靈的圖片（packaging\msi-banner.bmp、packaging\msi-dialog.bmp），來源是 packaging\icon.ico。

    - msi-banner.bmp  493×58   安裝過程每頁頂端的橫幅；左側會疊上黑色標題文字，所以左邊保持白色
    - msi-dialog.bmp  493×312  歡迎頁與完成頁；右側 164px 以後是白底文字區，圖案只放左側

    只有換圖示或品牌顏色時才需要重跑：powershell -ExecutionPolicy Bypass -File packaging\make-msi-art.ps1
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$dir = $PSScriptRoot

# 從 icon.ico 取出最大的 PNG 圖層（System.Drawing.Icon 讀不了 256px 的 PNG 圖層）。
function Get-IconImage([int]$want) {
    $b = [IO.File]::ReadAllBytes((Join-Path $dir 'icon.ico'))
    $best = $null; $bestW = 0
    for ($k = 0; $k -lt [BitConverter]::ToUInt16($b, 4); $k++) {
        $o = 6 + 16 * $k; $w = if ($b[$o] -eq 0) { 256 } else { $b[$o] }
        $size = [BitConverter]::ToInt32($b, $o + 8); $off = [BitConverter]::ToInt32($b, $o + 12)
        if ($b[$off] -eq 0x89 -and $w -gt $bestW -and ($bestW -lt $want)) { $best = $b[$off..($off + $size - 1)]; $bestW = $w }
    }
    if (-not $best) { throw 'icon.ico 裡沒有 PNG 圖層' }
    return [System.Drawing.Image]::FromStream((New-Object IO.MemoryStream (, [byte[]]$best)))
}

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::White)
    return $bmp, $g
}

function C([string]$hex, [int]$a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($hex); [System.Drawing.Color]::FromArgb($a, $c) }

# 品牌色：取自圖示（資料夾的琥珀色、分割窗格徽章的藍紫色）。
$indigo = C '#4F6BF0'; $violet = C '#7A4FF0'; $amber = C '#F7B733'
$icon = Get-IconImage 256
$font = 'Segoe UI'

# --- 橫幅 493×58 --------------------------------------------------------------
$bmp, $g = New-Canvas 493 58
# 右側淡出的品牌色帶，左邊 300px 維持純白給標題文字。
$band = New-Object System.Drawing.Rectangle 300, 0, 193, 58
$br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $band, ([System.Drawing.Color]::White), (C '#EEF0FF'), 0.0
$g.FillRectangle($br, $band)
# 柔和的裝飾圓（像窗格）。
$g.FillEllipse((New-Object System.Drawing.SolidBrush (C '#4F6BF0' 28)), 380, -30, 90, 90)
$g.FillEllipse((New-Object System.Drawing.SolidBrush (C '#F7B733' 40)), 350, 30, 46, 46)
$g.DrawImage($icon, 431, 5, 48, 48)
# 底部細線，與 WiX 的分隔線銜接。
$line = New-Object System.Drawing.Rectangle 0, 56, 493, 2
$g.FillRectangle((New-Object System.Drawing.Drawing2D.LinearGradientBrush $line, (C '#FFFFFF'), $violet, 0.0), $line)
$g.Dispose(); $bmp.Save((Join-Path $dir 'msi-banner.bmp'), [System.Drawing.Imaging.ImageFormat]::Bmp); $bmp.Dispose()

# --- 歡迎／完成頁 493×312 -----------------------------------------------------
$bmp, $g = New-Canvas 493 312
$panel = New-Object System.Drawing.Rectangle 0, 0, 164, 312
$br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $panel, $indigo, $violet, 70.0
$g.FillRectangle($br, $panel)
$g.SetClip($panel)   # 裝飾不可超出左側面板：右側是安裝精靈的文字區
# 背景的窗格格線與光暈。
$grid = New-Object System.Drawing.Pen (C '#FFFFFF' 22), 1
for ($y = 0; $y -le 312; $y += 24) { $g.DrawLine($grid, 0, $y, 164, $y) }
for ($x = 0; $x -le 164; $x += 24) { $g.DrawLine($grid, $x, 0, $x, 312) }
$g.FillEllipse((New-Object System.Drawing.SolidBrush (C '#FFFFFF' 30)), -60, 190, 200, 200)
$g.FillEllipse((New-Object System.Drawing.SolidBrush (C '#F7B733' 70)), 100, -40, 110, 110)
# 圖示與光暈。
$g.FillEllipse((New-Object System.Drawing.SolidBrush (C '#FFFFFF' 40)), 22, 40, 120, 120)
$g.DrawImage($icon, 34, 50, 96, 96)
# 名稱與標語。
$center = New-Object System.Drawing.StringFormat; $center.Alignment = 'Center'
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$g.DrawString('FilesKmp', (New-Object System.Drawing.Font $font, 17, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)), $white, (New-Object System.Drawing.RectangleF 0, 172, 164, 28), $center)
$g.DrawString("File manager`nfor Windows", (New-Object System.Drawing.Font $font, 11, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush (C '#FFFFFF' 215)), (New-Object System.Drawing.RectangleF 0, 202, 164, 34), $center)
# 功能標籤（小圓角膠囊）。
$tagFont = New-Object System.Drawing.Font $font, 9, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$tags = @('Tabs', 'Split panes', 'Quick Look', 'Search', 'Shortcuts')
$rows = @(@($tags[0], $tags[1]), @($tags[2], $tags[3]), @($tags[4]))
$ty = 250
foreach ($row in $rows) {
    $widths = $row | ForEach-Object { [int]$g.MeasureString($_, $tagFont).Width + 10 }
    $total = ($widths | Measure-Object -Sum).Sum + 4 * ($row.Count - 1)
    $tx = [int]((164 - $total) / 2)
    for ($k = 0; $k -lt $row.Count; $k++) {
        $r = New-Object System.Drawing.Rectangle $tx, $ty, $widths[$k], 15
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddArc($r.X, $r.Y, 14, 14, 90, 180); $path.AddArc($r.Right - 14, $r.Y, 14, 14, 270, 180); $path.CloseFigure()
        $g.FillPath((New-Object System.Drawing.SolidBrush (C '#FFFFFF' 45)), $path)
        $g.DrawString($row[$k], $tagFont, $white, (New-Object System.Drawing.RectangleF $r.X, ($r.Y + 1), $r.Width, $r.Height), $center)
        $tx += $widths[$k] + 4
    }
    $ty += 18
}
$g.ResetClip()
# 文字區左緣淡淡的陰影，讓面板和白底分開。
$edge = New-Object System.Drawing.Rectangle 164, 0, 8, 312
$g.FillRectangle((New-Object System.Drawing.Drawing2D.LinearGradientBrush $edge, (C '#000000' 25), (C '#000000' 0), 0.0), $edge)
$g.Dispose(); $bmp.Save((Join-Path $dir 'msi-dialog.bmp'), [System.Drawing.Imaging.ImageFormat]::Bmp); $bmp.Dispose()

Write-Host "已產生 $(Join-Path $dir 'msi-banner.bmp') 與 $(Join-Path $dir 'msi-dialog.bmp')"
