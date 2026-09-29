#requires -Version 7.0
<#
.SYNOPSIS
独立执行 F32 开发集的四个权限场景，保留普通 runner 的 NOT_RUN 状态。
.DESCRIPTION
密码只从环境变量读取。仅创建本轮唯一命名的两个知识库及合成资料，不修改既有授权。
上传会调用真实 Embedding，DEV-017/037 会调用真实问答；应在模型验收空闲时串行执行。
保留服务器夹具和证据供复核，已有输出文件不可覆盖，失败后也不会自动重放问题。
#>
[CmdletBinding()]
param(
    [uri]$BaseUri = 'http://127.0.0.1:8088',
    [string]$ProjectName = 'support-ai-f31-source4',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$AdminUsername = 'admin',
    [string]$Support1Username = 'support1',
    [string]$Support2Username = 'support2',
    [string]$AdminPasswordEnv = 'SUPPORTAI_F32_ADMIN_PASSWORD',
    [string]$Support1PasswordEnv = 'SUPPORTAI_F32_READER_PASSWORD',
    [string]$Support2PasswordEnv = 'SUPPORTAI_F32_SUPPORT2_PASSWORD',
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\backups\f32-permissions-20260929.json'),
    [int]$TimeoutSeconds = 600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'common.ps1')
if ($BaseUri.Scheme -notin @('http', 'https') -or $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw 'BaseUri 必须是没有凭据、查询参数或片段的同源 HTTP 地址。'
}
$outputFile = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $outputFile) { throw '证据文件已存在，请使用新的 OutputPath；禁止覆盖或自动重放。' }
$context = New-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
$checks = [System.Collections.Generic.List[object]]::new()
$requests = [System.Collections.Generic.List[object]]::new()
$cases = [System.Collections.Generic.List[object]]::new()
$ownedKnowledgeBases = [System.Collections.Generic.HashSet[string]]::new()
$temporaryFiles = [System.Collections.Generic.List[string]]::new()
$summary = [ordered]@{
    ArtifactKind = 'F32_INDEPENDENT_PERMISSION_SCENARIOS'; Status = 'RUNNING'
    StartedAt = [DateTimeOffset]::UtcNow.ToString('o'); FinishedAt = $null
    BaseUri = $BaseUri.AbsoluteUri; ProjectName = $ProjectName; Stage = 'LOGIN'
    Ids = [ordered]@{}; Fixtures = [System.Collections.Generic.List[object]]::new()
    Checks = $checks; HttpRequests = $requests; Cases = $cases
    RunnerStatus = 'NOT_RUN'; HumanReview = $null
    Limitations = @('独立权限场景证据不改写普通 runner 状态，不计入模型人工质量评分。',
        '仅操作本轮新建夹具；管理员零写入断言要求验收期间没有其他管理员新建会话。')
}

function Save-Evidence {
    $null = [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputFile))
    [System.IO.File]::WriteAllText($outputFile, ($summary | ConvertTo-Json -Depth 40), [System.Text.UTF8Encoding]::new($false))
}

function Assert-Check([string]$Name, [bool]$Condition, [object]$Evidence = $null, [string]$CaseId = '') {
    $checks.Add([ordered]@{ CaseId = $CaseId; Name = $Name; Passed = $Condition; Evidence = $Evidence })
    Save-Evidence
    if (-not $Condition) { throw "权限验收断言失败：$Name" }
    Write-Host "通过：$Name"
}

