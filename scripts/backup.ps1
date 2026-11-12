<#
  记事本本地备份
  登录 → 拉取 /api/export → 落到本地目录 → 只保留最近 N 份

  用法：
    powershell -NoProfile -File scripts\backup.ps1
    powershell -NoProfile -File scripts\backup.ps1 -Keep 60 -OutDir D:\code\notes-backup

  密码来源优先级：-Password 参数 → 环境变量 NOTES_PASSWORD → 项目 .dev.vars 里的 APP_PASSWORD
#>

param(
  [string]$BaseUrl = 'https://note.wenjun.eu.org',
  [string]$OutDir = 'D:\code\notes-backup',
  [int]$Keep = 30,
  [string]$Password = ''
)

$ErrorActionPreference = 'Stop'
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) notes-backup/1.0'

if (-not $Password) { $Password = $env:NOTES_PASSWORD }

if (-not $Password) {
  $vars = Join-Path -Path $PSScriptRoot -ChildPath '..\.dev.vars'
  if (Test-Path -LiteralPath $vars) {
    $line = Get-Content -LiteralPath $vars -Encoding utf8 |
      Where-Object { $_ -match '^APP_PASSWORD=' } |
      Select-Object -First 1
    if ($line) { $Password = $line.Substring('APP_PASSWORD='.Length).Trim() }
  }
}

if (-not $Password) {
  throw '缺少密码：设置 NOTES_PASSWORD 环境变量，或在 .dev.vars 里写入 APP_PASSWORD'
}

if (-not (Test-Path -LiteralPath $OutDir)) {
  New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}
$logFile = Join-Path $OutDir 'backup.log'

function Write-Log([string]$text) {
  $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text
  Add-Content -LiteralPath $logFile -Value $line -Encoding utf8
}

try {
  $login = Invoke-RestMethod -Uri "$BaseUrl/api/login" -Method Post `
    -ContentType 'application/json' `
    -Body (@{ password = $Password } | ConvertTo-Json) `
    -UserAgent $UA

  if (-not $login.token) { throw '登录没有返回 token' }

  $stamp = Get-Date -Format 'yyyy-MM-dd'
  $outFile = Join-Path $OutDir ("notes-{0}.json" -f $stamp)

  Invoke-WebRequest -Uri "$BaseUrl/api/export" `
    -Headers @{ Authorization = "Bearer $($login.token)" } `
    -UserAgent $UA `
    -OutFile $outFile | Out-Null

  $raw = Get-Content -LiteralPath $outFile -Raw -Encoding utf8
  $data = $raw | ConvertFrom-Json
  if (-not $data.notes) { throw '导出文件里没有 notes 字段，备份可能不完整' }
  $count = @($data.notes).Count

  $size = (Get-Item -LiteralPath $outFile).Length
  Write-Log ("OK {0} 条笔记, {1} KB -> {2}" -f $count, [math]::Round($size / 1024, 1), $outFile)

  $old = Get-ChildItem -LiteralPath $OutDir -Filter 'notes-*.json' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip $Keep
  foreach ($f in $old) {
    Remove-Item -LiteralPath $f.FullName -Force
    Write-Log ("清理旧备份 {0}" -f $f.Name)
  }

  Write-Output ("backup ok: {0} 条笔记, {1} KB -> {2}" -f $count, [math]::Round($size / 1024, 1), $outFile)
} catch {
  Write-Log ("FAIL {0}" -f $_.Exception.Message)
  Write-Error $_.Exception.Message
  exit 1
}
