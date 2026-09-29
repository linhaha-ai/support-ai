#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter()]
    [string]$SourceProjectName = 'support-ai-f31-source',

    [Parameter()]
    [string]$RestoreProjectName = 'support-ai-f31-restore',

    [Parameter()]
    [string]$SourceEnvFile = (Join-Path $PSScriptRoot '..\.env'),

    [Parameter()]
    [string]$RestoreEnvFile = (Join-Path $PSScriptRoot '..\.env.restore'),

    [Parameter()]
    [uri]$SourceBaseUri = 'http://127.0.0.1:8088',

    [Parameter()]
    [uri]$RestoreBaseUri = 'http://127.0.0.1:18088',

    [Parameter()]
    [AllowNull()]
    [uri]$MockOllamaBaseUri,

    [Parameter()]
    [string]$ModelEvidenceLabel = '未声明模型环境',

    [Parameter()]
    [string]$BackupName = ((Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'") + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)),

    [Parameter()]
    [switch]$SkipBuild,

    [Parameter()]
    [switch]$SkipLiveAi,

    [Parameter()]
    [switch]$SkipSeedImport
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'acceptance-storage.ps1')

function Join-ApiUri {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [string]$Path
    )

    return [uri]($BaseUri.AbsoluteUri.TrimEnd('/') + '/' + $Path.TrimStart('/'))
}

function Invoke-HttpRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$Uri,

        [Parameter(Mandatory)]
        [Microsoft.PowerShell.Commands.WebRequestMethod]$Method,

        [Parameter(Mandatory)]
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,

        [Parameter()]
        [hashtable]$Headers = @{},

        [Parameter()]
        [AllowNull()]
        [object]$JsonBody,

        [Parameter(Mandatory)]
        [int[]]$ExpectedStatus,

        [Parameter(Mandatory)]
        [string]$Operation
    )

    $requestParameters = @{
        Uri = $Uri
        Method = $Method
        WebSession = $Session
        Headers = $Headers
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $JsonBody) {
        $requestParameters.ContentType = 'application/json; charset=utf-8'
        $requestParameters.Body = $JsonBody | ConvertTo-Json -Depth 20 -Compress
    }

    $response = Invoke-WebRequest @requestParameters
    $statusCode = [int]$response.StatusCode
    if ($ExpectedStatus -notcontains $statusCode) {
        throw "$Operation 失败，HTTP 状态码为 $statusCode，预期为 $($ExpectedStatus -join '/')。"
    }
    return $response
}

function Read-ApiEnvelope {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [Microsoft.PowerShell.Commands.BasicHtmlWebResponseObject]$Response,

        [Parameter(Mandatory)]
        [string]$Operation
    )

    try {
        $envelope = $Response.Content | ConvertFrom-Json -Depth 100
    }
    catch {
        throw "$Operation 返回的内容不是有效 JSON。"
    }
    if ($null -eq $envelope -or $envelope.code -ne 'OK' -or $null -eq $envelope.data) {
        throw "$Operation 返回的业务结果不是 OK。"
    }
    return $envelope
}

function Get-CsrfToken {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )

    $response = Invoke-HttpRequest `
        -Uri (Join-ApiUri -BaseUri $BaseUri -Path '/api/auth/csrf') `
        -Method Get `
        -Session $Session `
        -Headers @{ Accept = 'application/json' } `
        -ExpectedStatus 200 `
        -Operation '获取 CSRF 令牌'
    $envelope = Read-ApiEnvelope -Response $response -Operation '获取 CSRF 令牌'
    if ([string]::IsNullOrWhiteSpace([string]$envelope.data.token) -or [string]::IsNullOrWhiteSpace([string]$envelope.data.headerName)) {
        throw 'CSRF 响应缺少 token 或 headerName。'
    }
    return [PSCustomObject]@{
        Token = [string]$envelope.data.token
        HeaderName = [string]$envelope.data.headerName
    }
}