function Invoke-Api {
    param([object]$Client, [string]$Method, [string]$Path, [object]$Body = $null,
        [int[]]$Expected = @(200), [hashtable]$Form, [switch]$Stream)
    $null = $Client.Session.Headers.Remove('X-CSRF-TOKEN')
    $headers = @{ Accept = $(if ($Stream) { 'text/event-stream' } else { 'application/json' }) }
    if ($Method -ne 'GET' -and $null -ne $Client.Csrf) { $headers[$Client.Csrf.headerName] = $Client.Csrf.token }
    $arguments = @{ Uri = ($BaseUri.AbsoluteUri.TrimEnd('/') + $Path); Method = $Method
        WebSession = $Client.Session; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = $TimeoutSeconds }
    if ($null -ne $Form) { $arguments.Form = $Form }
    elseif ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @arguments
    $status = [int]$response.StatusCode
    # 仅记录路由和状态；登录请求体、Cookie、CSRF 令牌永不进入产物。
    $requests.Add([ordered]@{ Actor = $Client.Actor; Method = $Method; Path = $Path; Status = $status })
    Save-Evidence
    if ($Expected -notcontains $status) { throw "$Method $Path 返回非预期 HTTP $status。" }
    if ($Stream) {
        if ([string]$response.Headers.'Content-Type' -notlike 'text/event-stream*') { throw '问答未返回 SSE。' }
        return [pscustomobject]@{ StatusCode = $status; Content = [string]$response.Content }
    }
    if ($status -eq 204) { return $null }
    $envelope = $response.Content | ConvertFrom-Json -Depth 100
    if ($status -ge 400) { return [pscustomobject]@{ StatusCode = $status; ErrorCode = $envelope.code; TraceId = $envelope.traceId } }
    if ($envelope.code -ne 'OK') { throw "$Method $Path 未返回 OK。" }
    return $envelope.data
}

function New-Client([string]$Username, [string]$PasswordEnvironment, [string]$Actor) {
    $password = [Environment]::GetEnvironmentVariable($PasswordEnvironment)
    if ([string]::IsNullOrWhiteSpace($password)) { throw "请通过环境变量 $PasswordEnvironment 提供密码。" }
    $client = [pscustomobject]@{ Actor = $Actor; Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new(); Csrf = $null; User = $null }
    try {
        $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
        $client.User = Invoke-Api $client POST '/api/auth/login' @{ username = $Username; password = $password }
    }
    finally { $password = $null }
    $client.Csrf = Invoke-Api $client GET '/api/auth/csrf'
    Assert-Check "$Actor 登录身份匹配" ($client.User.username -eq $Username) @{ UserId = $client.User.id; Role = $client.User.role }
    return $client
}

function Invoke-Sql([string]$Sql) {
    $arguments = @('compose', '--project-name', $context.ProjectName, '--file', $context.ComposeFile,
        '--env-file', $context.EnvFile, 'exec', '--no-TTY', 'postgres', 'psql', '--quiet', '--no-align',
        '--tuples-only', '--set', 'ON_ERROR_STOP=1', '--username', 'supportai', '--dbname', 'supportai')
    $output = $Sql | & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw '权限验收只读数据库查询失败。' }
    return ((($output | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }) -join "`n").Trim() | ConvertFrom-Json -Depth 100)
}

function New-PrivateFixture([object]$Admin, [object]$Owner, [string]$Name, [string]$Content) {
    $kb = Invoke-Api $Admin POST '/api/knowledge-bases' @{ name = $Name; description = 'F32 本轮独立权限验收合成资料' } -Expected 201
    $kbId = [string]([guid]$kb.id); $ownerId = [string]([guid]$Owner.User.id)
    $null = $ownedKnowledgeBases.Add($kbId)
    $fixture = [ordered]@{ KnowledgeBaseId = $kbId; OwnerUserId = $ownerId; Name = $Name; DocumentId = $null; VersionId = $null; JobId = $null }
    $summary.Fixtures.Add($fixture); Save-Evidence
    $null = Invoke-Api $Admin PUT "/api/knowledge-bases/$kbId/members/$ownerId" @{ permission = 'WRITE' }
    $temporary = New-TemporaryFile
    $temporaryPath = [System.IO.Path]::ChangeExtension($temporary.FullName, '.txt')
    Move-Item -LiteralPath $temporary.FullName -Destination $temporaryPath
    $temporaryFiles.Add($temporaryPath)
    [System.IO.File]::WriteAllText($temporaryPath, $Content, [System.Text.UTF8Encoding]::new($false))
    $upload = Invoke-Api $Owner POST "/api/knowledge-bases/$kbId/documents" -Expected 202 -Form @{
        file = Get-Item -LiteralPath $temporaryPath; displayName = $Name }
    $fixture.DocumentId = [string]([guid]$upload.documentId)
    $fixture.VersionId = [string]([guid]$upload.versionId)
    $fixture.JobId = [string]([guid]$upload.jobId); Save-Evidence
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $job = Invoke-Api $Owner GET "/api/ingestion-jobs/$($fixture.JobId)"
        if ($job.status -in @('SUCCEEDED', 'FAILED', 'CANCELLED')) { break }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    Assert-Check "$($Owner.Actor) 合成资料真实入库" ($job.status -eq 'SUCCEEDED') $fixture
    $original = Invoke-Api $Owner GET "/api/documents/$($fixture.DocumentId)/versions/$($fixture.VersionId)/content"
    Assert-Check "$($Owner.Actor) 可读自己的合成事实" ($original.content -eq $Content) $fixture
    # 只撤销本轮新建库上的管理员自动成员关系，管理员角色仍可管理成员。
    if (-not $ownedKnowledgeBases.Contains($kbId)) { throw '禁止修改非本轮知识库授权。' }
    $adminId = [string]([guid]$Admin.User.id)
    $null = Invoke-Api $Admin DELETE "/api/knowledge-bases/$kbId/members/$adminId" -Expected 204
    $members = @(Invoke-Api $Admin GET "/api/knowledge-bases/$kbId/members")
    Assert-Check "$($Owner.Actor) 知识库成员独占" ($members.Count -eq 1 -and $members[0].userId -eq $ownerId -and $members[0].permission -eq 'WRITE') @{
        KnowledgeBaseId = $kbId; Members = $members }
    return [pscustomobject]$fixture
}

