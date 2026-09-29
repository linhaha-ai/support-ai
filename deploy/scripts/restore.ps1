#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter()]
    [string]$ProjectName = 'support-ai',

    [Parameter()]
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),

    [Parameter(Mandatory)]
    [string]$BackupName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

Assert-BackupName -BackupName $BackupName
$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile

# 仅在完整性、临时库和原文件校验全部通过后，维护容器才会替换正式数据。
Stop-SupportAiApp -Context $context
Invoke-SupportAiMaintenance -Context $context -Command 'restore' -BackupName $BackupName
Start-SupportAiApplication -Context $context

Write-Host "恢复完成：$BackupName"