function New-AuthenticatedClient {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [string]$Username,

        [Parameter(Mandatory)]
        [string]$Password
    )

    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Get-CsrfToken -BaseUri $BaseUri -Session $session
    $headers = @{ Accept = 'application/json' }
    $headers[$csrf.HeaderName] = $csrf.Token
    $response = Invoke-HttpRequest `
        -Uri (Join-ApiUri -BaseUri $BaseUri -Path '/api/auth/login') `
        -Method Post `
        -Session $session `
        -Headers $headers `
        -JsonBody @{ username = $Username; password = $Password } `
        -ExpectedStatus 200 `
        -Operation "账号 $Username 登录"
    $envelope = Read-ApiEnvelope -Response $response -Operation "账号 $Username 登录"
    if ([string]$envelope.data.username -ne $Username) {
        throw "账号 $Username 登录后返回了不匹配的身份。"
    }

    # 登录会轮换 Servlet Session，因此写请求必须重新取得 CSRF 令牌。
    $rotatedCsrf = Get-CsrfToken -BaseUri $BaseUri -Session $session
    return [PSCustomObject]@{
        Session = $session
        CsrfToken = $rotatedCsrf.Token
        CsrfHeaderName = $rotatedCsrf.HeaderName
        Username = $Username
    }
}

function Get-ClientHeaders {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Client,

        [Parameter()]
        [switch]$WriteRequest
    )

    $headers = @{ Accept = 'application/json' }
    if ($WriteRequest) {
        $headers[$Client.CsrfHeaderName] = $Client.CsrfToken
    }
    return $headers
}

function Invoke-JsonApi {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$Uri,

        [Parameter(Mandatory)]
        [Microsoft.PowerShell.Commands.WebRequestMethod]$Method,

        [Parameter(Mandatory)]
        [PSCustomObject]$Client,

        [Parameter()]
        [AllowNull()]
        [object]$JsonBody,

        [Parameter(Mandatory)]
        [int[]]$ExpectedStatus,

        [Parameter(Mandatory)]
        [string]$Operation
    )

    $writeRequest = $Method -notin @(
        [Microsoft.PowerShell.Commands.WebRequestMethod]::Get,
        [Microsoft.PowerShell.Commands.WebRequestMethod]::Head,
        [Microsoft.PowerShell.Commands.WebRequestMethod]::Options
    )
    $response = Invoke-HttpRequest `
        -Uri $Uri `
        -Method $Method `
        -Session $Client.Session `
        -Headers (Get-ClientHeaders -Client $Client -WriteRequest:$writeRequest) `
        -JsonBody $JsonBody `
        -ExpectedStatus $ExpectedStatus `
        -Operation $Operation
    return Read-ApiEnvelope -Response $response -Operation $Operation
}

function Assert-Health {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [string]$EnvironmentName
    )

    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $response = Invoke-HttpRequest `
        -Uri (Join-ApiUri -BaseUri $BaseUri -Path '/api/health') `
        -Method Get `
        -Session $session `
        -Headers @{ Accept = 'application/json' } `
        -ExpectedStatus 200 `
        -Operation "$EnvironmentName 健康检查"
    if ($response.Content -notmatch '"status"\s*:\s*"UP"') {
        throw "$EnvironmentName 健康检查未返回 UP。"
    }
}

function Import-SeedSql {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory)]
        [string]$SqlPath,

        [Parameter(Mandatory)]
        [string]$SeedName
    )

    $resolvedSqlPath = (Resolve-Path -LiteralPath $SqlPath -ErrorAction Stop).ProviderPath
    $dockerArguments = @(
        'compose'
        '--project-name', $Context.ProjectName
        '--file', $Context.ComposeFile
        '--env-file', $Context.EnvFile
        'exec', '--no-TTY', 'postgres'
        'psql', '--set', 'ON_ERROR_STOP=1', '--username', 'supportai', '--dbname', 'supportai'
    )

    # 密码仅由 Compose 注入容器环境，命令行和验收输出均不展开 EnvFile 内容。
    Get-Content -Raw -LiteralPath $resolvedSqlPath | & docker @dockerArguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "导入 $SeedName 失败，psql 退出码：$exitCode。"
    }
}

function Invoke-PostgresScalar {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory)]
        [string]$Sql,

        [Parameter(Mandatory)]
        [string]$Operation
    )

    $dockerArguments = @(
        'compose'
        '--project-name', $Context.ProjectName
        '--file', $Context.ComposeFile
        '--env-file', $Context.EnvFile
        'exec', '--no-TTY', 'postgres'
        'psql', '--quiet', '--no-align', '--tuples-only', '--set', 'ON_ERROR_STOP=1'
        '--username', 'supportai', '--dbname', 'supportai', '--command', $Sql
    )
    $output = & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation 失败，psql 退出码：$LASTEXITCODE。"
    }
    return (($output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }) -join "`n").Trim()
}

function Reset-MockOllamaState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri
    )

    $response = Invoke-RestMethod `
        -Uri (Join-ApiUri -BaseUri $BaseUri -Path '/__mock/reset') `
        -Method Post `
        -ContentType 'application/json; charset=utf-8' `
        -Body '{}'
    if ($response.reset -ne $true) {
        throw '确定性模型桩未确认计数器重置。'
    }
}

function Get-MockOllamaState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri
    )

    return Invoke-RestMethod -Uri (Join-ApiUri -BaseUri $BaseUri -Path '/__mock/state') -Method Get
}