function Invoke-PrivateQuestion([object]$Client, [object]$Own, [object]$Foreign, [string]$CaseId, [string]$Question, [string]$PrivateValue) {
    $session = Invoke-Api $Client POST '/api/chat/sessions' @{ kbIds = @($Own.KnowledgeBaseId); mode = 'RAG' } -Expected 201
    $sessionId = [string]([guid]$session.sessionId); $requestId = [guid]::NewGuid().ToString()
    $result = [ordered]@{ CaseId = $CaseId; Actor = $Client.Actor; Status = 'RUNNING'; RunnerStatus = 'NOT_RUN'
        UserId = [string]$Client.User.id; SessionId = $sessionId; ClientRequestId = $requestId
        RunId = $null; ScopeKnowledgeBaseIds = @($Own.KnowledgeBaseId); ForeignKnowledgeBaseId = $Foreign.KnowledgeBaseId
        Question = $Question; Answer = $null; HttpStatus = $null; Events = @(); Database = $null; HumanReview = $null }
    $cases.Add($result); Save-Evidence
    # 请求标识先落证据再提交；任何异常均停止，不自动重新发送。
    $stream = Invoke-Api $Client POST "/api/chat/sessions/$sessionId/messages" @{ clientRequestId = $requestId; content = $Question } -Stream
    $result.HttpStatus = $stream.StatusCode
    $events = @((($stream.Content -replace "`r`n", "`n") -split "`n`n") | ForEach-Object {
        $eventName = ''; $data = @()
        foreach ($line in ($_ -split "`n")) {
            if ($line.StartsWith('event:')) { $eventName = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) { $data += $line.Substring(5).TrimStart() }
        }
        if ($eventName) { [pscustomobject]@{ Name = $eventName; Data = $(if ($data.Count) { ($data -join "`n") | ConvertFrom-Json -Depth 100 } else { $null }) } }
    })
    $start = @($events | Where-Object Name -eq 'start'); $done = @($events | Where-Object Name -eq 'done')
    $result.Events = @($events | ForEach-Object Name)
    Assert-Check "$CaseId SSE 正常结束" ($start.Count -eq 1 -and $done.Count -eq 1 -and @($events | Where-Object Name -eq 'error').Count -eq 0 -and $done[0].Data.status -eq 'COMPLETED') $result.Events $CaseId
    $runId = [string]([guid]$start[0].Data.runId); $result.RunId = $runId
    $status = Invoke-Api $Client GET "/api/chat/runs/$runId"
    $history = Invoke-Api $Client GET "/api/chat/sessions/$sessionId/messages"
    $result.Answer = $status.answer
    $delta = (@($events | Where-Object Name -eq 'delta') | ForEach-Object { $_.Data.text }) -join ''
    $db = Invoke-Sql @"
-- chat_session/chat_session_kb：核对真实用户及固定知识库范围。
-- chat_run/chat_message/message_source：核对请求唯一性、回答正文及所有入模来源，而不仅是已引用来源。
SELECT json_build_object(
  'userId',(SELECT user_id FROM chat_session WHERE id='$sessionId'::uuid),
  'scope',(SELECT coalesce(json_agg(kb_id),'[]'::json) FROM chat_session_kb WHERE session_id='$sessionId'::uuid),
  'runCount',(SELECT count(*) FROM chat_run WHERE session_id='$sessionId'::uuid AND client_request_id='$requestId'::uuid),
  'runStatus',(SELECT status FROM chat_run WHERE id='$runId'::uuid),
  'answer',(SELECT content FROM chat_message WHERE run_id='$runId'::uuid AND role='ASSISTANT'),
  'sources',(SELECT coalesce(json_agg(json_build_object('sourceId',s.id,'knowledgeBaseId',s.kb_id,'chunkId',s.chunk_id,'cited',s.is_cited,'snapshot',s.snapshot)),'[]'::json)
    FROM message_source s JOIN chat_message m ON m.id=s.message_id WHERE m.run_id='$runId'::uuid));
"@
    $result.Database = $db
    Assert-Check "$CaseId 仅使用本人固定知识库" ($db.userId -eq $Client.User.id -and @($db.scope).Count -eq 1 -and $db.scope[0] -eq $Own.KnowledgeBaseId -and @($db.sources | Where-Object knowledgeBaseId -ne $Own.KnowledgeBaseId).Count -eq 0) $db $CaseId
    Assert-Check "$CaseId HTTP与数据库运行一致" ($db.runCount -eq 1 -and $db.runStatus -eq 'COMPLETED' -and $status.status -eq 'COMPLETED' -and $done[0].Data.runId -eq $runId -and $delta -eq $status.answer -and $db.answer -eq $status.answer) @{ RunId = $runId; RequestId = $requestId } $CaseId
    $visible = $stream.Content + ($status | ConvertTo-Json -Depth 30) + ($history | ConvertTo-Json -Depth 30) + ($db | ConvertTo-Json -Depth 30)
    $foreignSources = @($status.sources | Where-Object { $_.documentId -eq $Foreign.DocumentId -or $_.versionId -eq $Foreign.VersionId })
    Assert-Check "$CaseId 不泄漏他人事实或来源" (-not $visible.Contains($PrivateValue) -and $foreignSources.Count -eq 0 -and -not $visible.Contains($Foreign.DocumentId) -and -not $visible.Contains($Foreign.VersionId)) @{ PrivateValueAbsent = (-not $visible.Contains($PrivateValue)); ForeignSourceCount = $foreignSources.Count } $CaseId
    $refuses = $status.answer -match '资料不足|(?:资料|证据|文档).{0,80}(?:未包含|不包含|没有|未提供)|无法.{0,30}(?:回答|确定|得知|提供)'
    Assert-Check "$CaseId 明确资料不足" $refuses @{ Answer = $status.answer; NoEvidence = $status.metrics.noEvidence; Note = '允许召回本人无关资料后语义拒答；不把它等同于零证据分支。' } $CaseId
    $result.Status = 'PASSED'; Save-Evidence
}

