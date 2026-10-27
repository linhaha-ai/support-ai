#requires -Version 7.0

# 一键启动整套部署：自动准备 deploy/.env（缺密码时生成随机强密码），再构建并启动 web/app/postgres。
[CmdletBinding()]
param(
    [Parameter()]
    [string]$ProjectName = 'support-ai',

    [Parameter()]
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

$deployRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = [System.IO.Path]::GetFullPath($EnvFile)
$envExample = Join-Path $deployRoot '.env.example'

# 首次运行复制模板；后续保持用户已填写的配置。
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $envExample -PathType Leaf)) {
        throw "缺少环境变量模板：$envExample"
    }
    Copy-Item -LiteralPath $envExample -Destination $envPath
    Write-Host "已从模板创建：$envPath"
}

# 解析 SUPPORT_AI_DB_PASSWORD；为空则生成随机强密码并写回 .env，实现无手工编辑的一键启动。
$envLines = Get-Content -LiteralPath $envPath
$passwordIndex = -1
for ($i = 0; $i -lt $envLines.Count; $i++) {
    if ($envLines[$i] -match '^\s*SUPPORT_AI_DB_PASSWORD\s*=') {
        $passwordIndex = $i
        break
    }
}

$passwordValue = $null
if ($passwordIndex -ge 0) {
    $passwordValue = ($envLines[$passwordIndex] -replace '^\s*SUPPORT_AI_DB_PASSWORD\s*=\s*', '').Trim().Trim('"').Trim("'")
}

if ([string]::IsNullOrWhiteSpace($passwordValue)) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $passwordValue = ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', '').Substring(0, 24)
    if ($passwordIndex -ge 0) {
        $envLines[$passwordIndex] = "SUPPORT_AI_DB_PASSWORD=$passwordValue"
    }
    else {
        $envLines += "SUPPORT_AI_DB_PASSWORD=$passwordValue"
    }
    Set-Content -LiteralPath $envPath -Value $envLines -Encoding utf8
    Write-Host '已为 SUPPORT_AI_DB_PASSWORD 生成随机强密码并写入 .env。'
}

$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $envPath
Invoke-SupportAiCompose -Context $context -Arguments @('up', '--build', '--wait') -Operation '启动完整部署'

$bind = '127.0.0.1'
$port = '8088'
foreach ($line in (Get-Content -LiteralPath $envPath)) {
    if ($line -match '^\s*SUPPORT_AI_BIND_ADDRESS\s*=\s*(.+)$') { $bind = $Matches[1].Trim().Trim('"') }
    if ($line -match '^\s*SUPPORT_AI_HTTP_PORT\s*=\s*(.+)$') { $port = $Matches[1].Trim().Trim('"') }
}

Write-Host "启动完成：http://${bind}:${port}"
Write-Host '默认管理员：admin / admin。演示用户与工单种子 SQL 见 README，不会自动导入。'
