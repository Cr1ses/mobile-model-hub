# 跑全部离线验证（不需要手机、不需要联网）。
#
#   powershell -ExecutionPolicy Bypass -File verify.ps1
#
# 三段验证：
#   1. Verify       —— 网关本体（鉴权 / 聚合 / 转发 / 流式 / 错误分支 / 状态页）
#   2. VerifyLogin  —— 四个渠道的直接登录（状态机 / 字段落位 / 续期 / PKCE / 二维码内容）
#   3. VerifyQr     —— 二维码编码器与参考实现逐模块对账（需要 Node + qrcode 包）
#
# 为什么要独立成脚本：这些验证跑的是 **APK 里同一份源码**，只有靠它们才能在不刷机
# 的情况下发现「登录字段落错位」这类问题 —— 那种错误在界面上看起来是成功的。
$ErrorActionPreference = 'Stop'

$SDK = 'D:\Android\Sdk'
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$ROOT = $PSScriptRoot
$WORK = Join-Path $env:TEMP 'wbapk-verify'
$OUT = Join-Path $WORK 'classes'

Write-Host "=== 准备（复制源码到 ASCII 路径）===" -ForegroundColor Cyan
if (Test-Path $WORK) { Remove-Item $WORK -Recurse -Force }
New-Item -ItemType Directory -Force -Path $WORK, $OUT | Out-Null
Copy-Item "$ROOT\app" "$WORK\app" -Recurse -Force
Copy-Item "$ROOT\verify" "$WORK\verify" -Recurse -Force

# 桌面验证需要 org.json 替身（Android 自带，桌面没有）。
# verify/org/json 只在验证里参与编译，绝不进 APK。
$srcs = @(Get-ChildItem "$WORK\verify\org" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
$srcs += @(Get-ChildItem "$WORK\app\src" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
$srcs += @(Get-ChildItem "$WORK\verify" -Recurse -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\org\\json\\' } |
    Select-Object -ExpandProperty FullName)
$srcs | Set-Content "$WORK\sources.txt" -Encoding ascii

Write-Host "=== 编译（App 源码 + 验证代码）===" -ForegroundColor Cyan
cmd /c "javac --release 8 -Xlint:-options -classpath `"$PLATFORM`" -d `"$OUT`" @`"$WORK\sources.txt`" > `"$WORK\javac.log`" 2>&1"
if ($LASTEXITCODE -ne 0) {
    Get-Content "$WORK\javac.log" | Select-Object -Last 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    throw "编译失败"
}

$failed = 0

Write-Host ""
Write-Host "=== 1/10 网关本体 ===" -ForegroundColor Cyan
& java -cp $OUT Verify
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 2/10 登录 ===" -ForegroundColor Cyan
& java -cp $OUT VerifyLogin
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 3/10 签到 ===" -ForegroundColor Cyan
& java -cp $OUT VerifyCheckin
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 4/10 模型目录刷新 ===" -ForegroundColor Cyan
& java -cp $OUT VerifyCatalog
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 5/10 模型规格 ===" -ForegroundColor Cyan
& java -cp $OUT VerifySpec
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 6/10 华为 CodeArts ===" -ForegroundColor Cyan
& java -cp $OUT VerifyCodeArts
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 7/10 ZCode 协议转换（OpenAI ⇄ Anthropic）===" -ForegroundColor Cyan
& java -cp $OUT VerifyZcode
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 8/10 Qoder ===" -ForegroundColor Cyan
& java -cp $OUT VerifyQoder
if ($LASTEXITCODE -ne 0) { $failed += 1 }

Write-Host ""
Write-Host "=== 9/10 Qoder cosy 协议（自定义 base64 / 签名）===" -ForegroundColor Cyan
& java -cp $OUT VerifyCosy
if ($LASTEXITCODE -ne 0) { $failed++ }

Write-Host "=== 10/10 二维码编码器 ===" -ForegroundColor Cyan
$env:WB_CLASSES = $OUT
if (-not $env:WB_QRCODE_MODULE) {
    # 参考实现可能装在别处；找不到就明确跳过，不假装通过
    $candidate = Join-Path $env:TEMP 'wg-qr\node_modules\qrcode'
    if (Test-Path $candidate) { $env:WB_QRCODE_MODULE = $candidate }
}
if (-not $env:WB_QRCODE_MODULE) {
    Write-Host "  跳过：未找到参考实现 qrcode 包（npm i qrcode 或设置 WB_QRCODE_MODULE）" -ForegroundColor Yellow
} else {
    & node "$ROOT\verify\run-qr-verify.mjs"
    if ($LASTEXITCODE -ne 0) { $failed += 1 }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "=== 全部验证通过 ===" -ForegroundColor Green
} else {
    Write-Host "=== 有 $failed 组验证失败 ===" -ForegroundColor Red
    exit 1
}