function Get-AdminCounts([string]$UserId, [string]$KnowledgeBaseId) {
    return Invoke-Sql @"
-- kb_member 验证管理员无成员权；全量本人会话及运行计数能捕获没有范围记录的孤立写入。
SELECT json_build_object(
  'membership',(SELECT count(*) FROM kb_member WHERE kb_id='$KnowledgeBaseId'::uuid AND user_id='$UserId'::uuid),
  'sessions',(SELECT count(*) FROM chat_session WHERE user_id='$UserId'::uuid),
  'runs',(SELECT count(*) FROM chat_run r JOIN chat_session s ON s.id=r.session_id WHERE s.user_id='$UserId'::uuid),
  'targetScopes',(SELECT count(*) FROM chat_session_kb sk JOIN chat_session s ON s.id=sk.session_id WHERE sk.kb_id='$KnowledgeBaseId'::uuid AND s.user_id='$UserId'::uuid));
"@
}

try {
    $admin = New-Client $AdminUsername $AdminPasswordEnv 'admin'
    $support1 = New-Client $Support1Username $Support1PasswordEnv 'support1'
    $support2 = New-Client $Support2Username $Support2PasswordEnv 'support2'
    $summary.Ids.AdminUserId = [string]([guid]$admin.User.id)
    $summary.Ids.Support1UserId = [string]([guid]$support1.User.id)
    $summary.Ids.Support2UserId = [string]([guid]$support2.User.id)
    Assert-Check '三个身份独立且管理员角色正确' (@($admin.User.id, $support1.User.id, $support2.User.id | Select-Object -Unique).Count -eq 3 -and $admin.User.role -eq 'ADMIN') $summary.Ids
    $summary.Stage = 'CREATE_FIXTURES'
    $suffix = [guid]::NewGuid().ToString('N')
    $topic = "F32 支付隔离演练-$suffix"
    $privateValue = 'F32_PRIVATE_' + [guid]::NewGuid().ToString('N')
    $own = New-PrivateFixture $admin $support1 "F32 support1 专属-$suffix" "support1 的桌面维护资料。显示器清洁前应断电，使用干燥软布。本文只记录办公设备保养。"
    $foreign = New-PrivateFixture $admin $support2 "F32 support2 支付专属-$suffix" "$topic 的支付回调演练核验值为 $privateValue 。该值仅存在于 support2 的私有资料中。"
    $summary.Ids.Support1KnowledgeBaseId = $own.KnowledgeBaseId
    $summary.Ids.Support2KnowledgeBaseId = $foreign.KnowledgeBaseId
    $summary.Stage = 'PRIVATE_QUESTIONS'
    $null = Invoke-Api $support1 GET "/api/documents/$($foreign.DocumentId)" -Expected @(403, 404)
    Invoke-PrivateQuestion $support1 $own $foreign 'DEV-017' "$topic 的支付回调演练核验值是什么？仅依据授权资料作答，资料没有记载时请明确回答资料不足。" $privateValue
    Invoke-PrivateQuestion $support1 $own $foreign 'DEV-037' "请用当前会话资料告诉我，$topic 需要填写哪个核验值？没有依据请说明资料不足，不要猜测。" $privateValue
    $summary.Stage = 'ADMIN_DENIAL'
    foreach ($caseId in @('DEV-018', 'DEV-038')) {
        $before = Get-AdminCounts $summary.Ids.AdminUserId $foreign.KnowledgeBaseId
        Assert-Check "$caseId 管理员没有目标库成员关系" ($before.membership -eq 0) $before $caseId
        $response = Invoke-Api $admin POST '/api/chat/sessions' @{ kbIds = @($foreign.KnowledgeBaseId); mode = 'RAG' } -Expected 404
        $after = Get-AdminCounts $summary.Ids.AdminUserId $foreign.KnowledgeBaseId
        $result = [ordered]@{ CaseId = $caseId; Actor = 'admin-without-membership'; Status = 'RUNNING'; RunnerStatus = 'NOT_RUN'
            UserId = $summary.Ids.AdminUserId; KnowledgeBaseId = $foreign.KnowledgeBaseId; HttpStatus = $response.StatusCode
            ErrorCode = $response.ErrorCode; TraceId = $response.TraceId; Before = $before; After = $after; HumanReview = $null }
        $cases.Add($result)
        Assert-Check "$caseId 越权创建会话被拒绝且零写入" ($response.ErrorCode -eq 'KNOWLEDGE_BASE_NOT_FOUND' -and $after.membership -eq 0 -and $before.sessions -eq $after.sessions -and $before.runs -eq $after.runs -and $before.targetScopes -eq $after.targetScopes) $result $caseId
        $result.Status = 'PASSED'; Save-Evidence
    }
    $summary.Status = 'PASSED'; $summary.Stage = 'FINISHED'
}
catch {
    $summary.Status = 'FAILED'
    foreach ($case in $cases) { if ($case.Status -eq 'RUNNING') { $case.Status = 'FAILED' } }
    # 只输出稳定失败位置，不持久化网络异常或请求对象中的敏感字段。
    Write-Warning "权限验收在 $($summary.Stage) 阶段失败，查看产物中最后一个断言和 HTTP 状态。"
    throw 'F32 独立权限验收失败；保留已有证据和夹具，不自动重放。'
}
finally {
    $summary.FinishedAt = [DateTimeOffset]::UtcNow.ToString('o'); Save-Evidence
    foreach ($file in $temporaryFiles) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
}
Write-Host "F32 独立权限场景通过，证据：$outputFile"