function Wait-IngestionSucceeded {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [PSCustomObject]$Client,

        [Parameter(Mandatory)]
        [string]$JobId,

        [Parameter()]
        [int]$TimeoutSeconds = 240
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $envelope = Invoke-JsonApi `
            -Uri (Join-ApiUri -BaseUri $BaseUri -Path "/api/ingestion-jobs/$JobId") `
            -Method Get `
            -Client $Client `
            -ExpectedStatus 200 `
            -Operation '查询恢复后的入库任务'
        $status = [string]$envelope.data.status
        if ($status -eq 'SUCCEEDED') {
            return
        }
        if ($status -in @('FAILED', 'CANCELLED')) {
            throw "恢复后的入库任务进入终态 $status，不能执行实时 RAG 验收。"
        }
        Start-Sleep -Seconds 2
    }
    throw "等待入库任务 $JobId 完成超时。"
}

function Invoke-RagSse {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [uri]$BaseUri,

        [Parameter(Mandatory)]
        [PSCustomObject]$Client,

        [Parameter(Mandatory)]
        [string]$SessionId
    )

    $uri = Join-ApiUri -BaseUri $BaseUri -Path "/api/chat/sessions/$SessionId/messages"
    $cookieHeader = ($Client.Session.Cookies.GetCookies($uri) | ForEach-Object {
        "$($_.Name)=$($_.Value)"
    }) -join '; '
    if ([string]::IsNullOrWhiteSpace($cookieHeader)) {
        throw '实时 RAG 请求缺少登录 Cookie。'
    }

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $false
    $httpClient = [System.Net.Http.HttpClient]::new($handler)
    $httpClient.Timeout = [TimeSpan]::FromMinutes(5)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $null = $request.Headers.TryAddWithoutValidation('Accept', 'text/event-stream')
    $null = $request.Headers.TryAddWithoutValidation('Cookie', $cookieHeader)
    $null = $request.Headers.TryAddWithoutValidation($Client.CsrfHeaderName, $Client.CsrfToken)
    $requestBody = @{
        clientRequestId = [guid]::NewGuid().ToString()
        content = '请严格依据验收文档，按顺序说明 F31 备份恢复的关键步骤，并给出每一步的核对结果。'
    } | ConvertTo-Json -Compress
    $request.Content = [System.Net.Http.StringContent]::new(
        $requestBody,
        [System.Text.Encoding]::UTF8,
        'application/json'
    )

    $events = [System.Collections.Generic.List[object]]::new()
    $response = $null
    $stream = $null
    $reader = $null
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $httpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200) {
            throw "实时 RAG 请求失败，HTTP 状态码为 $([int]$response.StatusCode)。"
        }
        if ($response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') {
            throw "实时 RAG 返回了非 SSE 媒体类型：$($response.Content.Headers.ContentType)。"
        }

        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        $currentEvent = $null
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLineAsync().GetAwaiter().GetResult()
            if ($line.StartsWith('event:')) {
                $currentEvent = $line.Substring(6).Trim()
                continue
            }
            if ($line.Length -eq 0 -and -not [string]::IsNullOrWhiteSpace($currentEvent)) {
                $events.Add([PSCustomObject]@{
                    Name = $currentEvent
                    ArrivalMilliseconds = $stopwatch.ElapsedMilliseconds
                })
                if ($currentEvent -eq 'error') {
                    throw '实时 RAG SSE 返回 error 事件。'
                }
                if ($currentEvent -eq 'done') {
                    break
                }
                $currentEvent = $null
            }
        }
    }
    finally {
        $stopwatch.Stop()
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        elseif ($null -ne $stream) {
            $stream.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
        $httpClient.Dispose()
        $handler.Dispose()
    }

    $eventNames = @($events | ForEach-Object { $_.Name })
    if ($eventNames -notcontains 'start' -or $eventNames -notcontains 'delta' -or $eventNames -notcontains 'done') {
        throw "实时 RAG SSE 事件不完整，实际事件：$($eventNames -join ', ')。"
    }
    $result = [PSCustomObject]@{
        EventCount = $events.Count
        DeltaCount = @($events | Where-Object Name -eq 'delta').Count
        FirstEventMilliseconds = $events[0].ArrivalMilliseconds
        LastEventMilliseconds = $events[$events.Count - 1].ArrivalMilliseconds
    }
    if ($result.DeltaCount -lt 2 -or ($result.LastEventMilliseconds - $result.FirstEventMilliseconds) -lt 500) {
        throw 'SSE 未呈现可观察的多分块时间间隔，不能判定代理逐步输出通过。'
    }
    return $result
}

if ($SourceProjectName -eq $RestoreProjectName) {
    throw 'SourceProjectName 与 RestoreProjectName 必须不同，避免覆盖源环境数据卷。'
}
if ($SourceBaseUri.AbsoluteUri.TrimEnd('/') -eq $RestoreBaseUri.AbsoluteUri.TrimEnd('/')) {
    throw 'SourceBaseUri 与 RestoreBaseUri 必须不同，源环境和隔离恢复环境不能共用入口。'
}
if ($SourceBaseUri.Scheme -notin @('http', 'https') -or $RestoreBaseUri.Scheme -notin @('http', 'https')) {
    throw 'SourceBaseUri 与 RestoreBaseUri 仅支持 HTTP 或 HTTPS。'
}

Assert-BackupName -BackupName $BackupName
$sourceContext = New-ComposeContext -ProjectName $SourceProjectName -EnvFile $SourceEnvFile
$restoreContext = New-ComposeContext -ProjectName $RestoreProjectName -EnvFile $RestoreEnvFile
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$identitySeedPath = Join-Path $repoRoot 'backend\support-ai-data\src\main\resources\db\seed\identity\init_app_user.sql'
$ticketSeedPath = Join-Path $repoRoot 'backend\support-ai-data\src\main\resources\db\seed\assistant\init_ticket.sql'
$backupScript = Join-Path $PSScriptRoot 'backup.ps1'
$restoreScript = Join-Path $PSScriptRoot 'restore.ps1'
$temporaryFile = Join-Path ([System.IO.Path]::GetTempPath()) (
    'support-ai-f31-' + [guid]::NewGuid().ToString('N') + '.txt'
)
$uniqueSuffix = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$knowledgeBaseName = "F31 备份恢复验收-$uniqueSuffix"
$documentContent = @"
SupportAI F31 部署验收文档。
第一步：应用停止写入后同时备份 PostgreSQL 与原文件。
第二步：校验备份清单与摘要，再在隔离环境恢复。
第三步：恢复后核对知识库、原文、工单和会话行为。
"@
$restoreTouched = $false
$failure = $null
$summary = [ordered]@{
    Status = 'RUNNING'
    SourceProject = $SourceProjectName
    RestoreProject = $RestoreProjectName
    BackupName = $BackupName
    KnowledgeBaseId = $null
    DocumentId = $null
    VersionId = $null
    IngestionJobId = $null
    RecoveryIngestionJobId = $null
    ChatSessionId = $null
    InterruptedRunId = $null
    Checks = [ordered]@{}
    Streaming = [ordered]@{ ModelEvidence = $ModelEvidenceLabel }
    RestoreEnvironment = '未启动'
}

try {
    [System.IO.File]::WriteAllText(
        $temporaryFile,
        $documentContent,
        [System.Text.UTF8Encoding]::new($false)
    )

    $sourceUpArguments = @('up', '--detach', '--wait')
    if (-not $SkipBuild) {
        $sourceUpArguments += '--build'
    }
    $sourceUpArguments += @('postgres', 'app', 'web')
    Invoke-SupportAiCompose -Context $sourceContext -Arguments $sourceUpArguments -Operation '启动源部署环境'
    Assert-Health -BaseUri $SourceBaseUri -EnvironmentName '源环境'
    $summary.Checks.SourceHealth = '通过'

    $adminClient = New-AuthenticatedClient -BaseUri $SourceBaseUri -Username 'admin' -Password 'admin'
    $createEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path '/api/knowledge-bases') `
        -Method Post `
        -Client $adminClient `
        -JsonBody @{ name = $knowledgeBaseName; description = 'F31 自动化一致性备份恢复验收' } `
        -ExpectedStatus 201 `
        -Operation '创建 F31 验收知识库'
    $knowledgeBaseId = [string]$createEnvelope.data.id
    if ([string]::IsNullOrWhiteSpace($knowledgeBaseId)) {
        throw '创建知识库响应缺少 id。'
    }
    $summary.KnowledgeBaseId = $knowledgeBaseId

    $uploadHeaders = Get-ClientHeaders -Client $adminClient -WriteRequest
    $uploadResponse = Invoke-WebRequest `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path "/api/knowledge-bases/$knowledgeBaseId/documents") `
        -Method Post `
        -WebSession $adminClient.Session `
        -Headers $uploadHeaders `
        -Form @{ file = Get-Item -LiteralPath $temporaryFile; displayName = 'F31 恢复验收文档' } `
        -SkipHttpErrorCheck
    if ([int]$uploadResponse.StatusCode -ne 202) {
        throw "上传 F31 验收文档失败，HTTP 状态码为 $([int]$uploadResponse.StatusCode)。"
    }
    $uploadEnvelope = Read-ApiEnvelope -Response $uploadResponse -Operation '上传 F31 验收文档'
    $documentId = [string]$uploadEnvelope.data.documentId
    $versionId = [string]$uploadEnvelope.data.versionId
    $jobId = [string]$uploadEnvelope.data.jobId
    if (@($documentId, $versionId, $jobId) | Where-Object { [string]::IsNullOrWhiteSpace($_) }) {
        throw '上传响应缺少 documentId、versionId 或 jobId。'
    }
    $summary.DocumentId = $documentId
    $summary.VersionId = $versionId
    $summary.IngestionJobId = $jobId

    $sourceContentEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path "/api/documents/$documentId/versions/$versionId/content") `
        -Method Get `
        -Client $adminClient `
        -ExpectedStatus 200 `
        -Operation '读取源环境文档原文'
    if ([string]$sourceContentEnvelope.data.content -ne $documentContent) {
        throw '源环境返回的文档原文与上传内容不一致。'
    }
    $summary.Checks.SourceDocumentContent = '通过'

    if (-not $SkipSeedImport) {
        Import-SeedSql -Context $sourceContext -SqlPath $identitySeedPath -SeedName '演示账号种子'
        Import-SeedSql -Context $sourceContext -SqlPath $ticketSeedPath -SeedName '模拟工单种子'
    }
    $supportClient = New-AuthenticatedClient -BaseUri $SourceBaseUri -Username 'support1' -Password 'support1'
    $sourceTicketEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path '/api/tickets/T10086') `
        -Method Get `
        -Client $supportClient `
        -ExpectedStatus 200 `
        -Operation '查询源环境工单 T10086'
    if ([string]$sourceTicketEnvelope.data.ticketNo -ne 'T10086') {
        throw '源环境工单响应与 T10086 不匹配。'
    }
    $summary.Checks.SourceTicket = '通过'

    $chatSessionId = $null
    $sourceMessageCount = 0
    if ($SkipLiveAi) {
        $summary.Streaming.Status = '已明确跳过，未计为通过'
    }
    else {
        Wait-IngestionSucceeded -BaseUri $SourceBaseUri -Client $adminClient -JobId $jobId
        $sessionEnvelope = Invoke-JsonApi `
            -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path '/api/chat/sessions') `
            -Method Post `
            -Client $adminClient `
            -JsonBody @{ kbIds = @($knowledgeBaseId); mode = 'RAG' } `
            -ExpectedStatus 201 `
            -Operation '创建源环境 RAG 会话'
        $chatSessionId = [string]$sessionEnvelope.data.sessionId
        if ([string]::IsNullOrWhiteSpace($chatSessionId)) {
            throw '创建源环境 RAG 会话响应缺少 sessionId。'
        }
        $summary.ChatSessionId = $chatSessionId
        $sourceSseResult = Invoke-RagSse -BaseUri $SourceBaseUri -Client $adminClient -SessionId $chatSessionId
        $sourceHistory = Invoke-JsonApi `
            -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path "/api/chat/sessions/$chatSessionId/messages") `
            -Method Get `
            -Client $adminClient `
            -ExpectedStatus 200 `
            -Operation '核对源环境问答历史与来源'
        $sourceMessages = @($sourceHistory.data.items)
        $sourceAssistantMessages = @($sourceMessages | Where-Object { $_.role -eq 'ASSISTANT' -and $_.status -eq 'COMPLETED' })
        if ($sourceHistory.data.contentReadable -ne $true -or $sourceMessages.Count -lt 2 -or $sourceAssistantMessages.Count -lt 1 -or @($sourceAssistantMessages[-1].sources).Count -lt 1) {
            throw '源环境问答历史或来源未完整持久化。'
        }
        $sourceMessageCount = $sourceMessages.Count
        $summary.Streaming.Source = $sourceSseResult
        $summary.Checks.SourceChatHistoryAndSources = '通过'
    }

    $recoverySourceStorageKey = Invoke-PostgresScalar -Context $sourceContext `
        -Sql "SELECT storage_key FROM document_version WHERE id='$versionId'::uuid;" `
        -Operation '读取恢复夹具源文件路径'
    $recoveryStorageKey = Copy-AcceptanceStorageFile -Context $sourceContext -SourceStorageKey $recoverySourceStorageKey
    # app 停止后写入过期租约夹具，确保只有重建后的新实例能够取得执行权。
    Stop-SupportAiApp -Context $sourceContext
    $recoveryDocumentId = [guid]::NewGuid().ToString()
    $recoveryVersionId = [guid]::NewGuid().ToString()
    $recoveryJobId = [guid]::NewGuid().ToString()
    $legacyLeaseToken = [guid]::NewGuid().ToString()
    $legacyIngestionSql = @"
WITH source_document AS (
    SELECT kb_id, created_by
    FROM document
    WHERE id = '$documentId'::UUID
      AND deleted_at IS NULL
), source_version AS (
    SELECT storage_key, file_hash, file_size, media_type, original_filename,
           parser_version, chunker_version, embedding_model, dimensions,
           config_hash, config_snapshot
    FROM document_version
    WHERE id = '$versionId'::UUID
      AND document_id = '$documentId'::UUID
      AND status = 'READY'
), inserted_document AS (
    INSERT INTO document (
        id, kb_id, name, active_version_id, pending_version_id, next_version_no,
        created_by, deleted_at, revision, created_at, updated_at
    )
    SELECT
        '$recoveryDocumentId'::UUID, kb_id, 'F31 重启恢复夹具',
        NULL, '$recoveryVersionId'::UUID, 2,
        created_by, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM source_document
    RETURNING id
), inserted_version AS (
    INSERT INTO document_version (
        id, document_id, version_no, storage_key, file_hash, file_size, media_type,
        original_filename, status, parser_version, chunker_version, embedding_model,
        dimensions, config_hash, config_snapshot, chunk_count, created_at, updated_at
    )
    SELECT
        '$recoveryVersionId'::UUID, inserted_document.id, 1,
        '$recoveryStorageKey', source_version.file_hash, source_version.file_size,
        source_version.media_type, source_version.original_filename, 'QUEUED',
        source_version.parser_version, source_version.chunker_version,
        source_version.embedding_model, source_version.dimensions,
        source_version.config_hash, source_version.config_snapshot, 0,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM inserted_document
    CROSS JOIN source_version
    RETURNING id, document_id
), inserted_job AS (
    INSERT INTO ingestion_job (
        id, version_id, status, stage, attempt, max_attempts,
        lease_owner, lease_token, lease_until, heartbeat_at, next_run_at,
        completed_chunks, total_chunks, error_code, error_detail, cancel_requested_at,
        started_at, finished_at, created_at, updated_at, trace_id
    )
    SELECT
        '$recoveryJobId'::UUID, inserted_version.id, 'RUNNING', 'PARSE', 1, 3,
        'f31-legacy-worker', '$legacyLeaseToken'::UUID,
        CURRENT_TIMESTAMP - INTERVAL '1 minute',
        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
        CURRENT_TIMESTAMP - INTERVAL '1 minute',
        0, NULL, NULL, NULL, NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP - INTERVAL '1 minute',
        'f31-ingestion-recovery'
    FROM inserted_version
    RETURNING id
)
SELECT id
FROM inserted_job;
"@
    $insertedRecoveryJobId = Invoke-PostgresScalar `
        -Context $sourceContext `
        -Sql $legacyIngestionSql `
        -Operation '写入 F31 过期入库租约夹具'
    if ($insertedRecoveryJobId -ne $recoveryJobId) {
        throw '过期入库租约夹具写入结果不完整。'
    }
    $summary.RecoveryIngestionJobId = $recoveryJobId

    Invoke-SupportAiCompose `
        -Context $sourceContext `
        -Arguments @('up', '--detach', '--force-recreate', '--wait', 'app', 'web') `
        -Operation '强制重建源环境 app'
    Assert-Health -BaseUri $SourceBaseUri -EnvironmentName '重建后的源环境'
    $oldSessionResponse = Invoke-HttpRequest `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path '/api/auth/me') `
        -Method Get `
        -Session $adminClient.Session `
        -Headers @{ Accept = 'application/json' } `
        -ExpectedStatus 401 `
        -Operation '验证重建后的旧会话失效'
    $null = $oldSessionResponse
    $summary.Checks.OldSessionInvalidated = '通过（HTTP 401）'

    $adminClient = New-AuthenticatedClient -BaseUri $SourceBaseUri -Username 'admin' -Password 'admin'
    $summary.Checks.ReloginAfterRecreate = '通过'

    Wait-IngestionSucceeded -BaseUri $SourceBaseUri -Client $adminClient -JobId $recoveryJobId
    $recoveryStateSql = @"
SELECT CONCAT_WS('|', job.status, version.status,
    CASE WHEN document.active_version_id = version.id THEN 'ACTIVE' ELSE 'INACTIVE' END,
    CASE WHEN document.pending_version_id IS NULL THEN 'PENDING_CLEARED' ELSE 'PENDING_PRESENT' END)
FROM ingestion_job job
INNER JOIN document_version version ON version.id = job.version_id
INNER JOIN document document ON document.id = version.document_id
WHERE job.id = '$recoveryJobId'::UUID;
"@
    $recoveryState = Invoke-PostgresScalar `
        -Context $sourceContext `
        -Sql $recoveryStateSql `
        -Operation '核对重启后的过期入库租约恢复状态'
    if ($recoveryState -ne 'SUCCEEDED|READY|ACTIVE|PENDING_CLEARED') {
        throw "过期入库租约恢复状态不完整：$recoveryState"
    }
    $recoveredFixtureContentEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path "/api/documents/$recoveryDocumentId/versions/$recoveryVersionId/content") `
        -Method Get `
        -Client $adminClient `
        -ExpectedStatus 200 `
        -Operation '核对重启恢复夹具的文档原文'
    if ([string]$recoveredFixtureContentEnvelope.data.content -ne $documentContent) {
        throw '重启恢复夹具的文档原文与源文件不一致。'
    }
    $summary.Checks.ExpiredIngestionLeaseRecovered = '通过（遗留 RUNNING 任务由新实例完成为 SUCCEEDED）'

    $recreatedContentEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $SourceBaseUri -Path "/api/documents/$documentId/versions/$versionId/content") `
        -Method Get `
        -Client $adminClient `
        -ExpectedStatus 200 `
        -Operation '核对容器重建后的文档原文'
    if ([string]$recreatedContentEnvelope.data.content -ne $documentContent) {
        throw '容器重建后的文档原文与上传内容不一致。'
    }
    $summary.Checks.ContainerRecreatePersistence = '通过'

    if (-not $SkipLiveAi) {
        $interruptedRunId = [guid]::NewGuid().ToString()
        $interruptedClientRequestId = [guid]::NewGuid().ToString()
        $requestHash = ('f' * 64) -join ''
        $legacyRunSql = @"
WITH completed_run AS (
    SELECT session_id, config_snapshot
    FROM chat_run
    WHERE session_id = '$chatSessionId'::UUID
      AND status = 'COMPLETED'
    ORDER BY created_at DESC
    LIMIT 1
), allocated AS (
    UPDATE chat_session AS session
    SET next_message_seq = session.next_message_seq + 2,
        updated_at = CURRENT_TIMESTAMP
    FROM completed_run
    WHERE session.id = completed_run.session_id
    RETURNING session.id AS session_id,
              session.next_message_seq - 2 AS user_seq,
              completed_run.config_snapshot
), inserted_run AS (
    INSERT INTO chat_run (
        id, session_id, client_request_id, request_hash, status, config_snapshot, usage, metrics,
        error_code, error_detail, cancel_requested_at, started_at, finished_at, created_at, updated_at
    )
    SELECT
        '$interruptedRunId'::UUID, session_id, '$interruptedClientRequestId'::UUID, '$requestHash',
        'RUNNING', config_snapshot, NULL, '{}'::JSONB, NULL, NULL, NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP - INTERVAL '1 minute'
    FROM allocated
    RETURNING id, session_id
), inserted_messages AS (
    INSERT INTO chat_message (
        id, run_id, session_id, seq, role, content, status, created_at, updated_at
    )
    SELECT
        gen_random_uuid(), inserted_run.id, inserted_run.session_id, allocated.user_seq,
        'USER', 'F31 应用重启恢复验收问题', 'COMPLETED',
        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP - INTERVAL '1 minute'
    FROM inserted_run
    INNER JOIN allocated ON allocated.session_id = inserted_run.session_id
    UNION ALL
    SELECT
        gen_random_uuid(), inserted_run.id, inserted_run.session_id, allocated.user_seq + 1,
        'ASSISTANT', '', 'STREAMING',
        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP - INTERVAL '1 minute'
    FROM inserted_run
    INNER JOIN allocated ON allocated.session_id = inserted_run.session_id
    RETURNING run_id
)
SELECT id
FROM inserted_run;
"@
        $insertedRunId = Invoke-PostgresScalar `
            -Context $sourceContext `
            -Sql $legacyRunSql `
            -Operation '写入 F31 遗留问答运行夹具'
        if ($insertedRunId -ne $interruptedRunId) {
            throw '遗留问答运行夹具未按预期写入。'
        }
        $summary.InterruptedRunId = $interruptedRunId
        if ($null -ne $MockOllamaBaseUri) {
            Reset-MockOllamaState -BaseUri $MockOllamaBaseUri
        }
    }

    & $backupScript -ProjectName $SourceProjectName -EnvFile $SourceEnvFile -BackupName $BackupName
    $summary.Checks.ConsistentBackup = '通过'

    $restoreTouched = $true
    Invoke-SupportAiCompose `
        -Context $restoreContext `
        -Arguments @('up', '--detach', '--wait', 'postgres') `
        -Operation '启动隔离恢复数据库'
    & $restoreScript -ProjectName $RestoreProjectName -EnvFile $RestoreEnvFile -BackupName $BackupName
    Invoke-SupportAiCompose `
        -Context $restoreContext `
        -Arguments @('up', '--detach', '--wait', 'app', 'web') `
        -Operation '等待隔离恢复环境就绪'
    Assert-Health -BaseUri $RestoreBaseUri -EnvironmentName '隔离恢复环境'
    $summary.RestoreEnvironment = '已恢复并通过健康检查'

    $restoredAdminClient = New-AuthenticatedClient -BaseUri $RestoreBaseUri -Username 'admin' -Password 'admin'
    $restoredKbEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $RestoreBaseUri -Path "/api/knowledge-bases/$knowledgeBaseId") `
        -Method Get `
        -Client $restoredAdminClient `
        -ExpectedStatus 200 `
        -Operation '核对恢复后的知识库'
    if ([string]$restoredKbEnvelope.data.name -ne $knowledgeBaseName) {
        throw '恢复后的知识库名称与备份前不一致。'
    }
    $summary.Checks.RestoredKnowledgeBase = '通过'

    $restoredContentEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $RestoreBaseUri -Path "/api/documents/$documentId/versions/$versionId/content") `
        -Method Get `
        -Client $restoredAdminClient `
        -ExpectedStatus 200 `
        -Operation '核对恢复后的文档原文'
    if ([string]$restoredContentEnvelope.data.content -ne $documentContent) {
        throw '恢复后的文档原文与备份前不一致。'
    }
    $summary.Checks.RestoredDocumentContent = '通过'

    $restoredSupportClient = New-AuthenticatedClient -BaseUri $RestoreBaseUri -Username 'support1' -Password 'support1'
    $restoredTicketEnvelope = Invoke-JsonApi `
        -Uri (Join-ApiUri -BaseUri $RestoreBaseUri -Path '/api/tickets/T10086') `
        -Method Get `
        -Client $restoredSupportClient `
        -ExpectedStatus 200 `
        -Operation '核对恢复后的工单 T10086'
    if ([string]$restoredTicketEnvelope.data.ticketNo -ne 'T10086') {
        throw '恢复后的工单响应与 T10086 不匹配。'
    }
    $summary.Checks.RestoredTicket = '通过'

    if ($SkipLiveAi) {
        $summary.Streaming.Status = '已明确跳过，未计为通过'
    }
    else {
        $interruptedRunEnvelope = Invoke-JsonApi `
            -Uri (Join-ApiUri -BaseUri $RestoreBaseUri -Path "/api/chat/runs/$interruptedRunId") `
            -Method Get `
            -Client $restoredAdminClient `
            -ExpectedStatus 200 `
            -Operation '核对恢复后的遗留问答收口'
        if ([string]$interruptedRunEnvelope.data.status -ne 'FAILED' -or [string]$interruptedRunEnvelope.data.errorCode -ne 'RUN_INTERRUPTED') {
            throw '恢复后的遗留问答未收口为 RUN_INTERRUPTED。'
        }
        $summary.Checks.InterruptedRunRecoveredWithoutReplay = '通过'

        if ($null -ne $MockOllamaBaseUri) {
            $mockState = Get-MockOllamaState -BaseUri $MockOllamaBaseUri
            if ([int]$mockState.calls.chat -ne 0) {
                throw '应用重启或恢复过程重新调用了模型，遗留问答存在重放。'
            }
            $summary.Checks.NoModelReplay = '通过（模型桩 chat 调用计数为 0）'
        }

        $restoredHistory = Invoke-JsonApi `
            -Uri (Join-ApiUri -BaseUri $RestoreBaseUri -Path "/api/chat/sessions/$chatSessionId/messages") `
            -Method Get `
            -Client $restoredAdminClient `
            -ExpectedStatus 200 `
            -Operation '核对恢复后的问答历史与来源'
        $restoredMessages = @($restoredHistory.data.items)
        $restoredAssistantMessages = @($restoredMessages | Where-Object { $_.role -eq 'ASSISTANT' -and $_.status -eq 'COMPLETED' })
        if ($restoredHistory.data.contentReadable -ne $true -or $restoredMessages.Count -lt $sourceMessageCount -or $restoredAssistantMessages.Count -lt 1 -or @($restoredAssistantMessages[-1].sources).Count -lt 1) {
            throw '恢复后的问答历史或来源与备份前不一致。'
        }
        $summary.Checks.RestoredChatHistoryAndSources = '通过'

        $restoredSseResult = Invoke-RagSse `
            -BaseUri $RestoreBaseUri `
            -Client $restoredAdminClient `
            -SessionId $chatSessionId
        $summary.Streaming.Status = '通过'
        $summary.Streaming.RestoredFollowUp = $restoredSseResult
    }

    $summary.Status = 'PASSED'
}
catch {
    $failure = $_
    $summary.Status = 'FAILED'
    $summary.Error = $_.Exception.Message
}
finally {
    if (Test-Path -LiteralPath $temporaryFile) {
        Remove-Item -LiteralPath $temporaryFile -Force
    }
    if ($restoreTouched) {
        try {
            # 仅停止隔离环境容器，刻意保留命名卷供人工复核或重复测试。
            Invoke-SupportAiCompose `
                -Context $restoreContext `
                -Arguments @('stop', 'web', 'app', 'postgres') `
                -Operation '停止隔离恢复环境'
            $summary.RestoreEnvironment = [string]$summary.RestoreEnvironment + '；容器已停止，数据卷保留'
        }
        catch {
            $summary.RestoreCleanupWarning = $_.Exception.Message
        }
    }

    Write-Host 'F31 部署验收摘要：'
    $summary | ConvertTo-Json -Depth 10
}

if ($null -ne $failure) {
    throw $failure
}
