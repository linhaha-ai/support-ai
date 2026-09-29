#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter()]
    [string]$ProjectName = 'support-ai',

    [Parameter()]
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),

    [Parameter()]
    [string]$BackupName = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')

Assert-BackupName -BackupName $BackupName
$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile

# app 停止后数据库和原文件均不再写入，维护容器才能生成一致快照。
Stop-SupportAiApp -Context $context
Invoke-SupportAiMaintenance -Context $context -Command 'backup' -BackupName $BackupName
Start-SupportAiApplication -Context $context

Write-Host "备份完成：$BackupName"
