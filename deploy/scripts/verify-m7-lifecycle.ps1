#requires -Version 7.0
<#
.SYNOPSIS
在真实部署中验证同一历史引用的版本、停用、撤权、删除生命周期。
.DESCRIPTION
只操作本轮新建知识库和文档，保留会话、来源元数据和证据。会真实执行两次文档入库与一次问答。
密码仅从进程环境读取；失败不重放、不覆盖已有证据。本脚本不代表浏览器点击或人工质量评分。
#>
[CmdletBinding()]
param(
    [uri]$BaseUri = 'http://127.0.0.1:8088',
    [string]$ProjectName = 'support-ai-f31-source4',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$AdminPasswordEnv = 'SUPPORTAI_F32_ADMIN_PASSWORD',
    [string]$ReaderPasswordEnv = 'SUPPORTAI_F32_READER_PASSWORD',
    [Parameter(Mandatory)][string]$OutputPath,
    [int]$TimeoutSeconds = 600
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'common.ps1')
if ($BaseUri.Scheme -notin @('http', 'https') -or $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw 'BaseUri 必须是不含凭据、查询或片段的同源地址。'
}
$outputFile = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $outputFile) { throw '已有验收记录不可覆盖，请提供新路径。' }
$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
$checks = [System.Collections.Generic.List[object]]::new()
$requests = [System.Collections.Generic.List[object]]::new()
$summary = [ordered]@{
    ArtifactKind = 'M7_HISTORICAL_REFERENCE_LIFECYCLE'; Status = 'RUNNING'; Stage = 'LOGIN'
    StartedAt = [DateTimeOffset]::UtcNow.ToString('o'); FinishedAt = $null
    BaseUri = $BaseUri.AbsoluteUri; ProjectName = $ProjectName; CaseIds = @('DEV-020', 'DEV-040')
    Ids = [ordered]@{}; Checks = $checks; HttpRequests = $requests; Answer = $null
    RunnerStatus = 'NOT_RUN'; HumanReview = $null
    Limitations = @('两个改写题映射同一次生命周期，不重复计数模型运行。',
        '真实 HTTP、SSE 与数据库证据，不包含浏览器点击；不修改 F32 原始数据及人工评分。',
        '删除只针对本次新建夹具文档，逻辑删除后正文将由正常清理任务回收。')
}
function Save-Evidence {
    $null = [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputFile))
    [System.IO.File]::WriteAllText($outputFile, ($summary | ConvertTo-Json -Depth 50), [System.Text.UTF8Encoding]::new($false))
}
function Assert-Check([string]$Name, [bool]$Passed, [object]$Evidence = $null) {
    $checks.Add([ordered]@{ Name = $Name; Passed = $Passed; Evidence = $Evidence }); Save-Evidence
    if (-not $Passed) { throw "生命周期断言失败：$Name" }
    Write-Host "通过：$Name"
}
function Invoke-Api {
    param([object]$Client, [string]$Method, [string]$Path, [object]$Body = $null,
        [int[]]$Expected = @(200), [hashtable]$Form, [switch]$Stream)
    $null = $Client.Session.Headers.Remove('X-CSRF-TOKEN')
    $headers = @{ Accept = $(if ($Stream) { 'text/event-stream' } else { 'application/json' }) }
    if ($Method -ne 'GET' -and $null -ne $Client.Csrf) { $headers[$Client.Csrf.headerName] = $Client.Csrf.token }
    $arguments = @{ Uri = $BaseUri.AbsoluteUri.TrimEnd('/') + $Path; Method = $Method
        WebSession = $Client.Session; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = $TimeoutSeconds }
    if ($null -ne $Form) { $arguments.Form = $Form }
    elseif ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @arguments
    $status = [int]$response.StatusCode
    # 证据只保留路径、角色和状态，不持久化登录请求体、Cookie 或 CSRF。
    $requests.Add([ordered]@{ Actor = $Client.Actor; Method = $Method; Path = $Path; Status = $status }); Save-Evidence
    if ($Expected -notcontains $status) { throw "$Method $Path 返回非预期 HTTP $status。" }
    if ($Stream) { return [string]$response.Content }
    if ($status -eq 204 -or [string]::IsNullOrWhiteSpace($response.Content)) { return $null }
    $envelope = $response.Content | ConvertFrom-Json -Depth 100
    if ($status -ge 400) { return [pscustomobject]@{ StatusCode = $status; ErrorCode = $envelope.code } }
    if ($envelope.code -ne 'OK') { throw '接口未返回 OK。' }
    return $envelope.data
}
function New-Client([string]$Username, [string]$PasswordEnvironment) {
    $password = [Environment]::GetEnvironmentVariable($PasswordEnvironment)
    if ([string]::IsNullOrWhiteSpace($password)) { throw "请通过 $PasswordEnvironment 提供凭据。" }
    $client = [pscustomobject]@{ Actor = $Username; Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new(); Csrf = $null; User = $null }
    try {
        $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
        $client.User = Invoke-Api $client POST '/api/auth/login' @{ username = $Username; password = $password }
    } finally { $password = $null }
    $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
    Assert-Check "$Username 登录身份匹配" ($client.User.username -eq $Username) @{ UserId = $client.User.id }
    return $client
}
function Wait-Ingestion([object]$Client, [string]$JobId) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $job = Invoke-Api $Client GET "/api/ingestion-jobs/$JobId"
        if ($job.status -eq 'SUCCEEDED') { return $job }
        if ($job.status -in @('FAILED', 'CANCELLED')) { throw "任务 $JobId 未成功。" }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw '入库等待超时。'
}
function Read-Database {
    $kb = [guid]$summary.Ids.KnowledgeBaseId; $doc = [guid]$summary.Ids.DocumentId
    $run = [guid]$summary.Ids.RunId; $chunk = [guid]$summary.Ids.ChunkId
    $sql = @"
-- document：限定本轮新建库与文档，检查逻辑删除状态。
-- message_source/chat_message：历史同一引用保留落库记录，读取权限由实时查询判断。
SELECT json_build_object(
 'documentDeleted',(SELECT deleted_at IS NOT NULL FROM document WHERE id='$doc'::uuid AND kb_id='$kb'::uuid),
 'sources',(SELECT coalesce(json_agg(json_build_object('sourceId',s.id,'chunkId',s.chunk_id,'versionId',c.version_id,'cited',s.is_cited)),'[]'::json)
   FROM message_source s JOIN chat_message m ON m.id=s.message_id JOIN document_chunk c ON c.id=s.chunk_id
   WHERE m.run_id='$run'::uuid AND s.chunk_id='$chunk'::uuid));
"@
    $arguments = @('compose', '--project-name', $context.ProjectName, '--file', $context.ComposeFile,
        '--env-file', $context.EnvFile, 'exec', '--no-TTY', 'postgres', 'psql', '--quiet', '--no-align',
        '--tuples-only', '--set', 'ON_ERROR_STOP=1', '--username', 'supportai', '--dbname', 'supportai')
    $output = $sql | & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw '只读数据库核对失败。' }
    return (($output -join "`n").Trim() | ConvertFrom-Json -Depth 30)
}
function Assert-Hidden([object]$Reader, [string]$Label) {
    $denied = Invoke-Api $Reader GET "/api/chunks/$($summary.Ids.ChunkId)/source" -Expected @(403, 404)
    $history = Invoke-Api $Reader GET "/api/chat/sessions/$($summary.Ids.SessionId)/messages"
    $run = Invoke-Api $Reader GET "/api/chat/runs/$($summary.Ids.RunId)"
    Assert-Check "$Label 同一旧引用拒绝正文" ($denied.StatusCode -in @(403, 404)) $denied
    Assert-Check "$Label 历史与运行同步隐藏内容" ($history.contentReadable -eq $false -and
        @($history.items | Where-Object { -not [string]::IsNullOrEmpty($_.content) -or $_.sources.Count -gt 0 }).Count -eq 0 -and
        $run.contentReadable -eq $false -and [string]::IsNullOrEmpty($run.answer) -and $run.sources.Count -eq 0) @{
        HistoryReadable = $history.contentReadable; RunReadable = $run.contentReadable }
}
try {
    $admin = New-Client 'admin' $AdminPasswordEnv; $reader = New-Client 'support1' $ReaderPasswordEnv
    $suffix = [guid]::NewGuid().ToString('N')
    $summary.Stage = 'CREATE_FIXTURE'
    $kb = Invoke-Api $admin POST '/api/knowledge-bases' @{ name = "M7 旧引用生命周期-$suffix"; description = '独立验收夹具' } -Expected 201
    $kbId = [string]([guid]$kb.id); $readerId = [string]([guid]$reader.User.id)
    $summary.Ids.KnowledgeBaseId = $kbId; $summary.Ids.ReaderId = $readerId; Save-Evidence
    $null = Invoke-Api $admin PUT "/api/knowledge-bases/$kbId/members/$readerId" @{ permission = 'WRITE' }
    $temporaryPath = Join-Path ([System.IO.Path]::GetDirectoryName($outputFile)) "m7-fixture-$suffix.txt"
    $content = "M7 旧引用独立验收手册。故障码 M7_CALLBACK_TIMEOUT 表示回调超时，版本一规定等待五秒后重试。"
    [System.IO.File]::WriteAllText($temporaryPath, $content, [System.Text.UTF8Encoding]::new($false))
    $upload = Invoke-Api $reader POST "/api/knowledge-bases/$kbId/documents" -Expected 202 -Form @{
        file = Get-Item -LiteralPath $temporaryPath; displayName = "M7 生命周期手册-$suffix" }
    $docId = [string]([guid]$upload.documentId); $versionId = [string]([guid]$upload.versionId)
    $summary.Ids.DocumentId = $docId; $summary.Ids.VersionId = $versionId; $summary.Ids.JobId = $upload.jobId; Save-Evidence
    $job = Wait-Ingestion $reader $upload.jobId
    Assert-Check '版本一真实入库完成' ($job.status -eq 'SUCCEEDED') $upload
    $session = Invoke-Api $reader POST '/api/chat/sessions' @{ kbIds = @($kbId); mode = 'RAG' } -Expected 201
    $sessionId = [string]([guid]$session.sessionId); $requestId = [guid]::NewGuid().ToString()
    $summary.Ids.SessionId = $sessionId; $summary.Ids.ClientRequestId = $requestId
    $summary.Stage = 'REAL_QUESTION'; Save-Evidence
    $stream = Invoke-Api $reader POST "/api/chat/sessions/$sessionId/messages" @{
        clientRequestId = $requestId; content = 'M7_CALLBACK_TIMEOUT 是什么，应等待多久重试？请引用手册。' } -Stream
    $events = @((($stream -replace "`r`n", "`n") -split "`n`n") | ForEach-Object {
        $name = ''; $data = @()
        foreach ($line in ($_ -split "`n")) {
            if ($line.StartsWith('event:')) { $name = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) { $data += $line.Substring(5).TrimStart() }
        }
        if ($name -and $data.Count) { [pscustomobject]@{ Name = $name; Data = ($data -join "`n") | ConvertFrom-Json -Depth 100 } }
    })
    $start = @($events | Where-Object Name -eq 'start'); $done = @($events | Where-Object Name -eq 'done')
    Assert-Check '真实问答 SSE 正常完成' ($start.Count -eq 1 -and $done.Count -eq 1 -and
        $done[0].Data.status -eq 'COMPLETED' -and @($events | Where-Object Name -eq 'error').Count -eq 0) @($events | ForEach-Object Name)
    $runId = [string]([guid]$start[0].Data.runId); $summary.Ids.RunId = $runId
    $run = Invoke-Api $reader GET "/api/chat/runs/$runId"
    $sources = @($run.sources | Where-Object { $_.documentId -eq $docId -and $_.versionId -eq $versionId })
    $summary.Answer = $run.answer
    Assert-Check '回答确实引用本次版本一' ($run.status -eq 'COMPLETED' -and $sources.Count -eq 1 -and $sources[0].available) $sources
    $chunkId = [string]([guid]$sources[0].chunkId); $summary.Ids.ChunkId = $chunkId; Save-Evidence
    $before = Read-Database
    Assert-Check '同一引用已真实落库且唯一' ($before.sources.Count -eq 1 -and $before.sources[0].cited -and -not $before.documentDeleted) $before
    $summary.Stage = 'PUBLISH_VERSION_TWO'
    $doc = Invoke-Api $reader GET "/api/documents/$docId"
    [System.IO.File]::WriteAllText($temporaryPath, $content.Replace('版本一规定等待五秒', '版本二规定等待三秒'), [System.Text.UTF8Encoding]::new($false))
    $newVersion = Invoke-Api $reader POST "/api/documents/$docId/versions" -Expected 202 -Form @{
        file = Get-Item -LiteralPath $temporaryPath; expectedRevision = [string]$doc.revision }
    $summary.Ids.NewVersionId = $newVersion.versionId; Save-Evidence
    $null = Wait-Ingestion $reader $newVersion.jobId
    $old = Invoke-Api $reader GET "/api/chunks/$chunkId/source"
    $historyRun = Invoke-Api $reader GET "/api/chat/runs/$runId"
    Assert-Check '版本二发布后同一旧引用标旧且可预览' ($old.versionId -eq $versionId -and
        $old.isCurrent -eq $false -and $old.content -eq $content -and
        $historyRun.sources[0].chunkId -eq $chunkId -and $historyRun.sources[0].isCurrent -eq $false) $old
    $summary.Stage = 'DISABLE_KNOWLEDGE_BASE'
    $disabled = Invoke-Api $admin PATCH "/api/knowledge-bases/$kbId" @{ status = 'DISABLED'; revision = $kb.revision }
    Assert-Hidden $reader '知识库停用后'
    $null = Invoke-Api $admin PATCH "/api/knowledge-bases/$kbId" @{ status = 'ACTIVE'; revision = $disabled.revision }
    $restored = Invoke-Api $reader GET "/api/chunks/$chunkId/source"
    Assert-Check '重新启用后同一旧引用恢复预览' ($restored.chunkId -eq $chunkId -and $restored.isCurrent -eq $false) $restored
    $summary.Stage = 'REVOKE_MEMBERSHIP'
    $null = Invoke-Api $admin DELETE "/api/knowledge-bases/$kbId/members/$readerId" -Expected 204
    Assert-Hidden $reader '成员撤权后'
    $null = Invoke-Api $admin PUT "/api/knowledge-bases/$kbId/members/$readerId" @{ permission = 'WRITE' }
    $restored = Invoke-Api $reader GET "/api/chunks/$chunkId/source"
    Assert-Check '重新授权后同一旧引用恢复预览' ($restored.chunkId -eq $chunkId -and $restored.isCurrent -eq $false) $restored
    $summary.Stage = 'DELETE_OWN_FIXTURE'
    # docId 仅来自本轮上传回执，删除前再次核对所属新库，绝不接受外部传入删除目标。
    $owned = Invoke-Api $reader GET "/api/documents/$docId"
    Assert-Check '删除目标限定本轮独立夹具' ($owned.knowledgeBaseId -eq $kbId -and $owned.documentId -eq $docId) @{ KnowledgeBaseId = $kbId; DocumentId = $docId }
    $null = Invoke-Api $reader DELETE "/api/documents/$docId" -Expected 202
    $denied = Invoke-Api $reader GET "/api/chunks/$chunkId/source" -Expected @(403, 404)
    $deletedRun = Invoke-Api $reader GET "/api/chat/runs/$runId"
    $after = Read-Database
    Assert-Check '删除文档后同一旧引用正文不可读' ($denied.StatusCode -in @(403, 404) -and $after.documentDeleted) $denied
    $deletedSource = @($deletedRun.sources | Where-Object chunkId -eq $chunkId)
    Assert-Check '删除后保留同一引用元数据并明确不可用' ($deletedRun.contentReadable -and
        $deletedSource.Count -eq 1 -and $deletedSource[0].available -eq $false -and
        $null -eq $deletedSource[0].previewPath -and $after.sources.Count -eq 1 -and
        $after.sources[0].sourceId -eq $before.sources[0].sourceId) @{ Source = $deletedSource; Database = $after }
    $summary.Status = 'PASSED'; $summary.Stage = 'FINISHED'
} catch {
    $summary.Status = 'FAILED'
    Write-Warning "验收在 $($summary.Stage) 失败，保留已生成证据及夹具，不自动重放。"
    throw 'M7 历史引用验收未通过，请依据最后断言和状态定位。'
} finally {
    $summary.FinishedAt = [DateTimeOffset]::UtcNow.ToString('o'); Save-Evidence
}
