# Python 분석 서버 실행 스크립트
#   .\run.ps1           서버 시작
#   .\run.ps1 -Setup    가상환경 생성 + 패키지 설치 후 시작
#   .\run.ps1 -Reset    기존 가상환경을 지우고 처음부터 다시

param(
    [switch]$Setup,
    [switch]$Reset
)

# PowerShell 5.1 에서 한글이 깨지지 않게
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Info($t) { Write-Host $t -ForegroundColor Cyan }
function Ok($t)   { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Warn($t) { Write-Host "  [WARN] $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "  [FAIL] $t" -ForegroundColor Red }

# ---------------------------------------------------------- 파이썬 찾기
function Resolve-Python {
    $candidates = @(
        @{ Exe = "py";     Pre = @("-3.11") },
        @{ Exe = "py";     Pre = @("-3.10") },
        @{ Exe = "py";     Pre = @("-3")    },
        @{ Exe = "python"; Pre = @()        },
        @{ Exe = "python3"; Pre = @()       }
    )
    foreach ($c in $candidates) {
        if (-not (Get-Command $c.Exe -ErrorAction SilentlyContinue)) { continue }
        try {
            $out = (& $c.Exe @($c.Pre + @("--version")) 2>&1 | Out-String).Trim()
        } catch { continue }
        if ($out -match "Python 3\.(\d+)") {
            return [pscustomobject]@{
                Exe     = $c.Exe
                Pre     = $c.Pre
                Version = $Matches[0]
                Minor   = [int]$Matches[1]
            }
        }
    }
    return $null
}

Info "`n[0] 파이썬 확인"
$py = Resolve-Python
if (-not $py) {
    Fail "파이썬을 찾을 수 없습니다."
    Write-Host ""
    Write-Host "  python.org 에서 3.11 을 설치하세요: https://www.python.org/downloads/release/python-3119/"
    Write-Host "  설치할 때 'Add python.exe to PATH' 체크를 꼭 켜세요."
    Write-Host ""
    Write-Host "  참고: Microsoft Store 스텁이 걸려 있으면 아래를 끄면 됩니다."
    Write-Host "        설정 > 앱 > 고급 앱 설정 > 앱 실행 별칭 > python.exe 끄기"
    exit 1
}
Ok "$($py.Version)  ($($py.Exe) $($py.Pre -join ' '))"
if ($py.Minor -ge 12) {
    Warn "3.12 이상은 PaddleOCR 설치가 실패할 수 있습니다. STT 는 정상 동작합니다."
}

# ---------------------------------------------------------- 가상환경
$activate = ".\.venv\Scripts\Activate.ps1"

if ($Reset -and (Test-Path ".venv")) {
    Info "`n[1] 기존 가상환경 삭제"
    Remove-Item ".venv" -Recurse -Force
    Ok "삭제됨"
}

if (Test-Path ".venv" -PathType Container) {
    if (-not (Test-Path $activate)) {
        Warn "가상환경이 손상되어 다시 만듭니다."
        Remove-Item ".venv" -Recurse -Force
    }
}

if (-not (Test-Path ".venv")) {
    Info "`n[1] 가상환경 생성"
    & $py.Exe @($py.Pre + @("-m", "venv", ".venv"))
    if (-not (Test-Path $activate)) {
        Fail "가상환경 생성 실패."
        Write-Host "  아래를 직접 실행해 보고 나오는 메시지를 확인하세요:"
        Write-Host "    $($py.Exe) $($py.Pre -join ' ') -m venv .venv"
        exit 1
    }
    Ok "생성됨"
    $Setup = $true
}

& $activate
Ok "가상환경 활성화"

# ---------------------------------------------------------- 패키지 설치
if ($Setup) {
    Info "`n[2] 기본 패키지 설치 (STT)"
    python -m pip install --upgrade pip --quiet
    pip install -r requirements-core.txt
    if ($LASTEXITCODE -ne 0) {
        Fail "기본 패키지 설치 실패. 위 메시지를 확인하세요."
        exit 1
    }
    Ok "완료"

    Info "`n[3] OCR 패키지 설치 (실패해도 계속 진행)"
    $ErrorActionPreference = "Continue"
    pip install -r requirements-ocr.txt
    if ($LASTEXITCODE -ne 0) {
        Warn "OCR 설치 실패 - 화면 자막 분석 없이 진행합니다."
        Warn "파이썬 3.10 / 3.11 이면 대체로 해결됩니다."
    } else {
        Ok "완료"
    }
    $ErrorActionPreference = "Stop"
}

# ---------------------------------------------------------- .env 확인
Info "`n[4] 설정 확인"
if (-not (Test-Path ".env")) {
    Fail ".env 파일이 없습니다. .env.example 을 복사해서 키를 넣으세요."
    exit 1
}
if (Select-String -Path ".env" -Pattern "^OPENAI_API_KEY=.+" -Quiet) {
    Ok "OPENAI_API_KEY 설정됨"
} else {
    Warn "OPENAI_API_KEY 가 비어 있습니다. STT 가 동작하지 않습니다."
}

if (Get-Command ffmpeg -ErrorAction SilentlyContinue) {
    Ok "ffmpeg 설치됨"
} else {
    Warn "ffmpeg 없음 - 영상 처리가 실패합니다. winget install Gyan.FFmpeg"
}

# ---------------------------------------------------------- 실행
Write-Host ""
Write-Host "서버 시작 -> http://localhost:8000/health" -ForegroundColor Green
Write-Host "이 창은 그대로 두고, 다른 터미널에서 테스트하세요. (종료: Ctrl+C)" -ForegroundColor Gray
Write-Host ""

uvicorn app.main:app --host 127.0.0.1 --port 8000
