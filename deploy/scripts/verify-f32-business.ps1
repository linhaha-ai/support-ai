#requires -Version 7.0
<#
.SYNOPSIS
在真实部署上串联业务接口、权限、版本、任务和助手验收。
.DESCRIPTION
密码仅从环境变量读取。脚本只创建并修改本次带唯一标识的知识库、文档、工单和会话；
保留夹具供复核，不修改已有知识库。真实模型调用串行执行，失败立即停止并保留已有证据。
.EXAMPLE
./deploy/scripts/verify-f32-business.ps1 -ProjectName support-ai-f31-source4 -OutputPath ./deploy/backups/f32-business.json
#>
[CmdletBinding()]
param(
    [uri]$BaseUri = 'http://127.0.0.1:8088',
    [string]$ProjectName = 'support-ai-f31-source4',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$AdminUsername = 'admin',
    [string]$ReaderUsername = 'support1',
    [string]$AdminPasswordEnv = 'SUPPORTAI_F32_ADMIN_PASSWORD',
    [string]$ReaderPasswordEnv = 'SUPPORTAI_F32_READER_PASSWORD',
    [string]$OutputPath,
    [int]$TimeoutSeconds = 600,
    [switch]$SkipAssistant
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'acceptance-storage.ps1')
$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
$checks = [System.Collections.Generic.List[object]]::new()
$operations = [System.Collections.Generic.HashSet[string]]::new()
$summary = [ordered]@{
    Status = 'RUNNING'; StartedAt = [DateTimeOffset]::UtcNow.ToString('o')
    BaseUri = $BaseUri.AbsoluteUri; ProjectName = $ProjectName
    Ids = [ordered]@{}; Checks = $checks; CoveredOperations = @(); UncoveredOperations = @()
    ModelEvidence = '真实部署配置；本脚本不启动协议桩'
    Assistant = $(if ($SkipAssistant) { 'NOT_RUN' } else { 'PENDING' })
}

function Save-Evidence {
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $resolved = [System.IO.Path]::GetFullPath($OutputPath)
        $null = [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolved))
        [System.IO.File]::WriteAllText($resolved, ($summary | ConvertTo-Json -Depth 30), [System.Text.UTF8Encoding]::new($false))
    }
}

function Assert-Check([string]$Name, [bool]$Condition, [string[]]$OperationIds = @(), [object]$Evidence = $null) {
    $checks.Add([ordered]@{ Name = $Name; Passed = $Condition; Evidence = $Evidence })
    if ($Condition) { foreach ($operation in $OperationIds) { $null = $operations.Add($operation) } }
    $summary.CoveredOperations = @($operations | Sort-Object)
    Save-Evidence
    if (-not $Condition) { throw "业务语义校验失败：$Name" }
    Write-Host "通过：$Name"
}

function Invoke-Api {
    param([object]$Client, [string]$Method, [string]$Path, [object]$Body = $null,
        [int[]]$Expected = @(200), [hashtable]$Form, [switch]$WithoutCsrf, [switch]$Raw)
    # Invoke-WebRequest 会把 Headers 合并回 WebSession；必须先清理上次令牌，省略临时头才等于真正无头。
    $null = $Client.Session.Headers.Remove('X-CSRF-TOKEN')
    if ($null -ne $Client.Csrf) { $null = $Client.Session.Headers.Remove([string]$Client.Csrf.headerName) }
    $headers = @{ Accept = 'application/json' }
    if ($Method -ne 'GET' -and -not $WithoutCsrf -and $null -ne $Client.Csrf) {
        $headers[$Client.Csrf.headerName] = $Client.Csrf.token
    }
    $arguments = @{
        Uri = ($BaseUri.AbsoluteUri.TrimEnd('/') + $Path); Method = $Method
        WebSession = $Client.Session; Headers = $headers; SkipHttpErrorCheck = $true
        TimeoutSec = $TimeoutSeconds
    }
    if ($null -ne $Form) { $arguments.Form = $Form }
    elseif ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-WebRequest @arguments
    if ($Expected -notcontains [int]$response.StatusCode) {
        # 不输出请求头、Cookie、密码或服务器的内部响应，错误定位仅保留路由与状态。
        throw "$Method $Path 返回 HTTP $([int]$response.StatusCode)，预期 $($Expected -join '/')。"
    }
    if ($Raw -or [int]$response.StatusCode -ge 400 -or [string]::IsNullOrWhiteSpace($response.Content)) { return $response }
    $envelope = $response.Content | ConvertFrom-Json -Depth 100
    if ($envelope.code -ne 'OK') { throw "$Method $Path 未返回 OK 业务结果。" }
    return $envelope.data
}

