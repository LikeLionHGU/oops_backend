# 검색 API 키가 실제로 동작하는지 확인한다 (고도화 v2)
#
#   .\scripts\check-search-keys.ps1
#
# src\main\resources\application-secret.yml 의 oops.search 키를 읽어서
# Perplexity / Serper 에 검색을 한 번씩 보낸다. 키는 화면에 찍지 않는다.
#   200 = 정상 / 401·403 = 키 문제 / 429 = 한도 초과 / 000 = 네트워크 문제

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$secret = Join-Path $PSScriptRoot "..\src\main\resources\application-secret.yml"
if (-not (Test-Path $secret)) { Write-Host "[FAIL] application-secret.yml 이 없습니다." -ForegroundColor Red; exit 1 }
$yml = Get-Content $secret -Raw -Encoding UTF8

function Read-Key($section) {
    $m = [regex]::Match($yml, "(?ms)^\s+$section\s*:\s*\r?\n\s+api-key\s*:\s*([^\s#]+)")
    if ($m.Success) { return $m.Groups[1].Value.Trim('"', "'") }
    return $null
}

function Post-Json($url, $headers, $json) {
    $tmp = [IO.Path]::GetTempFileName()
    $out = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($tmp, $json, [Text.UTF8Encoding]::new($false))
    $curlArgs = @("-s", "-m", "30", "-o", $out, "-w", "%{http_code}", "-X", "POST", $url, "-H", "Content-Type: application/json", "-d", "@$tmp")
    foreach ($h in $headers) { $curlArgs += @("-H", $h) }
    $code = & curl.exe @curlArgs
    $body = Get-Content $out -Raw -Encoding UTF8
    Remove-Item $tmp, $out -ErrorAction SilentlyContinue
    return @{ code = $code; body = $body }
}

function Report($name, $res, $listField) {
    if ($res.code -eq "200") {
        $n = 0
        try { $n = @(($res.body | ConvertFrom-Json).$listField).Count } catch { }
        Write-Host "[OK]   $name : HTTP 200, 결과 $n 건" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $name : HTTP $($res.code)" -ForegroundColor Red
        if ($res.code -eq "401" -or $res.code -eq "403") { Write-Host "       키가 틀렸거나 만료됐습니다." -ForegroundColor DarkGray }
        if ($res.code -eq "429") { Write-Host "       요청 한도 초과입니다. 잠시 뒤 다시 해 보세요." -ForegroundColor DarkGray }
        if ($res.code -eq "000") { Write-Host "       네트워크 연결 문제입니다." -ForegroundColor DarkGray }
    }
}

$query = "방탄소년단 데뷔 연도"

$pk = Read-Key "perplexity"
if ($pk) {
    $res = Post-Json "https://api.perplexity.ai/search" @("Authorization: Bearer $pk") `
        (@{ query = $query; max_results = 3; search_type = "web"; country = "KR"; max_tokens_per_page = 256 } | ConvertTo-Json -Compress)
    Report "Perplexity" $res "results"
} else { Write-Host "[SKIP] Perplexity 키 없음" -ForegroundColor Yellow }

$sk = Read-Key "serper"
if ($sk) {
    $res = Post-Json "https://google.serper.dev/search" @("X-API-KEY: $sk") `
        (@{ q = $query; gl = "kr"; hl = "ko"; num = 3 } | ConvertTo-Json -Compress)
    Report "Serper(웹)" $res "organic"
    $res = Post-Json "https://google.serper.dev/news" @("X-API-KEY: $sk") `
        (@{ q = $query; gl = "kr"; hl = "ko"; num = 3 } | ConvertTo-Json -Compress)
    Report "Serper(뉴스)" $res "news"
} else { Write-Host "[SKIP] Serper 키 없음" -ForegroundColor Yellow }
