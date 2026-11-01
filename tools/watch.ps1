# 분석 진행 상황과 결과 카드를 터미널에서 바로 본다. (백엔드가 켜져 있어야 한다)
#
#   .\tools\watch.ps1 -VideoId 437                       이미 올린 영상 지켜보기
#   .\tools\watch.ps1 -File "C:\경로\영상.mp4"            올리고 바로 지켜보기
#   .\tools\watch.ps1 -VideoId 437 -ReportOnly           결과만 다시 보기
#
# 진행률·단계·메시지가 바뀔 때만 한 줄씩 찍고, 끝나면 경고와 결과 카드를 보여준다.

param(
    [string]$VideoId,
    [string]$File,
    [string]$Base = "http://127.0.0.1:8080",
    [int]$Interval = 3,
    [switch]$ReportOnly
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$ErrorActionPreference = "Stop"

# PowerShell 5.1 의 Invoke-RestMethod 는 charset 이 없으면 한글을 깨뜨린다. 바이트를 직접 UTF-8 로 읽는다.
function Get-Json($path) {
    $res = Invoke-WebRequest -Uri ($Base + $path) -UseBasicParsing
    $ms = New-Object System.IO.MemoryStream
    $res.RawContentStream.CopyTo($ms)
    $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
    $body = $text | ConvertFrom-Json
    if ($body.PSObject.Properties.Name -contains "data") { return $body.data }
    return $body
}

function Fmt-Time($ms) {
    if ($null -eq $ms) { return "--:--" }
    $s = [int]([long]$ms / 1000)
    return "{0:D2}:{1:D2}" -f [int][math]::Floor($s / 60), ($s % 60)
}

function Show-Report($id) {
    $r = Get-Json "/api/v1/videos/$id/report"
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor DarkGray
    Write-Host (" 결과  #{0}  {1}  ({2})" -f $id, $r.filename, (Fmt-Time $r.durationMs)) -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor DarkGray

    if ($r.warnings -and $r.warnings.Count -gt 0) {
        Write-Host "`n[수행 안내]" -ForegroundColor Yellow
        foreach ($w in $r.warnings) { Write-Host ("  - {0} {1}: {2}" -f $w.stage, $w.code, $w.message) -ForegroundColor Yellow }
    }

    $events = @($r.events)
    if ($events.Count -eq 0 -or $null -eq $events[0]) {
        Write-Host "`n확인할 지점이 없습니다." -ForegroundColor Green
        return
    }
    Write-Host ("`n확인할 지점 {0}곳" -f $events.Count) -ForegroundColor Magenta
    $n = 0
    foreach ($e in $events) {
        $n++
        $kind = if ($e.candidateType -eq "FACT_CHECK") { "사실대조" } else { "맥락" }
        $crit = if ($e.criterionId) { " [$($e.criterionId)]" } else { "" }
        Write-Host ""
        Write-Host ("{0}. {1}~{2}  {3}{4}  {5}" -f $n, (Fmt-Time $e.startMs), (Fmt-Time $e.endMs), $kind, $crit, $e.title) -ForegroundColor White
        $said = if ($e.text) { $e.text } elseif ($e.captionText) { $e.captionText } else { "" }
        if ($e.contextBefore) { Write-Host ("   ..{0}" -f $e.contextBefore) -ForegroundColor DarkGray }
        if ($said) { Write-Host ("   발언: {0}" -f $said) -ForegroundColor Gray }
        if ($e.contextAfter) { Write-Host ("   ..{0}" -f $e.contextAfter) -ForegroundColor DarkGray }
        if ($e.reason) {
            foreach ($line in ($e.reason -split "`n")) { Write-Host ("   이유: {0}" -f $line) }
        }
        foreach ($ref in @($e.references)) {
            if ($null -eq $ref) { continue }
            Write-Host ("   출처: {0} ({1}) {2}" -f $ref.title, $ref.provider, $ref.url) -ForegroundColor DarkCyan
        }
    }
    Write-Host ""
}

# ---- 업로드
if ($File) {
    if (-not (Test-Path $File)) { Write-Host "파일이 없습니다: $File" -ForegroundColor Red; exit 1 }
    Write-Host "업로드 중: $File" -ForegroundColor Cyan
    $raw = (& curl.exe -s -X POST "$Base/api/v1/videos" -F "file=@$File") -join ""
    $up = $raw | ConvertFrom-Json
    if (-not $up.success) { Write-Host "업로드 실패: $raw" -ForegroundColor Red; exit 1 }
    $VideoId = $up.data.videoId
    Write-Host ("videoId = {0}" -f $VideoId) -ForegroundColor Green
}
if (-not $VideoId) { Write-Host "-VideoId 또는 -File 을 주세요." -ForegroundColor Red; exit 1 }

if ($ReportOnly) { Show-Report $VideoId; exit 0 }

# ---- 진행 상황
$last = ""
$started = Get-Date
while ($true) {
    try {
        $s = Get-Json "/api/v1/videos/$VideoId/status"
    } catch {
        Write-Host ("상태 조회 실패: {0}" -f $_.Exception.Message) -ForegroundColor Red
        Start-Sleep -Seconds $Interval
        continue
    }
    $line = "{0} {1} {2}% {3}" -f $s.status, $s.stage, $s.progress, $s.message
    if ($line -ne $last) {
        $elapsed = (Get-Date) - $started
        Write-Host ("[{0:mm\:ss}] {1,-10} {2,-12} {3,3}%  {4}" -f $elapsed, $s.status, $s.stage, $s.progress, $s.message)
        $last = $line
    }
    switch ($s.status) {
        "COMPLETED" { Show-Report $VideoId; exit 0 }
        "FAILED" {
            Write-Host ("분석 실패: {0} {1}" -f $s.failure.code, $s.failure.message) -ForegroundColor Red
            exit 1
        }
        "CANCELLED" { Write-Host "취소되었습니다." -ForegroundColor Yellow; exit 0 }
    }
    Start-Sleep -Seconds $Interval
}
