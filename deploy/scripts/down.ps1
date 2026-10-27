#requires -Version 7.0

# 一键停止整套部署。默认保留数据卷；需要清空数据时显式传 -RemoveVolumes。
[CmdletBinding()]
param(
    [Parameter()]
    [string]$ProjectName = 'support-ai',

    [Parameter()]
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),

    [Parameter()]
    [switch]$RemoveVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile

if ($RemoveVolumes) {
    Write-Host '警告：将同时删除 postgres-data 与 document-data 数据卷。'
    Invoke-SupportAiCompose -Context $context -Arguments @('down', '--volumes') -Operation '停止并删除部署'
}
else {
    # 日常重启使用 down 而不加 -v，数据卷继续保留。
    Invoke-SupportAiCompose -Context $context -Arguments @('down') -Operation '停止部署'
}

Write-Host '已停止。数据卷默认保留；如需清空请使用 -RemoveVolumes。'