function New-Client([string]$Username, [string]$PasswordEnvironment) {
    $password = [Environment]::GetEnvironmentVariable($PasswordEnvironment)
    if ([string]::IsNullOrWhiteSpace($password)) { throw "请通过环境变量 $PasswordEnvironment 提供账号密码。" }
    $client = [pscustomobject]@{ Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new(); Csrf = $null; User = $null }
    $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
    Assert-Check "获取 $Username 的 CSRF 令牌" (-not [string]::IsNullOrWhiteSpace($client.Csrf.token)) @('getCsrfToken')
    $client.User = Invoke-Api $client POST '/api/auth/login' @{ username = $Username; password = $password }
    $password = $null
    $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
    Assert-Check "登录身份匹配 $Username" ($client.User.username -eq $Username) @('login')
    return $client
}

function Invoke-Sql([string]$Sql) {
    $arguments = @('compose', '--project-name', $context.ProjectName, '--file', $context.ComposeFile,
        '--env-file', $context.EnvFile, 'exec', '--no-TTY', 'postgres', 'psql', '--quiet', '--no-align',
        '--tuples-only', '--set', 'ON_ERROR_STOP=1', '--username', 'supportai', '--dbname', 'supportai')
    $output = $Sql | & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "验收数据库操作失败，退出码 $LASTEXITCODE。" }
    return (($output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }) -join "`n").Trim()
}

function Wait-Ingestion([object]$Client, [string]$JobId) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $job = Invoke-Api $Client GET "/api/ingestion-jobs/$JobId"
        if ($job.status -eq 'SUCCEEDED') { return $job }
        if ($job.status -in @('FAILED', 'CANCELLED')) { throw "入库任务 $JobId 进入 $($job.status)，错误码 $($job.errorCode)。" }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "入库任务 $JobId 等待超时。"
}

function Invoke-Stream([object]$Client, [string]$SessionId, [string]$Question, [switch]$CancelOnStart) {
    $uri = [uri]($BaseUri.AbsoluteUri.TrimEnd('/') + "/api/chat/sessions/$SessionId/messages")
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $false
    $http = [System.Net.Http.HttpClient]::new($handler)
    $http.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $null = $request.Headers.TryAddWithoutValidation('Accept', 'text/event-stream')
    $cookie = ($Client.Session.Cookies.GetCookies($uri) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '; '
    $null = $request.Headers.TryAddWithoutValidation('Cookie', $cookie)
    $null = $request.Headers.TryAddWithoutValidation($Client.Csrf.headerName, $Client.Csrf.token)
    $requestId = [guid]::NewGuid().ToString()
    $request.Content = [System.Net.Http.StringContent]::new(
        (@{ clientRequestId = $requestId; content = $Question } | ConvertTo-Json -Compress),
        [System.Text.Encoding]::UTF8, 'application/json')
    $events = [System.Collections.Generic.List[object]]::new()
    $response = $null; $reader = $null; $runId = $null
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $http.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200 -or $response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') {
            throw "消息提交未进入 SSE，HTTP $([int]$response.StatusCode)。"
        }
        $reader = [System.IO.StreamReader]::new($response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
        $name = ''; $data = [System.Collections.Generic.List[string]]::new()
        while ($true) {
            # 每次读取也受限，避免响应头已到达后无限等候断开的模型流。
            $read = $reader.ReadLineAsync()
            if (-not $read.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) { throw 'SSE 事件读取超时。' }
            $line = $read.GetAwaiter().GetResult()
            if ($null -eq $line) { break }
            if ($line.StartsWith('event:')) { $name = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) { $data.Add($line.Substring(5).Trim()) }
            elseif ($line.Length -eq 0 -and $name.Length -gt 0) {
                $payload = if ($data.Count -gt 0) { ($data -join "`n") | ConvertFrom-Json -Depth 100 } else { $null }
                $events.Add([pscustomobject]@{ Name = $name; Milliseconds = $timer.ElapsedMilliseconds; Data = $payload })
                if ($name -eq 'start') {
                    $runId = [string]$payload.runId
                    if ($CancelOnStart) {
                        $cancel = Invoke-Api $Client POST "/api/chat/runs/$runId/cancel" -Expected @(200, 202)
                        Assert-Check '活动问答运行取消已持久化' ($cancel.status -eq 'CANCELLED') @('cancelChatRun') @{ RunId = $runId }
                    }
                }
                if ($name -eq 'error' -and -not $CancelOnStart) { throw "SSE 错误：$($payload.code)" }
                if ($name -in @('done', 'error')) { break }
                $name = ''; $data.Clear()
            }
        }
    }
    finally {
        $timer.Stop()
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose(); $http.Dispose(); $handler.Dispose()
    }
    if ([string]::IsNullOrWhiteSpace($runId)) { throw 'SSE 未返回 start/runId。' }
    $status = Invoke-Api $Client GET "/api/chat/runs/$runId"
    if ($CancelOnStart) {
        Assert-Check '取消问答终态可回读' ($status.status -eq 'CANCELLED') @('getChatRunStatus') @{ RunId = $runId }
    }
    else {
        $deltas = @($events | Where-Object Name -eq 'delta')
        $streamAnswer = ($deltas | ForEach-Object { $_.Data.text }) -join ''
        $doneEvents = @($events | Where-Object Name -eq 'done')
        $errorEvents = @($events | Where-Object Name -eq 'error')
        $protocolComplete = $doneEvents.Count -eq 1 -and $doneEvents[0].Data.runId -eq $runId -and $doneEvents[0].Data.status -eq 'COMPLETED' -and $errorEvents.Count -eq 0
        Assert-Check 'SSE 与持久化回答一致且正常完成' ($protocolComplete -and $status.status -eq 'COMPLETED' -and $deltas.Count -gt 0 -and $streamAnswer -eq $status.answer) @('submitChatMessage', 'getChatRunStatus') @{
            RunId = $runId; Events = @($events | ForEach-Object Name); DurationMs = $timer.ElapsedMilliseconds; Answer = $status.answer; Sources = $status.sources
        }
    }
    return [pscustomobject]@{ RunId = $runId; RequestId = $requestId; Question = $Question; Status = $status; Events = $events }
}

