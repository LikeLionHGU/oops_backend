# 영상 하나를 끝까지 돌려보는 확인 스크립트 (API 명세 v1 기준)
#
#   .\scripts\smoke-test.ps1 -File "C:\경로\영상.mp4"
#   .\scripts\smoke-test.ps1 -Url  "https://www.youtube.com/watch?v=..."
#   .\scripts\smoke-test.ps1 -VideoId 1      # 이미 분석한 영상 결과만 다시 보기 (재분석 안 함)

param(
    [string]$Url,
    [string]$File,
    [int]$VideoId = 0,
    [string]$Backend = "http://localhost:8080",
    [string]$Analysis = "http://localhost:8000"
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

# Invoke-RestMethod 는 PowerShell 5.1 에서 응답을 ISO-8859-1 로 해석해 한글이 깨진다.
# curl.exe 로 받아서 직접 UTF-8 로 파싱한다.
function Get-Json($url) {
    $raw = curl.exe -s $url
    if (-not $raw) { return $null }
    try { return ($raw | ConvertFrom-Json) } catch { return $null }
}

# ---------------------------------------------------------- 0. 사전 점검
Section "0. 준비물 확인"

if ($VideoId -eq 0 -and -not $Url -and -not $File) {
    Fail "-File, -Url, -VideoId 중 하나는 필요합니다."
    exit 1
}
if ($File -and -not (Test-Path $File)) { Fail "파일 없음: $File"; exit 1 }

if (Get-Command curl.exe -ErrorAction SilentlyContinue) { Ok "curl.exe" }
else { Fail "curl.exe 없음"; exit 1 }

if ($VideoId -eq 0) {
    if (Get-Command ffmpeg -ErrorAction SilentlyContinue) { Ok "ffmpeg" }
    else { Fail "ffmpeg 없음 -> winget install Gyan.FFmpeg"; exit 1 }
}
Ok "PowerShell $($PSVersionTable.PSVersion)"

# ---------------------------------------------------------- 1~2. 서버 확인
if ($VideoId -eq 0) {
    Section "1. Python 분석 서버"
    $health = Get-Json "$Analysis/health"
    if (-not $health) { Fail "분석 서버 응답 없음. oops-analysis 에서 .\run.ps1 실행"; exit 1 }
    Ok "응답함"
    if ($health.sttAvailable) { Ok "STT 사용 가능" } else { Warn "STT 불가 - .env 의 OPENAI_API_KEY 확인" }
    if ($health.ocrAvailable) { Ok "OCR 사용 가능" } else { Warn "OCR 불가 - 화면 분석은 건너뜁니다" }
}

Section "2. Spring 백엔드"
$ping = curl.exe -s -o NUL -w "%{http_code}" "$Backend/api/v1/videos/999999/status"
if ($ping -eq "000") { Fail "백엔드 응답 없음. IntelliJ 에서 실행하세요."; exit 1 }
Ok "응답함 (HTTP $ping)"

# ---------------------------------------------------------- 3. 등록
if ($VideoId -eq 0) {
    Section "3. 영상 등록 (업로드 즉시 분석 시작)"
    if ($File) {
        $item = Get-Item $File
        Write-Host ("  업로드 중... ({0} MB)" -f [math]::Round($item.Length / 1MB, 1))
        $raw = curl.exe -s -S -X POST "$Backend/api/v1/videos" -F "file=@$($item.FullName)"
    } else {
        # JSON 을 인라인으로 넘기면 PowerShell 이 큰따옴표를 벗겨서
        # 서버가 {url:...} 을 받고 파싱에 실패한다. 임시 파일로 넘긴다.
        $tmp = [IO.Path]::GetTempFileName()
        @{ url = $Url } | ConvertTo-Json -Compress | Set-Content -Path $tmp -Encoding UTF8 -NoNewline
        $raw = curl.exe -s -S -X POST "$Backend/api/v1/videos" `
                        -H "Content-Type: application/json" -d "@$tmp"
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
    if (-not $raw) { Fail "업로드 응답이 비었습니다."; exit 1 }
    try { $res = $raw | ConvertFrom-Json } catch { Fail "응답 해석 실패:"; Write-Host $raw; exit 1 }
    if (-not $res.success) {
        Fail "$($res.message)  [code=$($res.error.code) trace=$($res.error.traceId)]"
        exit 1
    }
    $VideoId = $res.data.videoId
    Ok "videoId = $VideoId / jobId = $($res.data.jobId)"

    # ------------------------------------------------------ 4. 진행률
    Section "4. 진행 상황"
    Write-Host "  (Ctrl+C 로 나가도 분석은 계속됩니다)"
    Write-Host ""

    $deadline = (Get-Date).AddMinutes(30)
    $status = $null
    $started = Get-Date
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $status = (Get-Json "$Backend/api/v1/videos/$VideoId/status").data
        if (-not $status) { continue }

        $bar = ("#" * [math]::Floor($status.progress / 5)).PadRight(20, ".")
        $label = "$($status.stage) - $($status.message)"
        Write-Host ("`r  [{0}] {1,3}%  {2}" -f $bar, $status.progress, $label.PadRight(42)) -NoNewline

        if ($status.status -eq "COMPLETED" -or $status.status -eq "FAILED") { break }
    }
    Write-Host ""
    $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)

    if ($status.status -ne "COMPLETED") {
        Fail "분석 실패: $($status.message)"
        Hint "IntelliJ 콘솔에서 [pipeline] 로그를 확인하세요."
        exit 1
    }
    Ok "분석 완료 (소요 $elapsed 초)"
    if ($elapsed -lt 10) {
        Warn "너무 빨리 끝났습니다. STT/OCR 이 실제로 돌지 않았을 가능성이 큽니다."
    }
}

