# Spring 을 빼고 Python 분석 서버만 직접 두드려 보는 진단 스크립트
#
#   .\diagnose.ps1 -File "C:\경로\1.mp4"
#
# 원본 파일과 Spring 이 저장한 파일 양쪽을 다 확인할 수 있다.

param(
    [Parameter(Mandatory=$true)][string]$File,
    [string]$Analysis = "http://localhost:8000"
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Section($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Warn($t) { Write-Host "  [WARN] $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "  [FAIL] $t" -ForegroundColor Red }

if (-not (Test-Path $File)) { Fail "파일 없음: $File"; exit 1 }
$full = (Get-Item $File).FullName

# ---------------------------------------------------------- 1. 파일 자체 점검
Section "1. 영상 파일 점검"
Write-Host "  경로: $full"
Write-Host ("  크기: {0} MB" -f [math]::Round((Get-Item $File).Length / 1MB, 2))

if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) {
    Warn "ffprobe 없음 - 스트림 확인을 건너뜁니다."
} else {
    $streams = ffprobe -v error -show_entries stream=index,codec_type,codec_name -of csv=p=0 "$full" 2>&1
    Write-Host "  스트림:"
    $streams | ForEach-Object { Write-Host "    $_" }

    $hasAudio = $streams -match "audio"
    $hasVideo = $streams -match "video"

    if ($hasAudio) { Ok "오디오 트랙 있음 -> STT 가능" }
    else { Fail "오디오 트랙 없음! -> STT 가 절대 안 됩니다. 소리 있는 영상으로 바꾸세요." }

    if ($hasVideo) { Ok "비디오 트랙 있음 -> OCR 가능" }
    else { Warn "비디오 트랙 없음 -> OCR 불가" }

    $dur = ffprobe -v error -show_entries format=duration -of csv=p=0 "$full" 2>&1
    Write-Host "  길이: $dur 초"
}

# ---------------------------------------------------------- 2. 분석 서버 상태
Section "2. 분석 서버"
$health = curl.exe -s "$Analysis/health"
if (-not $health) { Fail "응답 없음. run.ps1 로 서버를 먼저 띄우세요."; exit 1 }
Write-Host "  $health"

# ---------------------------------------------------------- 3. STT 직접 호출
Section "3. /transcribe 직접 호출"
Write-Host "  (Whisper 호출이라 영상 길이만큼 걸립니다)"

# PowerShell 에서 JSON 을 인라인으로 넘기면 따옴표가 깨진다. 임시 파일로 넘긴다.
$tmp = [IO.Path]::GetTempFileName()
@{ filePath = $full } | ConvertTo-Json -Compress | Set-Content -Path $tmp -Encoding UTF8

$sttRaw = curl.exe -s -S -X POST "$Analysis/transcribe" -H "Content-Type: application/json" -d "@$tmp"
Write-Host ""
if (-not $sttRaw) {
    Fail "응답 없음"
} else {
    try {
        $stt = $sttRaw | ConvertFrom-Json
        if ($stt.detail) {
            Fail "서버 오류: $($stt.detail)"
        } else {
            $n = @($stt.segments).Count
            if ($n -gt 0) {
                Ok "대본 $n 줄 (language=$($stt.language))"
                @($stt.segments) | Select-Object -First 5 | ForEach-Object {
                    Write-Host ("    {0,7}ms  {1}" -f $_.startMs, $_.text)
                }
            } else {
                Fail "대본 0줄 - 무음이거나 인식 실패입니다."
            }
        }
    } catch {
        Fail "응답 해석 실패. 원문:"
        Write-Host $sttRaw
    }
}

# ---------------------------------------------------------- 4. OCR 직접 호출
Section "4. /ocr 직접 호출"

$frameDir = Join-Path $env:TEMP "videoguard-diag-frames"
@{ filePath = $full; intervalSec = 2.0; frameDir = $frameDir } | ConvertTo-Json -Compress | Set-Content -Path $tmp -Encoding UTF8

$ocrRaw = curl.exe -s -S -X POST "$Analysis/ocr" -H "Content-Type: application/json" -d "@$tmp"
Write-Host ""
if (-not $ocrRaw) {
    Fail "응답 없음"
} else {
    try {
        $ocr = $ocrRaw | ConvertFrom-Json
        if ($ocr.detail) {
            Fail "서버 오류: $($ocr.detail)"
        } else {
            $n = @($ocr.items).Count
            if ($n -gt 0) {
                Ok "화면 자막 $n 건"
                @($ocr.items) | Select-Object -First 5 | ForEach-Object {
                    Write-Host ("    {0,7}ms  {1}" -f $_.startMs, $_.text)
                }
                Write-Host "  프레임 저장 위치: $frameDir"
            } else {
                Warn "화면 자막 0건 - 영상에 글자가 없거나 인식하지 못했습니다."
            }
        }
    } catch {
        Fail "응답 해석 실패. 원문:"
        Write-Host $ocrRaw
    }
}

Remove-Item $tmp -ErrorAction SilentlyContinue

Section "정리"
Write-Host "  여기서 성공하는데 Spring 을 거치면 실패한다면 -> 경로 전달 문제입니다."
Write-Host "  여기서도 실패한다면 -> Python 서버 창의 traceback 을 확인하세요."