function New-IngestionFixture([string]$SourceVersionId, [string]$KbId, [string]$CreatorId, [string]$State) {
    # 复制本次上传的原文件至独立存储键；不复用业务文档ID或文件路径。
    $documentId = [guid]::NewGuid().ToString(); $versionId = [guid]::NewGuid().ToString(); $jobId = [guid]::NewGuid().ToString()
    $sourceStorageKey = Invoke-Sql "SELECT storage_key FROM document_version WHERE id='$([guid]$SourceVersionId)'::uuid;"
    $fixtureStorageKey = Copy-AcceptanceStorageFile -Context $context -SourceStorageKey $sourceStorageKey
    $pending = if ($State -eq 'QUEUED') { "'$versionId'::uuid" } else { 'NULL' }
    $sql = @"
BEGIN;
-- document：仅创建本轮知识库中的任务控制夹具。
INSERT INTO document(id,kb_id,name,next_version_no,created_by)
VALUES ('$documentId','$KbId','F32 任务控制夹具',2,'$CreatorId');
-- document_version：保留实际上传文件配置，验证重试能够继续真实处理。
INSERT INTO document_version(id,document_id,version_no,storage_key,file_hash,file_size,media_type,original_filename,status,parser_version,chunker_version,embedding_model,dimensions,config_hash,config_snapshot)
SELECT '$versionId','$documentId',1,'$fixtureStorageKey',file_hash,file_size,media_type,original_filename,'$State',parser_version,chunker_version,embedding_model,dimensions,config_hash,config_snapshot
FROM document_version WHERE id='$SourceVersionId'::uuid;
UPDATE document SET pending_version_id=$pending WHERE id='$documentId'::uuid;
-- ingestion_job：排队任务推迟一天领取，以无竞态验证取消；失败任务供人工重试。
INSERT INTO ingestion_job(id,version_id,status,stage,attempt,max_attempts,next_run_at,error_code,finished_at)
VALUES ('$jobId','$versionId','$State','PARSE',1,3,CURRENT_TIMESTAMP + INTERVAL '1 day',
CASE WHEN '$State'='FAILED' THEN 'F32_FIXTURE' ELSE NULL END,
CASE WHEN '$State'='FAILED' THEN CURRENT_TIMESTAMP ELSE NULL END);
COMMIT;
SELECT id FROM ingestion_job WHERE id='$jobId'::uuid;
"@
    $created = Invoke-Sql $sql
    Assert-Check "创建唯一 $State 入库夹具" ($created -eq $jobId) @() @{ DocumentId = $documentId; VersionId = $versionId; JobId = $jobId }
    return [pscustomobject]@{ DocumentId = $documentId; VersionId = $versionId; JobId = $jobId }
}

