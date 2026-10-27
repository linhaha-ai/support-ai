import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// 使用独立浏览器上下文；凭据只从进程环境读取，不保存 Cookie、请求体或登录页截图。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const output = path.resolve(root, option('--output', 'evaluation/results/m7-browser.json'));
const baseUrl = process.env.M7_BROWSER_BASE_URL || 'http://127.0.0.1:8088';
const parsedBaseUrl = new URL(baseUrl);
const knowledgeBaseId = process.env.M7_BROWSER_KB_ID || '238cbcc3-71be-44bf-ae75-8477ae5c5a56';
const username = process.env.M7_BROWSER_USERNAME;
const password = process.env.M7_BROWSER_PASSWORD;
const modulePath = process.env.M7_PLAYWRIGHT_MODULE;
const executablePath = process.env.M7_BROWSER_EXECUTABLE;
assert(username && password && modulePath && executablePath,
  '必须设置 M7_BROWSER_USERNAME、M7_BROWSER_PASSWORD、M7_PLAYWRIGHT_MODULE 和 M7_BROWSER_EXECUTABLE');
assert(['127.0.0.1', 'localhost', '[::1]'].includes(parsedBaseUrl.hostname), '仅允许本机部署验收');
assert(!parsedBaseUrl.username && !parsedBaseUrl.password && !parsedBaseUrl.search && !parsedBaseUrl.hash,
  '部署地址不得携带凭据、查询参数或片段');
const business = JSON.parse(readFileSync(path.join(root, 'evaluation/results/f32-business.json'), 'utf8'));
assert(!existsSync(output), '产物已存在，请使用新的 --output 文件名保留既有证据');
mkdirSync(path.dirname(output), { recursive: true });
const redact = (value) => String(value).replaceAll(password, '[REDACTED]').slice(0, 1500);
const started = Date.now();
const result = {
  schemaVersion: '1.0', artifactKind: 'M7_HEADLESS_BROWSER_ACCEPTANCE', status: 'RUNNING',
  startedAt: new Date(started).toISOString(), baseUrl, knowledgeBaseId,
  gitCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  scriptSha256: createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex'),
  execution: '独立无头 Edge / Playwright，真实页面与后端，未模拟网络或模型响应',
  streamObservation: '只读克隆同一次真实 SSE Response；原响应直接交给应用，不替换数据或额外发送模型请求',
  checks: [], screenshots: [], consoleErrors: [], pageErrors: [], failedHttp: [], findings: [],
};
let browser;
let page;
let authenticated = false;
const checked = async (name, action) => {
  const at = Date.now();
  try {
    const evidence = await action();
    result.checks.push({ name, status: 'PASSED', durationMs: Date.now() - at, evidence });
  } catch (error) {
    result.checks.push({ name, status: 'FAILED', durationMs: Date.now() - at, error: redact(error.message) });
    throw error;
  }
};
const shot = async (name) => {
  const target = output.replace(/\.json$/i, `-${name}.png`);
  const drawer = page.locator('.chat-source-drawer');
  const drawerVisible = await drawer.isVisible();
  if (drawerVisible) {
    // 抽屉结束滑入动画后再截取视口，避免留下仅有一条正文的中间画面。
    let previous = null;
    let stable = 0;
    for (let attempt = 0; attempt < 40; attempt += 1) {
      const box = await drawer.boundingBox();
      const inside = box && box.x >= 0 && box.x + box.width <= page.viewportSize().width + 1;
      stable = inside && previous && Math.abs(previous.x - box.x) < 0.5 ? stable + 1 : 0;
      if (stable >= 3) break;
      previous = box;
      await page.waitForTimeout(50);
    }
    assert(stable >= 3, '来源抽屉未稳定进入视口');
  }
  await page.screenshot({ path: target, fullPage: !drawerVisible });
  result.screenshots.push(path.relative(root, target).replaceAll('\\', '/'));
};
const menu = (name) => page.locator('.navigation-item').filter({ hasText: name }).click();
const waitText = (selector, text) => page.waitForFunction(({ selector, text }) =>
  document.querySelector(selector)?.textContent.includes(text), { selector, text });
