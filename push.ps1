# 推送到 GitHub（需要 Personal Access Token，repo 权限）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File push.ps1 -Token ghp_xxxx -Owner 你的用户名
#   powershell -ExecutionPolicy Bypass -File push.ps1 -Token ghp_xxxx -Owner 你的用户名 -Repo 自定义仓库名
#
# 会做三件事：
#   1. 用 API 建仓库（已存在则跳过）
#   2. 设置 remote
#   3. push 到 main
#
# ⚠ Token 不会写进任何文件，只在本次进程内使用。
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][string]$Owner,
    [string]$Repo = 'mobile-model-hub',
    [string]$Description = '手机上的模型聚合：把多个 AI 平台的模型聚合成一个 OpenAI 兼容端点'
)

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
Set-Location $ROOT

$headers = @{
    Authorization         = "token $Token"
    Accept                = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'          = 'mobile-model-hub-push'
}

Write-Host "=== 1/3 检查 Token 身份 ===" -ForegroundColor Cyan
try {
    $me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers -Method Get
    Write-Host "  已认证：$($me.login)" -ForegroundColor Green
    if ($Owner -eq '') { $Owner = $me.login }
} catch {
    throw "Token 无效或权限不足：$($_.Exception.Message)"
}

Write-Host "=== 2/3 创建仓库（已存在则跳过）===" -ForegroundColor Cyan
$repoBody = @{
    name        = $Repo
    description = $Description
    private     = $true
    auto_init   = $false
} | ConvertTo-Json
try {
    $r = Invoke-RestMethod -Uri 'https://api.github.com/user/repos' -Headers $headers `
        -Method Post -Body $repoBody -ContentType 'application/json'
    Write-Host "  已创建：$($r.full_name)" -ForegroundColor Green
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 422) {
        Write-Host "  仓库已存在，继续推送" -ForegroundColor Yellow
    } else {
        throw "创建仓库失败（HTTP $code）：$($_.Exception.Message)"
    }
}

Write-Host "=== 3/3 推送 ===" -ForegroundColor Cyan
# 用 token 的 URL 形式推送，避免交互式密码提示
$remoteUrl = "https://$Owner`:$Token@github.com/$Owner/$Repo.git"
& git remote remove origin 2>$null
& git remote add origin $remoteUrl
& git push -u origin main
if ($LASTEXITCODE -ne 0) {
    throw "推送失败（见上面的 git 输出）"
}

# 推完把 remote 里的 token 抹掉，避免留在 .git/config
& git remote set-url origin "https://github.com/$Owner/$Repo.git"

Write-Host ""
Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host "  仓库：https://github.com/$Owner/$Repo" -ForegroundColor Green