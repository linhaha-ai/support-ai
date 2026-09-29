#requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-BackupName {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BackupName
    )

    if ($BackupName -notmatch '^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9_-])?$') {
        throw '备份名只能包含字母、数字、点、下划线和连字符，长度为 1 到 64，且不能以点结尾。'
    }

    # 备份目录落在宿主机文件系统，需兼容 Windows 保留文件名规则。
    $windowsStem = ($BackupName -split '\.', 2)[0].ToUpperInvariant()
    if ($windowsStem -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') {
        throw '备份名不能使用 Windows 保留文件名。'
    }
}

function New-ComposeContext {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$ProjectName,

        [Parameter(Mandatory)]
        [string]$EnvFile
    )

    if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]{0,62}$') {
        throw 'Compose 项目名只能使用小写字母、数字、下划线和连字符，且长度不能超过 63。'
    }

    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCommand) {
        throw '未找到 docker 命令。'
    }

    $deployRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $composeFile = [System.IO.Path]::GetFullPath((Join-Path $deployRoot 'compose.yml'))
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "部署编排文件不存在：$composeFile"
    }

    $resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile -ErrorAction Stop).ProviderPath
    if (-not (Test-Path -LiteralPath $resolvedEnvFile -PathType Leaf)) {
        throw 'EnvFile 必须指向普通文件。'
    }

    [PSCustomObject]@{
        ProjectName = $ProjectName
        ComposeFile = $composeFile
        EnvFile = $resolvedEnvFile
    }
}

function Invoke-SupportAiCompose {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [Parameter(Mandatory)]
        [string]$Operation
    )

    $dockerArguments = @(
        'compose'
        '--project-name', $Context.ProjectName
        '--file', $Context.ComposeFile
        '--env-file', $Context.EnvFile
    ) + $Arguments

    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation 失败，docker compose 退出码：$LASTEXITCODE。请检查容器实际状态后再继续。"
    }
}

function Stop-SupportAiApp {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context
    )

    Write-Host '正在停止应用写入，进入一致性维护窗口...'
    Invoke-SupportAiCompose -Context $Context -Arguments @('stop', 'app') -Operation '停止 app 服务'
}

function Start-SupportAiApplication {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context
    )

    Write-Host '维护操作成功，正在启动 app 和 web 服务...'
    Invoke-SupportAiCompose -Context $Context -Arguments @('up', '--detach', '--wait', 'app', 'web') -Operation '启动 app/web 服务'
}

function Invoke-SupportAiMaintenance {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory)]
        [ValidateSet('backup', 'restore')]
        [string]$Command,

        [Parameter(Mandatory)]
        [string]$BackupName
    )

    Invoke-SupportAiCompose -Context $Context `
        -Arguments @('--profile', 'maintenance', 'run', '--rm', '--no-TTY', 'maintenance', $Command, $BackupName) `
        -Operation "执行 $Command 维护任务"
}