const observeStream = async (sessionId) => page.evaluate((targetPath) => {
  // Edge 调试协议可能回收流式响应体，独立只读分支保留同次响应的完整事件。
  window.__m7CapturedStream = { done: false, text: null, error: null };
  const originalFetch = window.fetch;
  window.fetch = async (...args) => {
    const response = await originalFetch.apply(window, args);
    const url = args[0] instanceof Request ? args[0].url : String(args[0]);
    const method = args[1]?.method || (args[0] instanceof Request ? args[0].method : 'GET');
    if (new URL(url, window.location.href).pathname === targetPath && method === 'POST') {
      void response.clone().text().then((text) => {
        window.__m7CapturedStream = { done: true, text, error: null };
      }, (error) => {
        window.__m7CapturedStream = { done: true, text: null, error: error.name };
      });
    }
    return response;
  };
}, `/api/chat/sessions/${sessionId}/messages`);
const streamBody = async () => {
  await page.waitForFunction(() => window.__m7CapturedStream?.done, null, { timeout: 180000 });
  const stream = await page.evaluate(() => window.__m7CapturedStream);
  assert.equal(stream.error, null, 'SSE 只读副本读取失败');
  return stream.text;
};

// 与后端引用契约一致：整个范围都已登记时才展开，未知或畸形编号不生成入口。
const citedNumbers = (answer, sources) => {
  const registered = new Set(sources.map((source) => source.number));
  const numbers = new Set();
  const horizontal = '[\\t \\u00a0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000]*';
  const pattern = new RegExp(`\\[([0-9]+)\\]${horizontal}(?:[-–—]|至|到)${horizontal}\\[([0-9]+)\\]|\\[([0-9]+)${horizontal}[-–—]${horizontal}([0-9]+)\\]|\\[\\^?([0-9]+)\\]`, 'g');
  const canonical = (value) => /^[1-9][0-9]*$/.test(value) && Number(value) <= 2147483647;
  for (const match of answer.matchAll(pattern)) {
    const start = match[5] ?? match[1] ?? match[3];
    const end = match[5] ?? match[2] ?? match[4];
    if (!canonical(start) || !canonical(end)) continue;
    const length = Number(end) - Number(start) + 1;
    if (length < 1 || length > 100) continue;
    const range = Array.from({ length }, (_, index) => Number(start) + index);
    if (range.every((number) => registered.has(number))) range.forEach((number) => numbers.add(number));
  }
  return [...numbers];
};
const completedFrames = async (sessionId) => {
  const frames = (await streamBody()).split(/\r?\n\r?\n/).map((frame) => {
    const event = frame.match(/^event:\s*(\S+)/m)?.[1];
    const data = frame.match(/^data:\s*(.+)$/m)?.[1];
    return event && data ? { event, data: JSON.parse(data) } : null;
  }).filter(Boolean);
  const terminal = frames.filter((frame) => ['done', 'error'].includes(frame.event));
  const observation = { sessionId, runId: frames.find((frame) => frame.event === 'start')?.data.runId,
    terminal, tools: frames.filter((frame) => frame.event === 'tool').map((frame) => frame.data) };
  (result.streams ??= []).push(observation);
  assert(observation.runId, 'SSE 缺少运行身份');
  assert.equal(terminal.length, 1, 'SSE 必须且只能有一个终态');
  assert.equal(frames.at(-1)?.event, 'done', `业务未成功完成：${JSON.stringify(terminal)}`);
  await page.locator('.chat-message-assistant .chat-message-heading .el-tag').filter({ hasText: '已完成' }).waitFor();
  return frames;
};

