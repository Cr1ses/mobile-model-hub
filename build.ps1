# 构建 APK（不依赖 Gradle：直接用 Android SDK 工具链）
#
# 为什么在临时目录构建：aapt2 在 Windows 上无法处理含中文的路径，
# 所以把源码复制到纯 ASCII 路径下构建，完成后把产物拷回。
#
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'

$SDK = 'D:\Android\Sdk'
$BUILD_TOOLS = "$SDK\build-tools\34.0.0"
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$ROOT = $PSScriptRoot
$WORK = Join-Path $env:TEMP 'wbapk'
$OUT = "$WORK\build"

Write-Host "=== 0/8 同步源码到 ASCII 工作目录 ===" -ForegroundColor Cyan
if (Test-Path $WORK) { Remove-Item $WORK -Recurse -Force }
New-Item -ItemType Directory -Force -Path $WORK | Out-Null
Copy-Item "$ROOT\app" "$WORK\app" -Recurse -Force
New-Item -ItemType Directory -Force -Path "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

Write-Host "=== 1/8 编译资源（aapt2 compile）===" -ForegroundColor Cyan
& "$BUILD_TOOLS\aapt2.exe" compile --dir "$WORK\app\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "=== 2/8 链接资源（aapt2 link）===" -ForegroundColor Cyan
& "$BUILD_TOOLS\aapt2.exe" link `
    -o "$OUT\app-unsigned.apk" `
    -I $PLATFORM `
    --manifest "$WORK\app\AndroidManifest.xml" `
    --java "$OUT\gen" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 1 --version-name "1.0" `
    "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "=== 3/8 编译 Java（javac）===" -ForegroundColor Cyan
# ⚠ 每次构建前必须清空 classes：javac 不会删除「这次没产生」的旧 .class。
# 源码里把一个 switch 改成 if-else 之后，旧的合成类（`Xxx$1.class`）会留在目录里，
# 被 d8 一起收走 —— 而 d8 遇到这类合成类会直接崩（NPE: String.length()），
# 报错信息与「源码里有匿名类」一模一样，极难判断到底是哪来的。
if (Test-Path "$OUT\classes") { Remove-Item "$OUT\classes" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$OUT\classes" | Out-Null
$sources = @()
$sources += Get-ChildItem "$WORK\app\src" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
# 用 cmd 包装：javac 的 stderr 在 PowerShell 里会被当成终止错误
$sourceList = "$OUT\sources.txt"
$sources | Set-Content -Path $sourceList -Encoding ascii
$javacCmd = "javac --release 8 -classpath `"$PLATFORM`" -d `"$OUT\classes`" @`"$sourceList`" > `"$OUT\javac.log`" 2>&1"
cmd /c $javacCmd
if ($LASTEXITCODE -ne 0) {
    Get-Content "$OUT\javac.log" | Select-Object -Last 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    throw "javac 失败（exit $LASTEXITCODE）"
}

Write-Host "=== 4/8 转 dex（d8）===" -ForegroundColor Cyan
# ⚠ 两个坑都在这一行上：
#   ① 把每个 .class 都摊在命令行上 —— 源码长到十几个类之后，cmd 的 8191 字符
#      上限会被撑爆（报「The command line is too long」）；
#   ② 改成直接传 class 根目录 —— d8 不接受目录，报「Unsupported source file type」。
# 正确做法是 @argfile：把清单写进文件，d8 自己按行读。
$classes = Get-ChildItem "$OUT\classes" -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
$d8Args = "$OUT\d8-args.txt"
$d8Lines = @('--lib', $PLATFORM, '--min-api', '24', '--output', "$OUT\dex")
$d8Lines += $classes
$d8Lines | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } } |
    Set-Content -Path $d8Args -Encoding ascii
& "$BUILD_TOOLS\d8.bat" "@$d8Args"
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "=== 5/8 把 dex 打进 APK ===" -ForegroundColor Cyan
Copy-Item "$OUT\app-unsigned.apk" "$OUT\app-with-dex.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\app-with-dex.apk", 'Update')
$dexFile = Get-ChildItem "$OUT\dex" -Filter *.dex | Select-Object -First 1
if ($null -eq $dexFile) { $zip.Dispose(); throw "没有生成 classes.dex" }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile.FullName, 'classes.dex') | Out-Null
$zip.Dispose()
Write-Host "  dex 已打入（$([math]::Round($dexFile.Length/1KB,1)) KB）"

Write-Host "=== 6/8 对齐（zipalign）===" -ForegroundColor Cyan
& "$BUILD_TOOLS\zipalign.exe" -f 4 "$OUT\app-with-dex.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "=== 7/8 生成签名密钥 ===" -ForegroundColor Cyan
# ⚠ 密钥**必须放在项目目录**，不能放 $WORK。
#
# $WORK（%TEMP%\wbapk）在每次构建开头都会被整个删掉重建，密钥放那里等于
# 「每次构建都生成一把新钥匙」—— 于是每个 APK 的签名都不一样，手机上
# 覆盖安装会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能先卸载；
# 而卸载会清掉 App 数据，**所有已登录账号、API Key、模型开关全部丢失**。
# 放在项目目录里，后续构建就能直接升级安装、保留数据。
$ks = "$ROOT\debug.keystore"
if (-not (Test-Path $ks)) {
    Write-Host "  首次构建：生成签名密钥（之后复用，保证可覆盖升级）"
    # keytool 的 stderr 会被 PowerShell 当成终止错误，用 cmd 包装
    cmd /c "keytool -genkeypair -v -keystore `"$ks`" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=Android Debug,O=Android,C=US`" > nul 2>&1"
    if (-not (Test-Path $ks)) { throw "keytool 生成密钥失败" }
} else {
    Write-Host "  复用已有签名密钥（升级安装不会丢数据）"
}

Write-Host "=== 8/8 签名（apksigner）===" -ForegroundColor Cyan
& "$BUILD_TOOLS\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$OUT\MobileModelHub.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

# 校验签名
& "$BUILD_TOOLS\apksigner.bat" verify --print-certs "$OUT\MobileModelHub.apk" 2>&1 | Select-Object -First 4

Write-Host ""
Write-Host "=== 拷回项目目录 ===" -ForegroundColor Cyan
Copy-Item "$OUT\MobileModelHub.apk" "$ROOT\MobileModelHub.apk" -Force
$final = Get-Item "$ROOT\MobileModelHub.apk"
Write-Host "=== 构建成功 ===" -ForegroundColor Green
Write-Host "  路径：$($final.FullName)"
Write-Host "  大小：$([math]::Round($final.Length/1MB,2)) MB"