# ---------------------------------------------------------- 5. 진단
Section "5. 추출된 원문 (여기가 진단의 핵심)"
$transcript = (Get-Json "$Backend/api/v1/videos/$VideoId/transcript").data
$screen     = (Get-Json "$Backend/api/v1/videos/$VideoId/screen-texts").data

$tCount = @($transcript).Count
$sCount = @($screen).Count

Write-Host "  STT 대본     : $tCount 줄"
if ($tCount -eq 0) {
    Fail "대본이 비어 있습니다. -> 발언 관련 분석기가 전부 스킵됩니다."
    Hint "Python 서버 창에 [stt] 로그가 찍혔는지 확인하세요."
    Hint "직접 테스트: curl.exe -s -X POST $Analysis/transcribe -H `"Content-Type: application/json`" -d '{\"filePath\":\"영상경로\"}'"
} else {
    @($transcript) | Select-Object -First 5 | ForEach-Object {
        Write-Host ("    {0,7}ms  {1}" -f $_.startMs, $_.text)
    }
    if ($tCount -gt 5) { Write-Host "    ... 외 $($tCount - 5)줄" -ForegroundColor DarkGray }
}

Write-Host ""
Write-Host "  화면 자막(OCR): $sCount 건"
if ($sCount -eq 0) {
    Warn "화면 자막이 없습니다. -> 화면 관련 분석기가 스킵됩니다."
    Hint "영상에 글자가 안 나오거나, OCR 이 인식하지 못했습니다."
} else {
    @($screen) | Select-Object -First 5 | ForEach-Object {
        Write-Host ("    {0,7}ms  {1}" -f $_.startMs, $_.text)
    }
    if ($sCount -gt 5) { Write-Host "    ... 외 $($sCount - 5)건" -ForegroundColor DarkGray }
}

# ---------------------------------------------------------- 6. 리포트
Section "6. Timeline Report"
$report = (Get-Json "$Backend/api/v1/videos/$VideoId/report").data
if (-not $report) { Fail "리포트를 가져오지 못했습니다."; exit 1 }

$events = @($report.events)
Write-Host ""
Write-Host ("  심각도: HIGH {0} / MEDIUM {1} / LOW {2}" -f `
    $report.summary.high, $report.summary.medium, $report.summary.low) -ForegroundColor Magenta

if ($events.Count -eq 0) {
    Write-Host ""
    Write-Host "  탐지된 논란 요소가 없습니다." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  가능한 원인:" -ForegroundColor DarkGray
    if ($tCount -eq 0) {
        Hint "1) STT 실패로 분석할 텍스트 자체가 없었습니다. (가장 유력)"
    } else {
        Hint "1) 룰 사전에 없는 표현입니다. RiskRuleEngine 의 KEYWORDS 를 확인하세요."
        Hint "2) LLM 분석기가 스킵됐을 수 있습니다."
        Hint "   -> IntelliJ 콘솔에서 'speech-risk 스킵' 이 보이면 Spring 에 OPENAI_API_KEY 가 없는 것입니다."
        Hint "   -> Run/Debug Configurations > Environment variables 확인"
        Hint "3) LLM 이 문제없다고 판단했습니다. 콘솔의 '[speech-risk] ... findings=0' 로 구분됩니다."
    }
} else {
    $rank = 0
    foreach ($e in $events) {
        $rank++
        $color = switch ($e.severity) { "HIGH" { "Red" } "MEDIUM" { "Yellow" } default { "Gray" } }
        $ts = [TimeSpan]::FromMilliseconds($e.startMs).ToString("mm\:ss")
        if (($e.endMs - $e.startMs) -gt 4000) {
            $ts += " ~ " + [TimeSpan]::FromMilliseconds($e.endMs).ToString("mm\:ss")
        }
        $rep = if ($e.occurrences -gt 1) { " ({0}회 반복)" -f $e.occurrences } else { "" }

        Write-Host ""
        Write-Host ("  #{0}  {1}  [{2}]  {3}{4}" -f $rank, $ts, $e.type, $e.severity, $rep) -ForegroundColor $color
        if ($e.type -eq "SPEECH") {
            Write-Host ("      발언: {0}" -f $e.text)
            Write-Host ("      유형: {0}" -f ($e.riskTypes -join ", "))
        } else {
            Write-Host ("      발언: {0}" -f $e.speechText)
            Write-Host ("      자막: {0}" -f $e.captionText)
        }
        Write-Host ("      사유: {0}" -f $e.reason)
        if ($e.frameUrl) { Write-Host ("      캡처: {0}{1}" -f $Backend, $e.frameUrl) -ForegroundColor DarkGray }
    }
}

Write-Host ""
Section "끝"
Write-Host "  전체 JSON  : curl.exe $Backend/api/v1/videos/$VideoId/report"
Write-Host "  결과 재확인: .\scripts\smoke-test.ps1 -VideoId $VideoId    (재분석 없이 조회만)"