$temporaryFile = Join-Path ([System.IO.Path]::GetTempPath()) ('f32-business-' + [guid]::NewGuid().ToString('N') + '.txt')
$failure = $null
try {
    $admin = New-Client $AdminUsername $AdminPasswordEnv
    $reader = New-Client $ReaderUsername $ReaderPasswordEnv
    $me = Invoke-Api $admin GET '/api/auth/me'
    Assert-Check '当前身份与登录账号一致' ($me.id -eq $admin.User.id) @('getCurrentUser')
    $users = @(Invoke-Api $admin GET '/api/admin/users')
    Assert-Check '管理员目录包含验收账号且不返回密码' (@($users | Where-Object id -eq $reader.User.id).Count -eq 1 -and (($users | ConvertTo-Json) -notmatch 'password')) @('listUsersForAdministration')
    $null = Invoke-Api $reader GET '/api/admin/users' -Expected 403
    Assert-Check '普通账号不能读取管理员目录' $true
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $kb = Invoke-Api $admin POST '/api/knowledge-bases' @{ name = "F32 业务验收-$suffix"; description = '本次自动验收独立夹具' } @(201)
    $kbId = [string]([guid]$kb.id); $readerId = [string]([guid]$reader.User.id)
    $summary.Ids.KnowledgeBaseId = $kbId
    Assert-Check '创建知识库并获得WRITE权限' ($kb.currentPermission -eq 'WRITE') @('createKnowledgeBase') @{ KnowledgeBaseId = $kbId }
    $managed = @(Invoke-Api $admin GET '/api/admin/knowledge-bases')
    Assert-Check '管理员管理目录包含新知识库' (@($managed | Where-Object id -eq $kbId).Count -eq 1) @('listManagedKnowledgeBases')
    $null = Invoke-Api $reader GET "/api/knowledge-bases/$kbId" -Expected @(403, 404)
    $null = Invoke-Api $admin PATCH "/api/knowledge-bases/$kbId" @{ name = "F32 业务验收修订-$suffix"; revision = $kb.revision } -WithoutCsrf -Expected 403
    Assert-Check '无成员权限和缺失CSRF均被拒绝' $true
    $updated = Invoke-Api $admin PATCH "/api/knowledge-bases/$kbId" @{ name = "F32 业务验收修订-$suffix"; revision = $kb.revision }
    Assert-Check '元数据修订号递增' ($updated.revision -gt $kb.revision) @('updateKnowledgeBase')
    $null = Invoke-Api $admin PATCH "/api/knowledge-bases/$kbId" @{ description = '旧修订号不能覆盖'; revision = $kb.revision } -Expected 409
    Assert-Check '过期修订号阻止覆盖' $true
    $member = Invoke-Api $admin PUT "/api/knowledge-bases/$kbId/members/$readerId" @{ permission = 'READ' }
    $members = @(Invoke-Api $admin GET "/api/knowledge-bases/$kbId/members")
    Assert-Check 'READ授权与成员目录一致' ($member.permission -eq 'READ' -and @($members | Where-Object { $_.userId -eq $readerId -and $_.permission -eq 'READ' }).Count -eq 1) @('upsertKnowledgeBaseMember', 'listKnowledgeBaseMembers')
    $detail = Invoke-Api $reader GET "/api/knowledge-bases/$kbId"
    $accessible = @(Invoke-Api $reader GET '/api/knowledge-bases')
    Assert-Check 'READ成员可读取授权知识库列表与详情' ($detail.id -eq $kbId -and @($accessible | Where-Object id -eq $kbId).Count -eq 1) @('getKnowledgeBase', 'listAccessibleKnowledgeBases')

    $content = "F32 专用业务验收手册。`n错误码 F32_E_TIMEOUT 表示回调超时，先核对回调地址和网络，再按三次退避重试。`n本手册不包含工单实时状态，必须查询工单系统。`n"
    [System.IO.File]::WriteAllText($temporaryFile, $content, [System.Text.UTF8Encoding]::new($false))
    $form = @{ file = Get-Item -LiteralPath $temporaryFile; displayName = "F32 故障处理手册-$suffix" }
    $null = Invoke-Api $reader POST "/api/knowledge-bases/$kbId/documents" -Form $form -Expected 403
    $member = Invoke-Api $admin PUT "/api/knowledge-bases/$kbId/members/$readerId" @{ permission = 'WRITE' }
    $upload = Invoke-Api $reader POST "/api/knowledge-bases/$kbId/documents" -Form $form -Expected 202
    $docId = [string]([guid]$upload.documentId); $versionId = [string]([guid]$upload.versionId)
    $summary.Ids.DocumentId = $docId; $summary.Ids.VersionId = $versionId; $summary.Ids.IngestionJobId = $upload.jobId
    $job = Wait-Ingestion $reader $upload.jobId
    Assert-Check 'READ不能上传、WRITE上传后真实入库成功' ($member.permission -eq 'WRITE' -and $job.status -eq 'SUCCEEDED') @('uploadKnowledgeDocument', 'getIngestionJob') @{ JobId = $upload.jobId }
    $docs = @(Invoke-Api $reader GET "/api/knowledge-bases/$kbId/documents")
    $doc = Invoke-Api $reader GET "/api/documents/$docId"
    $versions = Invoke-Api $reader GET "/api/documents/$docId/versions"
    Assert-Check '文档列表详情版本对应READY活动版本' (@($docs | Where-Object documentId -eq $docId).Count -eq 1 -and $doc.activeVersion.versionId -eq $versionId -and $doc.activeVersion.status -eq 'READY' -and $null -eq $doc.pendingVersion -and $versions.items[0].versionId -eq $versionId) @('listKnowledgeDocuments', 'getKnowledgeDocument', 'listKnowledgeDocumentVersions')
    $original = Invoke-Api $reader GET "/api/documents/$docId/versions/$versionId/content"
    $chunks = Invoke-Api $reader GET "/api/documents/$docId/versions/$versionId/chunks"
    $chunkId = [string]([guid]$chunks.items[0].chunkId)
    $source = Invoke-Api $reader GET "/api/chunks/$chunkId/source"
    Assert-Check '原文完整且分块来源属于当前版本' ($original.content -eq $content -and $chunks.items.Count -gt 0 -and $source.versionId -eq $versionId -and $source.isCurrent -eq $true -and $source.content.Contains('F32_E_TIMEOUT')) @('getKnowledgeDocumentContent', 'listKnowledgeDocumentChunks', 'getDocumentChunkSource')
    $retrieval = Invoke-Api $reader POST "/api/knowledge-bases/$kbId/retrieval-tests" @{ query = 'F32_E_TIMEOUT 回调超时如何处理'; topK = 5; strategy = 'FUSION' }
    Assert-Check '融合检索命中授权文档且版本正确' (@($retrieval.matches | Where-Object { $_.documentId -eq $docId -and $_.versionId -eq $versionId }).Count -gt 0 -and @($retrieval.matches | Where-Object knowledgeBaseId -ne $kbId).Count -eq 0) @('testKnowledgeBaseRetrieval') @{ Matches = $retrieval.matches; Strategy = $retrieval.strategy }

    $ragSession = Invoke-Api $reader POST '/api/chat/sessions' @{ kbIds = @($kbId); mode = 'RAG' } @(201)
    $sessionId = [string]([guid]$ragSession.sessionId); $summary.Ids.RagSessionId = $sessionId
    Assert-Check '创建个人RAG会话' ($ragSession.mode -eq 'RAG') @('createChatSession')
    $titled = Invoke-Api $reader PATCH "/api/chat/sessions/$sessionId" @{ title = "F32 串联验收-$suffix" }
    $sessions = Invoke-Api $reader GET '/api/chat/sessions?limit=100'
    Assert-Check '会话标题更新且个人列表可见' ($titled.title -eq "F32 串联验收-$suffix" -and @($sessions.items | Where-Object sessionId -eq $sessionId).Count -eq 1) @('updateChatSessionTitle', 'listChatSessions')
    $rag = Invoke-Stream $reader $sessionId 'F32_E_TIMEOUT 表示什么，应如何处理？请依据手册回答并引用来源。'
    Assert-Check 'RAG回答包含已登记文档引用和排查依据' ($rag.Status.answer.Contains('回调') -and @($rag.Status.sources | Where-Object { $_.type -eq 'DOCUMENT' -and $_.documentId -eq $docId }).Count -gt 0) @() @{ RunId = $rag.RunId }
    $reused = Invoke-Api $reader POST "/api/chat/sessions/$sessionId/messages" @{ clientRequestId = $rag.RequestId; content = $rag.Question }
    $runCount = Invoke-Sql "SELECT count(*) FROM chat_run WHERE session_id='$sessionId'::uuid AND client_request_id='$($rag.RequestId)'::uuid;"
    Assert-Check '同幂等键复用原运行且没有重复生成记录' ($reused.runId -eq $rag.RunId -and $runCount -eq '1')
    $history = Invoke-Api $reader GET "/api/chat/sessions/$sessionId/messages"
    Assert-Check '历史持久化用户与助手正文及引用' ($history.contentReadable -eq $true -and $history.items.Count -eq 2 -and @($history.items | Where-Object { $_.role -eq 'ASSISTANT' -and $_.content -eq $rag.Status.answer -and $_.sources.Count -gt 0 }).Count -eq 1) @('listChatSessionMessages')
    $null = Invoke-Stream $reader $sessionId '请详细说明F32_E_TIMEOUT的排查步骤。' -CancelOnStart

    $same = Invoke-Api $reader POST "/api/documents/$docId/versions" -Form @{ file = Get-Item $temporaryFile; expectedRevision = [string]$doc.revision }
    Assert-Check '相同文件与配置复用版本和任务' ($same.reused -eq $true -and $same.versionId -eq $versionId -and $same.jobId -eq $upload.jobId) @('uploadKnowledgeDocumentVersion')
    $newContent = $content.Replace('三次退避重试', '四次退避重试，并通知值班人员')
    [System.IO.File]::WriteAllText($temporaryFile, $newContent, [System.Text.UTF8Encoding]::new($false))
    $newVersion = Invoke-Api $reader POST "/api/documents/$docId/versions" -Form @{ file = Get-Item $temporaryFile; expectedRevision = [string]$doc.revision } -Expected 202
    $null = Wait-Ingestion $reader $newVersion.jobId
    $newDoc = Invoke-Api $reader GET "/api/documents/$docId"
    $oldSource = Invoke-Api $reader GET "/api/chunks/$chunkId/source"
    $newOriginal = Invoke-Api $reader GET "/api/documents/$docId/versions/$($newVersion.versionId)/content"
    Assert-Check '新版本发布后原子切换且历史来源标旧' ($newDoc.activeVersion.versionId -eq $newVersion.versionId -and $null -eq $newDoc.pendingVersion -and $oldSource.isCurrent -eq $false -and $newOriginal.content -eq $newContent) @() @{ NewVersionId = $newVersion.versionId }
    $newRetrieval = Invoke-Api $reader POST "/api/knowledge-bases/$kbId/retrieval-tests" @{ query = 'F32_E_TIMEOUT 回调超时重试次数'; topK = 5; strategy = 'FUSION' }
    Assert-Check '检索仅返回当前新版而非历史向量' (@($newRetrieval.matches | Where-Object versionId -eq $newVersion.versionId).Count -gt 0 -and @($newRetrieval.matches | Where-Object versionId -eq $versionId).Count -eq 0) @() @{ Matches = $newRetrieval.matches }

    $retryFixture = New-IngestionFixture $newVersion.versionId $kbId ([string]([guid]$admin.User.id)) 'FAILED'
    $retried = Invoke-Api $admin POST "/api/ingestion-jobs/$($retryFixture.JobId)/retry"
    $retriedFinal = Wait-Ingestion $admin $retryFixture.JobId
    Assert-Check '失败任务人工重试后真实发布成功' ($retried.status -eq 'QUEUED' -and $retriedFinal.status -eq 'SUCCEEDED') @('retryIngestionJob') @{ JobId = $retryFixture.JobId }
    $cancelFixture = New-IngestionFixture $newVersion.versionId $kbId ([string]([guid]$admin.User.id)) 'QUEUED'
    $cancelled = Invoke-Api $admin POST "/api/ingestion-jobs/$($cancelFixture.JobId)/cancel"
    $cancelledDoc = Invoke-Api $admin GET "/api/documents/$($cancelFixture.DocumentId)"
    Assert-Check '取消排队任务清除pending且未激活版本' ($cancelled.status -eq 'CANCELLED' -and $null -eq $cancelledDoc.pendingVersion -and $null -eq $cancelledDoc.activeVersion) @('cancelIngestionJob') @{ JobId = $cancelFixture.JobId }

    $ticketId = [guid]::NewGuid().ToString(); $ticketNo = 'F32' + (Get-Random -Minimum 100000000 -Maximum 999999999)
    $summary.Ids.TicketId = $ticketId; $summary.Ids.TicketNo = $ticketNo
    $ticketCreated = Invoke-Sql @"
-- ticket：只创建本次知识库下的独立工单，不覆盖已有工单编号。
INSERT INTO ticket(id,ticket_no,kb_id,title,description,status,owner_name)
VALUES ('$ticketId','$ticketNo','$kbId','F32 回调超时验收','F32_E_TIMEOUT 回调失败待排查','OPEN','F32值班员') RETURNING id;
"@
    $ticket = Invoke-Api $reader GET "/api/tickets/$ticketNo"
    Assert-Check '工单接口返回当前数据库状态和授权范围' ($ticketCreated -eq $ticketId -and $ticket.ticketNo -eq $ticketNo -and $ticket.status -eq 'OPEN' -and $ticket.knowledgeBaseId -eq $kbId) @('getTicketStatus')
    if (-not $SkipAssistant) {
        $assistantSession = Invoke-Api $reader POST '/api/chat/sessions' @{ kbIds = @($kbId); mode = 'ASSISTANT' } @(201)
        $assistantId = [string]([guid]$assistantSession.sessionId); $summary.Ids.AssistantSessionId = $assistantId
        $assistant = Invoke-Stream $reader $assistantId "请查询工单 $ticketNo 的当前状态，再查询F32_E_TIMEOUT手册，综合给出处理建议。请同时引用工单和知识文档。"
        Assert-Check '真实助手组合文档与工单并持久化两类引用' (@($assistant.Status.sources | Where-Object type -eq 'DOCUMENT').Count -gt 0 -and @($assistant.Status.sources | Where-Object { $_.type -eq 'TICKET' -and $_.ticketNo -eq $ticketNo -and $_.ticketStatus -eq 'OPEN' }).Count -gt 0) @() @{ RunId = $assistant.RunId; Sources = $assistant.Status.sources }
        $changed = Invoke-Sql "UPDATE ticket SET status='RESOLVED',updated_at=CURRENT_TIMESTAMP WHERE id='$ticketId'::uuid AND kb_id='$kbId'::uuid RETURNING status;"
        Assert-Check '仅本次工单状态更新为RESOLVED' ($changed -eq 'RESOLVED')
        $followup = Invoke-Stream $reader $assistantId "工单 $ticketNo 现在解决了吗？请重新查询最新状态，不要沿用上一轮状态。"
        $currentTicketSources = @($followup.Status.sources | Where-Object { $_.type -eq 'TICKET' -and $_.ticketNo -eq $ticketNo -and $_.ticketStatus -eq 'RESOLVED' -and $_.isCurrent -eq $true })
        Assert-Check '多轮助手重新查工单并引用最新状态' ($currentTicketSources.Count -gt 0) @() @{ RunId = $followup.RunId; Sources = $followup.Status.sources }
        $audit = Invoke-Sql "SELECT json_agg(row_to_json(t)) FROM (SELECT run_id,tool_name,status,call_no FROM tool_call WHERE run_id IN ('$($assistant.RunId)'::uuid,'$($followup.RunId)'::uuid) ORDER BY run_id,call_no) t;"
        $auditRows = @($audit | ConvertFrom-Json)
        Assert-Check '助手工具审计覆盖知识搜索与工单查询' (@($auditRows | Where-Object { $_.tool_name -eq 'searchKnowledge' -and $_.status -eq 'SUCCEEDED' }).Count -gt 0 -and @($auditRows | Where-Object { $_.run_id -eq $followup.RunId -and $_.tool_name -eq 'getTicketStatus' -and $_.status -eq 'SUCCEEDED' }).Count -gt 0) @() $auditRows
        $summary.Assistant = 'PASSED'
    }

    $null = Invoke-Api $admin DELETE "/api/knowledge-bases/$kbId/members/$readerId" -Expected 204
    $hiddenHistory = Invoke-Api $reader GET "/api/chat/sessions/$sessionId/messages"
    $hiddenRun = Invoke-Api $reader GET "/api/chat/runs/$($rag.RunId)"
    $null = Invoke-Api $reader GET "/api/chunks/$chunkId/source" -Expected @(403, 404)
    $null = Invoke-Api $reader GET "/api/tickets/$ticketNo" -Expected 404
    Assert-Check '撤权后历史运行来源和工单正文均不可泄漏' ($hiddenHistory.contentReadable -eq $false -and @($hiddenHistory.items | Where-Object { -not [string]::IsNullOrEmpty($_.content) -or $_.sources.Count -gt 0 }).Count -eq 0 -and $hiddenRun.contentReadable -eq $false -and [string]::IsNullOrEmpty($hiddenRun.answer) -and $hiddenRun.sources.Count -eq 0) @('revokeKnowledgeBaseMember')
    if (-not $SkipAssistant) {
        $hiddenAssistant = Invoke-Api $reader GET "/api/chat/runs/$($followup.RunId)"
        $hiddenAssistantHistory = Invoke-Api $reader GET "/api/chat/sessions/$assistantId/messages"
        Assert-Check '撤权后助手工单快照与正文也被隐藏' ($hiddenAssistant.contentReadable -eq $false -and [string]::IsNullOrEmpty($hiddenAssistant.answer) -and $hiddenAssistant.sources.Count -eq 0 -and $hiddenAssistantHistory.contentReadable -eq $false -and @($hiddenAssistantHistory.items | Where-Object { -not [string]::IsNullOrEmpty($_.content) -or $_.sources.Count -gt 0 }).Count -eq 0)
    }
    $null = Invoke-Api $reader DELETE "/api/chat/sessions/$sessionId" -Expected 204
    $remainingSessions = Invoke-Api $reader GET '/api/chat/sessions?limit=100'
    Assert-Check '删除本次会话后个人列表不再展示' (@($remainingSessions.items | Where-Object sessionId -eq $sessionId).Count -eq 0) @('deleteChatSession')

    # 单独上传删除夹具，避免回收共享原文件引用的任务控制夹具。
    $deleteUpload = Invoke-Api $admin POST "/api/knowledge-bases/$kbId/documents" -Form @{ file = Get-Item $temporaryFile; displayName = 'F32 删除验收专用文档' } -Expected 202
    $null = Wait-Ingestion $admin $deleteUpload.jobId
    $null = Invoke-Api $admin DELETE "/api/documents/$($deleteUpload.documentId)" -Expected 202
    $null = Invoke-Api $admin GET "/api/documents/$($deleteUpload.documentId)" -Expected 404
    $cleanupState = Invoke-Sql "SELECT count(*) FROM document_cleanup_job WHERE document_id='$($deleteUpload.documentId)'::uuid;"
    Assert-Check '删除文档立即不可读且持久化清理任务' ($cleanupState -eq '1') @('deleteKnowledgeDocument') @{ DocumentId = $deleteUpload.documentId }
    $null = Invoke-Api $reader POST '/api/auth/logout' -Expected 204
    $null = Invoke-Api $reader GET '/api/auth/me' -Expected 401
    Assert-Check '退出登录后原会话失效' $true @('logout')

    $expectedTables = @('app_user','knowledge_base','kb_member','document','document_version','ingestion_job','document_chunk','vector_store','chat_session','chat_session_kb','chat_run','chat_message','message_source','ticket','tool_call','document_cleanup_job')
    $tableJson = Invoke-Sql "SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history';"
    $actualTables = @($tableJson | ConvertFrom-Json)
    Assert-Check '16张业务向量表与清单完全一致' (@(Compare-Object ($expectedTables | Sort-Object) ($actualTables | Sort-Object)).Count -eq 0) @() $actualTables
    $constraints = Invoke-Sql "SELECT json_agg(row_to_json(t)) FROM (SELECT conrelid::regclass::text AS source_table,confrelid::regclass::text AS target_table,conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE contype='f' AND connamespace='public'::regnamespace ORDER BY conrelid::regclass::text,conname) t;"
    $relations = @($constraints | ConvertFrom-Json)
    $requiredConstraints = @('fk_document_active_version','fk_document_pending_version','fk_vector_store_document_chunk','fk_message_source_ticket','fk_message_source_tool_call','fk_tool_call_run')
    Assert-Check '关键跨表外键和文档版本复合约束存在' (@($requiredConstraints | Where-Object { $_ -notin $relations.conname }).Count -eq 0) @() $relations
    $pointerErrors = Invoke-Sql "SELECT count(*) FROM document d LEFT JOIN document_version v ON v.id=d.active_version_id WHERE d.kb_id='$kbId'::uuid AND d.active_version_id IS NOT NULL AND (v.document_id<>d.id OR v.status<>'READY');"
    Assert-Check '本次所有文档active指针仅指向自身READY版本' ($pointerErrors -eq '0')
    $openapi = Invoke-RestMethod -Uri ($BaseUri.AbsoluteUri.TrimEnd('/') + '/v3/api-docs') -WebSession $admin.Session -TimeoutSec 30
    # 仅核验设计中的业务 API；Actuator 链接和健康探针不属于 35 个业务接口。
    $businessPaths = @($openapi.paths.PSObject.Properties | Where-Object {
        $_.Name.StartsWith('/api/', [StringComparison]::Ordinal) -and $_.Name -notmatch '^/api/health(?:/|$)'
    })
    $expectedOperations = @($businessPaths | ForEach-Object {
        $_.Value.PSObject.Properties | Where-Object { $_.Name -in @('get','post','put','patch','delete') } | ForEach-Object { $_.Value.operationId }
    } | Where-Object { $_ } | Sort-Object -Unique)
    $summary.UncoveredOperations = @($expectedOperations | Where-Object { -not $operations.Contains($_) })
    Assert-Check '全部35个设计业务接口均有语义断言' ($expectedOperations.Count -eq 35 -and $summary.UncoveredOperations.Count -eq 0) @() @{ Expected = $expectedOperations.Count; Covered = $operations.Count; Missing = $summary.UncoveredOperations }
    $summary.Status = $(if ($SkipAssistant) { 'PARTIAL' } else { 'PASSED' })
}
catch {
    $failure = $_
    $summary.Status = 'FAILED'
    $summary.Error = $_.Exception.Message
}
finally {
    # 仅删除本脚本创建的临时上传文件；数据库夹具保留，所有ID写入验收证据。
    if (Test-Path -LiteralPath $temporaryFile) { Remove-Item -LiteralPath $temporaryFile -Force }
    $summary.FinishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Save-Evidence
    $summary | ConvertTo-Json -Depth 30
}
if ($null -ne $failure) { throw $failure }
