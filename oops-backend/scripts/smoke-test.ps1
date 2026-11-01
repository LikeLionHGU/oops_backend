# 영상 하나를 올리고 결과까지 터미널에서 보는 확인 스크립트 (redesign-v3)
#
#   .\scripts\smoke-test.ps1 -File "C:\경로\영상.mp4"    올리고 → 진행 상황 → 결과 카드
#   .\scripts\smoke-test.ps1 -VideoId 437                 이미 올린 영상: 진행 중이면 지켜보고, 끝났으면 결과만
#
# 백엔드(8080)와 분석 서버(8000)가 켜져 있어야 한다.

param(
    [string]$File,
    [int]$VideoId = 0,
    [string]$Backend = "http://localhost:8080",
    [string]$Analysis = "http://localhost:8000",
    [int]$TimeoutMinutes = 150
)

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }
$ErrorActionPreference = "Stop"

function Section($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Warn($t) { Write-Host "  [WARN] $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "  [FAIL] $t" -ForegroundColor Red }
function Hint($t) { Write-Host "         $t" -ForegroundColor DarkGray }

# Invoke-RestMethod 는 PowerShell 5.1 에서 한글을 깨뜨린다. curl.exe 로 받아 직접 파싱한다.
function Get-Json($url) {
    $raw = (curl.exe -s $url) -join "`n"
    if (-not $raw) { return $null }
    try { return ($raw | ConvertFrom-Json) } catch { return $null }
}

function Fmt-Time($ms) {
    if ($null -eq $ms) { return "--:--" }
    return [TimeSpan]::FromMilliseconds([double]$ms).ToString("mm\:ss")
}

# ---------------------------------------------------------- 0. 준비물
Section "0. 준비물 확인"
if ($VideoId -eq 0 -and -not $File) { Fail "-File 또는 -VideoId 가 필요합니다."; exit 1 }
if ($File -and -not (Test-Path $File)) { Fail "파일 없음: $File"; exit 1 }
if (Get-Command curl.exe -ErrorAction SilentlyContinue) { Ok "curl.exe" } else { Fail "curl.exe 없음"; exit 1 }

# ---------------------------------------------------------- 1~2. 서버
if ($File) {
    Section "1. 분석 서버 (전사)"
    $health = Get-Json "$Analysis/health"
    if (-not $health) { Fail "분석 서버 응답 없음. oops-analysis 에서 .\run.ps1 실행"; exit 1 }
    Ok "응답함"
    if ($health.sttAvailable -eq $false) { Warn "STT 불가 - oops-analysis/.env 의 GROQ_API_KEY 확인" }
}

Section "2. 백엔드"
$ping = curl.exe -s -o NUL -w "%{http_code}" "$Backend/api/v1/videos/999999/status"
if ($ping -eq "000") { Fail "백엔드 응답 없음. gradlew bootRun 으로 켜세요."; exit 1 }
Ok "응답함 (HTTP $ping)"

# ---------------------------------------------------------- 3. 업로드
if ($File) {
    Section "3. 영상 업로드 (올리면 바로 분석 시작)"
    $item = Get-Item $File
    Write-Host ("  업로드 중... ({0} MB)" -f [math]::Round($item.Length / 1MB, 1))
    $raw = (curl.exe -s -S -X POST "$Backend/api/v1/videos" -F "file=@$($item.FullName)") -join "`n"
    if (-not $raw) { Fail "업로드 응답이 비었습니다."; exit 1 }
    try { $res = $raw | ConvertFrom-Json } catch { Fail "응답 해석 실패:"; Write-Host $raw; exit 1 }
    if (-not $res.success) { Fail "$($res.message)  [code=$($res.error.code)]"; exit 1 }
    $VideoId = [int]$res.data.videoId
    Ok "videoId = $VideoId / jobId = $($res.data.jobId)"
}

# ---------------------------------------------------------- 4. 진행 상황
$status = (Get-Json "$Backend/api/v1/videos/$VideoId/status").data
if (-not $status) { Fail "videoId $VideoId 상태를 가져오지 못했습니다."; exit 1 }

if ($status.status -ne "COMPLETED" -and $status.status -ne "FAILED" -and $status.status -ne "CANCELLED") {
    Section "4. 진행 상황"
    Write-Host "  (Ctrl+C 로 나가도 분석은 계속됩니다. 다시 보려면 -VideoId $VideoId)"
    Write-Host ""
    $started = Get-Date
    $deadline = $started.AddMinutes($TimeoutMinutes)
    $last = ""
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $s = (Get-Json "$Backend/api/v1/videos/$VideoId/status").data
        if (-not $s) { continue }
        $status = $s
        $key = "$($s.status)|$($s.stage)|$($s.progress)|$($s.message)"
        if ($key -ne $last) {
            $bar = ("#" * [math]::Floor($s.progress / 5)).PadRight(20, ".")
            $el = ((Get-Date) - $started).ToString("mm\:ss")
            Write-Host ("  [{0}] {1} {2,3}%  {3} - {4}" -f $el, $bar, $s.progress, $s.stage, $s.message)
            $last = $key
        }
        if ($s.status -eq "COMPLETED" -or $s.status -eq "FAILED" -or $s.status -eq "CANCELLED") { break }
    }
    Ok ("소요 {0}" -f ((Get-Date) - $started).ToString("mm\:ss"))
}

if ($status.status -eq "FAILED") {
    Fail "분석 실패: $($status.failure.code) $($status.failure.message)"
    Hint "백엔드 창의 [pipeline] / [analysis-server] 로그를 확인하세요."
    exit 1
}
if ($status.status -eq "CANCELLED") { Warn "취소된 분석입니다."; exit 0 }
if ($status.status -ne "COMPLETED") { Warn "아직 끝나지 않았습니다. 나중에 -VideoId $VideoId 로 다시 보세요."; exit 0 }

# ---------------------------------------------------------- 5. 전사
Section "5. 전사"
$transcript = @((Get-Json "$Backend/api/v1/videos/$VideoId/transcript").data)
Write-Host ("  대본 {0}줄" -f $transcript.Count)
$transcript | Select-Object -First 3 | ForEach-Object { Write-Host ("    {0}  {1}" -f (Fmt-Time $_.startMs), $_.text) -ForegroundColor DarkGray }

# ---------------------------------------------------------- 6. 결과
Section "6. 확인할 지점"
$report = (Get-Json "$Backend/api/v1/videos/$VideoId/report").data
if (-not $report) { Fail "리포트를 가져오지 못했습니다."; exit 1 }

foreach ($w in @($report.warnings)) {
    if ($null -eq $w) { continue }
    Warn ("{0} {1}: {2}" -f $w.stage, $w.code, $w.message)
}

$events = @($report.events) | Where-Object { $null -ne $_ }
if (@($events).Count -eq 0) {
    Write-Host ""
    Write-Host "  확인할 지점을 찾지 못했습니다." -ForegroundColor Green
    Hint "위에 [WARN] 이 있으면 일부 단계를 못 돈 것입니다. 없으면 실제로 걸린 것이 없는 것입니다."
} else {
    Write-Host ("  {0}곳" -f @($events).Count) -ForegroundColor Magenta
    $rank = 0
    foreach ($e in $events) {
        $rank++
        $fact = $e.candidateType -eq "FACT_CHECK"
        $kind = if ($fact) { "사실대조" } else { "맥락 " + $e.criterionId }
        $color = if ($fact) { "Yellow" } else { "Red" }
        $ts = Fmt-Time $e.startMs
        if (($e.endMs - $e.startMs) -gt 4000) { $ts += " ~ " + (Fmt-Time $e.endMs) }

        Write-Host ""
        Write-Host ("  #{0}  {1}  [{2}]  {3}" -f $rank, $ts, $kind, $e.title) -ForegroundColor $color
        if ($e.contextBefore) { Write-Host ("      앞: {0}" -f $e.contextBefore) -ForegroundColor DarkGray }
        Write-Host ("      발언: {0}" -f $e.text)
        if ($e.contextAfter) { Write-Host ("      뒤: {0}" -f $e.contextAfter) -ForegroundColor DarkGray }
        foreach ($line in (("" + $e.reason) -split "`n")) { if ($line) { Write-Host ("      이유: {0}" -f $line) } }
        foreach ($ref in @($e.references)) {
            if ($null -eq $ref) { continue }
            Write-Host ("      출처: {0} ({1}) {2}" -f $ref.title, $ref.provider, $ref.url) -ForegroundColor DarkCyan
        }
    }
}

Section "끝"
Write-Host "  전체 JSON  : curl.exe $Backend/api/v1/videos/$VideoId/report"
Write-Host "  결과 재확인: .\scripts\smoke-test.ps1 -VideoId $VideoId"
Write-Host "  내부 기록  : uploads\context-traces\$VideoId\ , uploads\fact-traces\$VideoId\"
