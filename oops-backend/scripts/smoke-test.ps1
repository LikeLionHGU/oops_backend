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

# ---------------------------------------------------------- 7. 내부 기록
# 사용자 화면에는 안 나오는 것: 후보 탐색 → 독립 점검 → 상세 검토 각 단계에서 무엇이 어떻게 갈렸는지.
# 스크립트가 oops-backend 폴더에서 돈다는 가정으로 uploads\context-traces 를 직접 읽는다.
Section "7. 맥락 확인 내부 기록 (화면에는 안 나옴)"
$traceDir = Join-Path (Get-Location) "uploads\context-traces\$VideoId"
$traceFile = Get-ChildItem $traceDir -Filter *.json -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $traceFile) {
    Warn "내부 기록 파일이 없습니다: $traceDir  (oops-backend 폴더에서 실행했는지 확인)"
} else {
    $doc = [System.IO.File]::ReadAllText($traceFile.FullName, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $trace = @($doc.trace) | Where-Object { $null -ne $_ }
    $disc = @($trace | Where-Object { $_.stage -eq "DISCOVERY" -and $_.outcome -eq "CANDIDATE" })
    # 문맥을 한 번 확대하면 같은 후보의 점검 기록이 두 줄 생긴다. 마지막 것이 최종 판단이다.
    $gate = @($trace | Where-Object { $_.stage -eq "GATE" } | Group-Object candidateId | ForEach-Object { $_.Group | Select-Object -Last 1 })
    $rev  = @($trace | Where-Object { $_.stage -eq "REVIEW" })
    $pass = @($gate | Where-Object { $_.outcome -eq "PROCEED" })
    Write-Host ("  기준 {0} · AI 호출 {1}회(실패 {2})" -f $doc.criteriaVersion, $doc.modelCalls, $doc.failedCalls) -ForegroundColor DarkGray
    Write-Host ("  후보 탐색 {0}건 → 독립 점검 통과 {1}건 (제외 {2} · 중단 {3}) → 상세 검토 결과 {4}건" -f `
        $disc.Count, $pass.Count, @($gate | Where-Object { $_.outcome -eq "EXCLUDE" }).Count,
        @($gate | Where-Object { $_.outcome -eq "STOP" }).Count, @($rev | Where-Object { $_.outcome -eq "REVIEW" }).Count)

    # 구간 ID → 대본 줄. stt-index-N 은 대본 N번째 줄(0부터)이다.
    function Line-Of($segId) {
        if ($segId -match '^stt-index-(\d+)$') {
            $i = [int]$Matches[1]
            if ($i -lt $transcript.Count) { return "{0}  {1}" -f (Fmt-Time $transcript[$i].startMs), $transcript[$i].text }
        }
        return $segId
    }

    foreach ($x in @($trace | Where-Object { $_.stage -eq "DISCOVERY" -and $_.outcome -ne "CANDIDATE" -and $_.outcome -ne "SKIPPED_BUDGET" })) {
        Warn ("후보 탐색 {0}: {1} {2}" -f $x.outcome, $x.note, (@($x.missingInformation) -join ","))
    }

    if ($pass.Count -eq 0) {
        Write-Host "  독립 점검을 통과한 후보가 없습니다." -ForegroundColor DarkGray
    }
    foreach ($g in $pass) {
        $r = $rev | Where-Object { $_.candidateId -eq $g.candidateId } | Select-Object -Last 1
        Write-Host ""
        Write-Host ("  [{0}] {1}" -f $g.candidateId, $g.criterionId) -ForegroundColor White
        foreach ($sid in @($g.coreSegmentIds)) { Write-Host ("      대본: {0}" -f (Line-Of $sid)) -ForegroundColor Gray }
        $d = $disc | Where-Object { $_.candidateId -eq $g.candidateId } | Select-Object -First 1
        if ($d) { Write-Host ("      탐색: {0}" -f $d.reason) -ForegroundColor DarkGray }
        Write-Host ("      점검: 통과 — {0}" -f $g.reason)
        if (@($g.allowedJudgments).Count) { Write-Host ("      허용: {0}" -f (@($g.allowedJudgments) -join " / ")) -ForegroundColor DarkCyan }
        if (@($g.stoppedJudgments).Count) { Write-Host ("      중단: {0}" -f (@($g.stoppedJudgments) -join ", ")) -ForegroundColor DarkYellow }
        if ($g.expanded) { Write-Host "      (문맥 확대 후 판단)" -ForegroundColor DarkGray }
        if ($r) {
            $c = if ($r.outcome -eq "REVIEW") { "Red" } else { "Yellow" }
            $label = if ($r.outcome -eq "REVIEW") { "결과로 표시" } else { "제외" }
            Write-Host ("      상세: {0} — {1}" -f $label, $r.reason) -ForegroundColor $c
        } else {
            Write-Host "      상세: 기록 없음 (호출 실패 또는 무효 출력)" -ForegroundColor Yellow
        }
    }
    $other = @($gate | Where-Object { $_.outcome -ne "PROCEED" })
    if ($other.Count) {
        Write-Host ""
        Write-Host "  독립 점검에서 멈춘 후보:" -ForegroundColor DarkGray
        foreach ($g in $other) {
            Write-Host ("    [{0}] {1} {2} — {3} | {4}" -f $g.candidateId, $g.criterionId, $g.outcome, $g.reason,
                ((@($g.coreSegmentIds) | ForEach-Object { Line-Of $_ }) -join " / ")) -ForegroundColor DarkGray
        }
    }
}

Section "끝"
Write-Host "  전체 JSON  : curl.exe $Backend/api/v1/videos/$VideoId/report"
Write-Host "  결과 재확인: .\scripts\smoke-test.ps1 -VideoId $VideoId"
Write-Host "  내부 기록  : uploads\context-traces\$VideoId\ , uploads\fact-traces\$VideoId\"