try {
  const { chromium } = await import(pathToFileURL(modulePath).href);
  browser = await chromium.launch({ executablePath, headless: true });
  result.browserVersion = browser.version();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  page = await context.newPage();
  page.setDefaultTimeout(20000);
  page.on('console', (message) => {
    if (message.type() === 'error') result.consoleErrors.push({ text: redact(message.text()),
      path: message.location().url ? new URL(message.location().url).pathname : null });
  });
  page.on('pageerror', (error) => result.pageErrors.push(redact(error.message)));
  page.on('response', (response) => {
    if (response.status() >= 400) result.failedHttp.push({ status: response.status(),
      path: new URL(response.url()).pathname, method: response.request().method() });
  });

  await checked('未登录显示登录页且写入按钮不可用', async () => {
    await page.goto(baseUrl, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: '登录工作空间' }).waitFor();
    assert(await page.getByRole('button', { name: '登录', exact: true }).isDisabled());
    return { loginHeadingVisible: true, emptyLoginDisabled: true };
  });
  await checked('通过真实表单登录并显示健康概览', async () => {
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('heading', { name: '登录成功，工作空间已就绪。' }).waitFor();
    authenticated = true;
    await waitText('.status-card', '后端连接成功');
    const phaseLabel = await page.locator('.phase-caption').innerText();
    if (phaseLabel.includes('F25')) result.findings.push({ severity: 'LOW',
      detail: '运行概览仍显示当前阶段 F25；不影响本轮业务行为，但阶段文案尚未同步到 M7。' });
    return { authenticated: true, backendHealthy: true, displayedPhase: phaseLabel };
  });

  if (args.includes('--review-existing')) {
    // 复核只读取已生成会话，不重复问答，也不覆盖原失败产物。
    const original = JSON.parse(readFileSync(path.resolve(root, option('--review-existing')), 'utf8'));
    const session = original.checks.find((item) => item.name === '选择固定知识库创建独立 RAG 会话并重命名').evidence;
    const source = original.checks.find((item) => item.name === '引用按钮打开对应真实原文且显示版本身份').evidence;
    result.reviewOf = option('--review-existing');
    result.reviewScope = '已生成 RAG 会话的只读来源截图与退出复核；原助手 FAILED 结论保持不变，零模型请求';
    await checked('已生成会话的来源身份与稳定抽屉截图复核', async () => {
      await menu('会话');
      await page.locator('.chat-session-list-item').filter({ hasText: session.sessionTitle }).click();
      const requested = page.waitForResponse((response) => new URL(response.url()).pathname === source.sourcePath);
      await page.locator('.chat-source-button').filter({ hasText: `[${source.citedNumber}]` }).click();
      const response = await requested;
      assert.equal(response.status(), 200);
      const detail = (await response.json()).data;
      for (const key of ['chunkId', 'documentId', 'versionId']) assert.equal(detail[key], source[key]);
      await page.locator('.chat-source-content').waitFor();
      assert.equal(await page.locator('.chat-source-content').innerText(), detail.content);
      await shot('source-stable');
      await page.locator('.chat-source-drawer .el-drawer__close-btn').click();
      return { sessionId: session.sessionId, sourcePath: source.sourcePath, chunkId: detail.chunkId,
        documentId: detail.documentId, versionId: detail.versionId, contentCharacters: detail.content.length };
    });
  } else {

  let knowledgeBaseName;
  let businessKnowledgeBaseName;
  await checked('我的知识库列出合成验收资料且可查看文档', async () => {
    const response = await context.request.get(`${baseUrl}/api/knowledge-bases`);
    assert.equal(response.status(), 200);
    const envelope = await response.json();
    const kb = envelope.data.find((item) => item.id === knowledgeBaseId);
    assert(kb, '当前账号未获得目标合成知识库读取权限');
    knowledgeBaseName = kb.name;
    businessKnowledgeBaseName = envelope.data.find((item) => item.id === business.Ids.KnowledgeBaseId)?.name;
    assert(businessKnowledgeBaseName, '当前账号未获得助手合成知识库读取权限');
    await menu('知识库');
    const card = page.locator('.knowledge-item').filter({ has: page.getByRole('heading', { name: knowledgeBaseName, exact: true }) });
    await card.getByRole('button', { name: '查看文档', exact: true }).click();
    await page.locator('.document-list-item').first().waitFor();
    const names = await page.locator('.document-list-name').allTextContents();
    assert(names.length >= 6);
    return { knowledgeBaseName, documentCount: names.length, documentNames: names };
  });
  await checked('支付回调文档当前原文、历史版本和分块均可查看', async () => {
    await page.locator('.document-list-item').filter({ hasText: 'payment-callback-guide' }).click();
    await waitText('.document-content', '/openapi/v2/payment/callback');
    const versions = page.locator('.document-version-item');
    assert.equal(await versions.count(), 2);
    await versions.filter({ hasText: 'V1 ·' }).click();
    await waitText('.document-content', '/openapi/v1/payment/callback');
    const historicalText = await page.locator('.document-content').innerText();
    assert(historicalText.includes('5 秒'));
    await versions.filter({ hasText: 'V2 ·' }).click();
    await waitText('.document-content', '/openapi/v2/payment/callback');
    await page.getByRole('button', { name: '分块', exact: true }).click();
    await page.locator('.document-chunk-item').first().waitFor();
    const chunkCount = await page.locator('.document-chunk-item').count();
    assert(chunkCount > 0);
    await shot('documents');
    return { versionCount: 2, historicalFiveSecondContract: true, currentV2Contract: true, visibleChunkCount: chunkCount };
  });
  await checked('工单页面显示真实合成工单状态与查询时间', async () => {
    await menu('工单查询');
    await page.getByLabel('工单编号', { exact: true }).fill(business.Ids.TicketNo);
    const queried = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/tickets/${business.Ids.TicketNo}`);
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const response = await queried;
    assert.equal(response.status(), 200);
    const ticket = (await response.json()).data;
    await page.locator('.ticket-card').waitFor();
    const card = await page.locator('.ticket-card').innerText();
    assert(card.includes(ticket.ticketNo) && card.includes(ticket.status) && card.includes(ticket.title));
    assert(card.includes('状态更新时间') && card.includes('本次查询时间'));
    return { ticketNo: ticket.ticketNo, status: ticket.status, knowledgeBaseId: ticket.knowledgeBaseId, queriedAt: ticket.queriedAt };
  });
  await checked('不存在工单显示明确反馈而非保留旧卡片', async () => {
    await page.getByLabel('工单编号', { exact: true }).fill('M7-NOT-EXIST');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.locator('.ticket-alert').waitFor();
    const message = await page.locator('.ticket-alert').innerText();
    assert(message.includes('未找到该工单') && message.includes('无权查看'));
    assert.equal(await page.locator('.ticket-card').count(), 0);
    return { message, visibleTicketCards: 0 };
  });

  let sessionId;
  const sessionTitle = `M7 浏览器验收 ${new Date().toISOString()}`;
  await checked('选择固定知识库创建独立 RAG 会话并重命名', async () => {
    await menu('会话');
    const scopeOption = page.locator('.el-checkbox').filter({ hasText: knowledgeBaseName });
    await scopeOption.click();
    assert(await scopeOption.getByRole('checkbox').isChecked());
    const created = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/chat/sessions'
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: '创建会话', exact: true }).click();
    const response = await created;
    assert.equal(response.status(), 201);
    sessionId = (await response.json()).data.sessionId;
    assert(sessionId);
    await page.getByRole('button', { name: '重命名会话', exact: true }).click();
    await page.getByLabel('会话标题', { exact: true }).fill(sessionTitle);
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('heading', { name: sessionTitle, exact: true }).waitFor();
    return { sessionId, sessionTitle, mode: 'RAG', knowledgeBaseId };
  });
  let finalAnswer;
  let runId;
  let sourceNumber;
  let selectedSource;
  await checked('真实 SSE 回答在完成前多次渐进更新', async () => {
    await observeStream(sessionId);
    // 仅记录 DOM 长度与时间，不拦截、替换或重放生产网络响应。
    await page.evaluate(() => {
      window.__m7Progress = [];
      window.__m7Started = performance.now();
      window.__m7Observer = new MutationObserver(() => {
        const article = document.querySelector('.chat-message-assistant');
        const length = article?.querySelector('.chat-message-content')?.textContent.length || 0;
        const previous = window.__m7Progress.at(-1);
        if (length && previous?.length !== length) window.__m7Progress.push({
          elapsedMs: Math.round(performance.now() - window.__m7Started), length,
          streaming: article.textContent.includes('生成中'),
        });
      });
      window.__m7Observer.observe(document.querySelector('.chat-transcript'), { childList: true, characterData: true, subtree: true });
    });
    const streamed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/chat/sessions/${sessionId}/messages`
      && response.request().method() === 'POST', { timeout: 120000 });
    await page.getByLabel('问题内容', { exact: true }).fill('根据当前支付回调手册，支付回调入口、响应时限和确认字符串是什么？请引用来源。');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    const response = await streamed;
    assert.equal(response.status(), 200);
    assert((await response.headerValue('content-type')).includes('text/event-stream'));
    const frames = await completedFrames(sessionId);
    const events = frames.map((frame) => frame.event);
    runId = frames.find((frame) => frame.event === 'start')?.data.runId;
    assert(runId);
    assert.equal(events.at(-1), 'done');
    assert(events.filter((event) => event === 'delta').length > 1);
    finalAnswer = await page.locator('.chat-message-assistant .chat-message-content').innerText();
    assert(finalAnswer.includes('/openapi/v2/payment/callback') && /3\s*秒/.test(finalAnswer) && finalAnswer.includes('SUCCESS'));
    const progress = await page.evaluate(() => {
      window.__m7Observer.disconnect();
      return window.__m7Progress;
    });
    const intermediate = progress.filter((sample) => sample.streaming && sample.length < finalAnswer.length);
    assert(intermediate.length > 1, '没有观察到完成前的多次真实 DOM 内容增长');
    const sources = frames.find((frame) => frame.event === 'sources')?.data.sources || [];
    const numbers = citedNumbers(finalAnswer, sources);
    assert(numbers.length > 0 && numbers.every((number) => sources.some((source) => source.number === number)));
    assert.deepEqual(numbers.toSorted((a, b) => a - b), sources.map((source) => source.number).toSorted((a, b) => a - b));
    const buttons = page.locator('.chat-message-assistant .chat-source-button');
    assert.equal(await buttons.count(), numbers.length);
    sourceNumber = numbers[0];
    selectedSource = sources.find((source) => source.number === sourceNumber);
    return { runId, sessionId, sseEventCount: events.length, deltaEvents: events.filter((event) => event === 'delta').length,
      domUpdates: progress.length, intermediateUpdates: intermediate.length, progress,
      citedNumbers: numbers, sourceButtonCount: await buttons.count(), answer: finalAnswer };
  });
  await checked('引用按钮打开对应真实原文且显示版本身份', async () => {
    const sourceResponse = page.waitForResponse((response) => new URL(response.url()).pathname.startsWith('/api/chunks/')
      && new URL(response.url()).pathname.endsWith('/source')
      && response.request().method() === 'GET');
    await page.locator('.chat-source-button').filter({ hasText: `[${sourceNumber}]` }).click();
    const response = await sourceResponse;
    assert.equal(response.status(), 200);
    const detail = (await response.json()).data;
    assert.equal(new URL(response.url()).pathname, `/api/chunks/${selectedSource.chunkId}/source`);
    assert.equal(detail.chunkId, selectedSource.chunkId);
    assert.equal(detail.documentId, selectedSource.documentId);
    assert.equal(detail.versionId, selectedSource.versionId);
    await page.locator('.chat-source-content').waitFor();
    assert.equal(await page.locator('.chat-source-content').innerText(), detail.content);
    assert(detail.content.length > 0);
    assert((await page.locator('.chat-source-metadata').innerText()).includes(`版本 ${detail.versionNo}`));
    await shot('source');
    await page.locator('.chat-source-drawer .el-drawer__close-btn').click();
    return { sourcePath: new URL(response.url()).pathname, citedNumber: sourceNumber,
      chunkId: detail.chunkId, documentId: detail.documentId, versionId: detail.versionId, documentName: detail.documentName,
      versionNo: detail.versionNo, isCurrent: detail.isCurrent, contentCharacters: detail.content.length };
  });
  await checked('刷新后历史会话、回答和引用来源保持一致', async () => {
    await page.reload({ waitUntil: 'networkidle' });
    await menu('会话');
    await page.locator('.chat-session-list-item').filter({ hasText: sessionTitle }).click();
    await page.locator('.chat-message-assistant .chat-message-content').waitFor();
    assert.equal(await page.locator('.chat-message-assistant .chat-message-content').innerText(), finalAnswer);
    await page.locator('.chat-source-button').filter({ hasText: `[${sourceNumber}]` }).click();
    await page.locator('.chat-source-content').waitFor();
    assert((await page.locator('.chat-source-content').innerText()).length > 0);
    await page.locator('.chat-source-drawer .el-drawer__close-btn').click();
    return { sessionId, runId, sameAnswerAfterReload: true, sourceReadableAfterReload: true };
  });
  await checked('智能助手真实组合知识与工单并展示两类引用', async () => {
    await page.getByRole('button', { name: '新建会话', exact: true }).first().click();
    await page.locator('.chat-mode-selector').getByText('智能助手', { exact: true }).click();
    const assistantScope = page.locator('.el-checkbox').filter({ hasText: businessKnowledgeBaseName });
    await assistantScope.click();
    assert(await assistantScope.getByRole('checkbox').isChecked());
    const created = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/chat/sessions'
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: '创建会话', exact: true }).click();
    const sessionResponse = await created;
    assert.equal(sessionResponse.status(), 201);
    const assistantSession = (await sessionResponse.json()).data;
    assert.equal(assistantSession.mode, 'ASSISTANT');
    const assistantSessionId = assistantSession.sessionId;
    await observeStream(assistantSessionId);
    const streamed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/chat/sessions/${assistantSessionId}/messages`
      && response.request().method() === 'POST', { timeout: 180000 });
    await page.getByLabel('问题内容', { exact: true }).fill(
      `请查询工单 ${business.Ids.TicketNo} 的当前状态，再查询F32_E_TIMEOUT手册，综合给出处理建议。请同时引用工单和知识文档。`);
    await page.getByRole('button', { name: '发送', exact: true }).click();
    const response = await streamed;
    assert.equal(response.status(), 200);
    const frames = await completedFrames(assistantSessionId);
    assert.equal(frames.at(-1)?.event, 'done');
    const sources = frames.find((frame) => frame.event === 'sources')?.data.sources || [];
    const tools = frames.filter((frame) => frame.event === 'tool').map((frame) => frame.data);
    assert(tools.some((tool) => tool.name === 'searchKnowledge' && tool.status === 'SUCCEEDED'));
    assert(tools.some((tool) => tool.name === 'getTicketStatus' && tool.status === 'SUCCEEDED'));
    const answer = await page.locator('.chat-message-assistant .chat-message-content').innerText();
    const citations = citedNumbers(answer, sources);
    // 断言前留存实际正文与来源，失败批次也能直接区分漏引和页面解析错误。
    result.assistantObservation = { sessionId: assistantSessionId,
      runId: frames.find((frame) => frame.event === 'start')?.data.runId, answer, sources, citations };
    assert(citations.length > 1 && citations.every((number) => sources.some((source) => source.number === number)));
    assert.deepEqual(citations.toSorted((a, b) => a - b), sources.map((source) => source.number).toSorted((a, b) => a - b));
    const document = sources.find((source) => source.type === 'DOCUMENT' && citations.includes(source.number));
    const ticket = sources.find((source) => source.type === 'TICKET' && citations.includes(source.number));
    assert(document && ticket, '助手本轮必须同时实际引用知识文档和工单');
    assert.equal(ticket.ticketNo, business.Ids.TicketNo);
    assert.equal(ticket.ticketStatus, 'RESOLVED');
    assert.equal(ticket.inherited, false);
    const ticketChip = page.locator('.chat-ticket-source').filter({ hasText: `[${ticket.number}]` });
    assert((await ticketChip.innerText()).includes('本轮查询结果'));
    assert((await ticketChip.innerText()).includes('已解决'));
    assert((await page.locator('.chat-tools').innerText()).includes('查询工单'));
    const sourceResponse = page.waitForResponse((item) => new URL(item.url()).pathname === `/api/chunks/${document.chunkId}/source`);
    await page.locator('.chat-source-button').filter({ hasText: `[${document.number}]` }).click();
    const detailResponse = await sourceResponse;
    assert.equal(detailResponse.status(), 200);
    const detail = (await detailResponse.json()).data;
    assert.equal(detail.chunkId, document.chunkId);
    assert.equal(detail.documentId, document.documentId);
    assert.equal(detail.versionId, document.versionId);
    await page.locator('.chat-source-content').waitFor();
    assert.equal(await page.locator('.chat-source-content').innerText(), detail.content);
    await page.locator('.chat-source-drawer .el-drawer__close-btn').click();
    await page.locator('.chat-source-drawer').waitFor({ state: 'hidden' });
    await shot('assistant');
    return { sessionId: assistantSessionId, runId: frames.find((frame) => frame.event === 'start')?.data.runId,
      tools, citations, sources, answer, documentPreviewMatchesSourceIdentity: true };
  });
  }
  await checked('退出后返回登录页且认证会话失效', async () => {
    await page.getByRole('button', { name: '退出', exact: true }).click();
    await page.getByRole('heading', { name: '登录工作空间' }).waitFor();
    authenticated = false;
    const response = await context.request.get(`${baseUrl}/api/auth/me`);
    assert.equal(response.status(), 401);
    return { loginHeadingVisible: true, meStatus: response.status() };
  });
  await checked('没有未捕获页面异常或非预期失败请求', async () => {
    assert.equal(result.pageErrors.length, 0);
    const expected = (item) => item.status === 401 && item.path === '/api/auth/me'
      || item.status === 404 && item.path === '/api/tickets/M7-NOT-EXIST';
    const unexpected = result.failedHttp.filter((item) => !expected(item));
    assert.equal(unexpected.length, 0, JSON.stringify(unexpected));
    const unexpectedConsole = result.consoleErrors.filter((item) =>
      !(item.path === '/api/auth/me' && item.text.includes('401'))
      && !(item.path === '/api/tickets/M7-NOT-EXIST' && item.text.includes('404')));
    assert.equal(unexpectedConsole.length, 0, JSON.stringify(unexpectedConsole));
    return { pageErrors: 0, unexpectedFailedHttp: 0, unexpectedConsoleErrors: 0,
      expectedNegativeHttp: result.failedHttp.length };
  });
  result.status = 'PASSED';
} catch (error) {
  result.status = 'FAILED';
  result.error = redact(error.message);
  if (page && authenticated) {
    try { await shot('failure'); } catch { /* 浏览器关闭失败时仍保留结构化检查结果。 */ }
  }
} finally {
  if (browser) await browser.close();
  result.completedAt = new Date().toISOString();
  result.durationMs = Date.now() - started;
  result.passedChecks = result.checks.filter((check) => check.status === 'PASSED').length;
  result.failedChecks = result.checks.filter((check) => check.status === 'FAILED').length;
  writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ status: result.status, passedChecks: result.passedChecks,
    failedChecks: result.failedChecks, durationMs: result.durationMs, output: path.relative(root, output) }));
  if (result.status !== 'PASSED') process.exitCode = 1;
}
