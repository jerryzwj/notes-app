# 一条命令出 APK：同步网页 + cap sync + gradle assembleDebug
# 依赖：node 22、JDK 17、Android SDK（platforms;android-34 / build-tools;34.0.0 / platform-tools）
# 用法：powershell -File D:\code\notes-app\android-app\build-apk.ps1

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'D:\Android\sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\sdk'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# 1. 把 ../../public 同步到 www
node scripts/copy-web.js
if ($LASTEXITCODE -ne 0) { throw 'copy-web 失败' }

# 2. 让 Capacitor 把 www 塞进 android 工程
npx cap sync android
if ($LASTEXITCODE -ne 0) { throw 'cap sync 失败' }

# 3. 编译
Set-Location (Join-Path $root 'android')
.\gradlew.bat assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'gradle 失败' }

$apk = Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw '没找到 APK' }
Get-Item -LiteralPath $apk | Select-Object FullName, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } }, LastWriteTime
