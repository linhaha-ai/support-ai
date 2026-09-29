#requires -Version 7.0

function Copy-AcceptanceStorageFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [PSCustomObject]$Context,
        [Parameter(Mandatory)] [string]$SourceStorageKey
    )

    # 夹具必须拥有独立原文件；共享 storage_key 会在任一文档删除时误删另一份资料。
    if ($SourceStorageKey -notmatch '^[0-9]{4}/[0-9]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{1,16}$') {
        throw '夹具源文件不是合法的受控存储路径。'
    }
    $monthDirectory = [DateTimeOffset]::UtcNow.ToString('yyyy/MM')
    $extension = [System.IO.Path]::GetExtension($SourceStorageKey)
    $newStorageKey = "$monthDirectory/$([guid]::NewGuid().ToString())$extension"
    $storageRoot = '/var/lib/support-ai/storage'
    $arguments = @('compose', '--project-name', $Context.ProjectName, '--file', $Context.ComposeFile,
        '--env-file', $Context.EnvFile, 'exec', '--no-TTY', 'app')

    # 不经过 shell 展开，且两个路径都只包含前面允许的字符；由应用自身UID复制文件。
    & docker @arguments mkdir -p "$storageRoot/$monthDirectory"
    if ($LASTEXITCODE -ne 0) { throw '创建夹具受控存储目录失败。' }
    & docker @arguments cp "$storageRoot/$SourceStorageKey" "$storageRoot/$newStorageKey"
    if ($LASTEXITCODE -ne 0) { throw '复制夹具独立原文件失败。' }
    $sourceHash = & docker @arguments sha256sum "$storageRoot/$SourceStorageKey"
    if ($LASTEXITCODE -ne 0) { throw '读取夹具源文件摘要失败。' }
    $targetHash = & docker @arguments sha256sum "$storageRoot/$newStorageKey"
    if ($LASTEXITCODE -ne 0) { throw '读取夹具目标文件摘要失败。' }
    if (($sourceHash -split '\s+')[0] -ne ($targetHash -split '\s+')[0]) { throw '夹具复制前后文件摘要不一致。' }
    return $newStorageKey
}
