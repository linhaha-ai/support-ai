#!/usr/bin/env node

import { createHash } from 'node:crypto';
import http from 'node:http';

const VECTOR_DIMENSIONS = 1024;
const MAX_BODY_BYTES = 1024 * 1024;
const STREAM_INTERVAL_MS = 350;
const HOST = process.env.MOCK_OLLAMA_HOST || '0.0.0.0';
const PORT = parsePort(process.env.MOCK_OLLAMA_PORT || '11435');
const DEFAULT_CHAT_MODEL = 'qwen2.5:3b';
const DEFAULT_EMBEDDING_MODEL = 'bge-m3';
const FIXED_ANSWER = '根据验收文档，部署、备份与恢复流程可以正常执行 [1]。';

const serverStartedAt = new Date().toISOString();
const state = {
  resetAt: serverStartedAt,
  calls: emptyCounters(),
  lastCalledAt: emptyTimestamps(),
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function parsePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('MOCK_OLLAMA_PORT must be an integer between 1 and 65535');
  }
  return port;
}

function emptyCounters() {
  return {
    tags: 0,
    embed: 0,
    embeddings: 0,
    chat: 0,
    chatStream: 0,
    chatNonStream: 0,
  };
}

function emptyTimestamps() {
  return {
    tags: null,
    embed: null,
    embeddings: null,
    chat: null,
    chatStream: null,
    chatNonStream: null,
  };
}

function recordCall(...names) {
  const timestamp = new Date().toISOString();
  for (const name of names) {
    state.calls[name] += 1;
    state.lastCalledAt[name] = timestamp;
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function sendJson(response, status, payload, extraHeaders = {}) {
  const body = `${JSON.stringify(payload)}\n`;
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    ...extraHeaders,
  });
  response.end(body);
}

function methodNotAllowed(response, allowedMethod) {
  sendJson(response, 405, { error: 'method not allowed' }, { allow: allowedMethod });
}

async function readJsonObject(request) {
  const contentType = request.headers['content-type'] || '';
  if (!/^application\/json(?:\s*;.*)?$/i.test(contentType)) {
    throw new HttpError(415, 'content-type must be application/json');
  }
  const contentEncoding = request.headers['content-encoding'];
  if (contentEncoding && contentEncoding.toLowerCase() !== 'identity') {
    throw new HttpError(415, 'content-encoding is not supported');
  }

  const declaredLength = Number(request.headers['content-length']);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    throw new HttpError(413, 'request body is too large');
  }

  const chunks = [];
  let size = 0;
  let tooLarge = false;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      tooLarge = true;
      continue;
    }
    chunks.push(chunk);
  }
  if (tooLarge) {
    throw new HttpError(413, 'request body is too large');
  }
  if (size === 0) {
    throw new HttpError(400, 'request body must be a JSON object');
  }

  let parsed;
  try {
    parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new HttpError(400, 'request body contains invalid JSON');
  }
  if (!isObject(parsed)) {
    throw new HttpError(400, 'request body must be a JSON object');
  }
  return parsed;
}

function requireModel(body) {
  if (typeof body.model !== 'string' || body.model.trim() === '') {
    throw new HttpError(400, 'model must be a non-empty string');
  }
  return body.model.trim();
}

function requireString(value, field) {
  if (typeof value !== 'string') {
    throw new HttpError(400, `${field} must be a string`);
  }
  return value;
}

// 词法特征占主要权重，微小稠密噪声保证 1024 个维度均为确定性非零值。
function deterministicEmbedding(input) {
  const text = input.normalize('NFKC').toLowerCase();
  const seed = createHash('sha256').update(text).digest();
  const vector = new Array(VECTOR_DIMENSIONS);
  let randomState = seed.readUInt32LE(0) || 0x9e3779b9;
  for (let index = 0; index < VECTOR_DIMENSIONS; index += 1) {
    randomState ^= randomState << 13;
    randomState ^= randomState >>> 17;
    randomState ^= randomState << 5;
    const value = (randomState >>> 0) / 0xffffffff;
    vector[index] = (value * 2 - 1) * 1e-7 || 1e-7;
  }

  const compactCharacters = Array.from(text.replace(/\s+/gu, ''));
  const features = text.match(/[\p{L}\p{N}_-]+/gu) || [];
  features.push(...compactCharacters);
  for (let index = 0; index + 1 < compactCharacters.length; index += 1) {
    features.push(`${compactCharacters[index]}${compactCharacters[index + 1]}`);
  }
  if (features.length === 0) {
    features.push('__empty__');
  }

  for (const feature of features) {
    const hash = fnv1a(feature);
    const vectorIndex = hash % VECTOR_DIMENSIONS;
    const sign = (hash & 0x80000000) === 0 ? 1 : -1;
    vector[vectorIndex] += sign;
  }

  const norm = Math.sqrt(vector.reduce((sum, value) => sum + value * value, 0));
  return vector.map((value) => value / norm);
}

function fnv1a(value) {
  let hash = 0x811c9dc5;
  for (const byte of Buffer.from(value, 'utf8')) {
    hash ^= byte;
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

function tagsPayload() {
  return {
    models: [
      modelTag(DEFAULT_CHAT_MODEL, 'qwen2', '3B'),
      modelTag(DEFAULT_EMBEDDING_MODEL, 'bert', '567M'),
    ],
  };
}

function modelTag(name, family, parameterSize) {
  return {
    name,
    model: name,
    modified_at: serverStartedAt,
    size: 1,
    digest: createHash('sha256').update(name).digest('hex'),
    details: {
      parent_model: '',
      format: 'gguf',
      family,
      families: [family],
      parameter_size: parameterSize,
      quantization_level: 'Q4_K_M',
    },
  };
}

function validateChatRequest(body) {
  const model = requireModel(body);
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    throw new HttpError(400, 'messages must be a non-empty array');
  }
  const messages = body.messages.map((message, index) => {
    if (!isObject(message)) {
      throw new HttpError(400, `messages[${index}] must be an object`);
    }
    const role = requireString(message.role, `messages[${index}].role`);
    const content = requireString(message.content, `messages[${index}].content`);
    return { role, content };
  });
  if (body.stream !== undefined && typeof body.stream !== 'boolean') {
    throw new HttpError(400, 'stream must be a boolean');
  }
  return { model, messages, stream: body.stream === true };
}

function chatContent(body, messages) {
  const combined = messages.map((message) => message.content).join('\n');
  const queryRewrite = combined.includes('检索问题改写器')
    || combined.includes('只能包含 query 字段')
    || body.format === 'json';
  if (!queryRewrite) {
    return FIXED_ANSWER;
  }

  const latestUserMessage = [...messages].reverse().find((message) => message.role === 'user');
  const rawPrompt = latestUserMessage?.content || '';
  const match = rawPrompt.match(/用户原问题：\s*([\s\S]*?)(?:\n\s*\n已授权历史问答|$)/u);
  const query = (match?.[1] || rawPrompt).trim() || '验收问题';
  return JSON.stringify({ query });
}

function usageMetrics(messages, content) {
  const promptCharacters = messages.reduce((sum, message) => sum + Array.from(message.content).length, 0);
  return {
    total_duration: 1_200_000_000,
    load_duration: 2_000_000,
    prompt_eval_count: Math.max(1, Math.ceil(promptCharacters / 4)),
    prompt_eval_duration: 300_000_000,
    eval_count: Math.max(1, Math.ceil(Array.from(content).length / 3)),
    eval_duration: 800_000_000,
  };
}

function chatResponse(model, messages, content) {
  return {
    model,
    created_at: new Date().toISOString(),
    message: { role: 'assistant', content },
    done: true,
    done_reason: 'stop',
    ...usageMetrics(messages, content),
  };
}

function splitIntoThree(content) {
  const characters = Array.from(content);
  const firstCut = Math.ceil(characters.length / 3);
  const secondCut = Math.ceil((characters.length * 2) / 3);
  return [
    characters.slice(0, firstCut).join(''),
    characters.slice(firstCut, secondCut).join(''),
    characters.slice(secondCut).join(''),
  ];
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function streamChat(response, model, messages, content) {
  response.writeHead(200, {
    'content-type': 'application/x-ndjson; charset=utf-8',
    'cache-control': 'no-store',
    'x-accel-buffering': 'no',
  });

  const deltas = splitIntoThree(content);
  for (let index = 0; index < deltas.length; index += 1) {
    if (response.destroyed) {
      return;
    }
    response.write(`${JSON.stringify({
      model,
      created_at: new Date().toISOString(),
      message: { role: 'assistant', content: deltas[index] },
      done: false,
    })}\n`);
    if (index < deltas.length - 1) {
      await delay(STREAM_INTERVAL_MS);
    }
  }

  if (!response.destroyed) {
    await delay(STREAM_INTERVAL_MS);
    response.end(`${JSON.stringify({
      model,
      created_at: new Date().toISOString(),
      message: { role: 'assistant', content: '' },
      done: true,
      done_reason: 'stop',
      ...usageMetrics(messages, content),
    })}\n`);
  }
}

async function handleEmbed(request, response) {
  const body = await readJsonObject(request);
  const model = requireModel(body);
  const inputs = typeof body.input === 'string' ? [body.input] : body.input;
  if (!Array.isArray(inputs) || inputs.length === 0 || inputs.some((input) => typeof input !== 'string')) {
    throw new HttpError(400, 'input must be a string or a non-empty string array');
  }
  recordCall('embed');
  sendJson(response, 200, {
    model,
    embeddings: inputs.map(deterministicEmbedding),
    total_duration: 10_000_000,
    load_duration: 1_000_000,
    prompt_eval_count: inputs.reduce((sum, input) => sum + Math.max(1, Math.ceil(input.length / 4)), 0),
  });
}

async function handleLegacyEmbeddings(request, response) {
  const body = await readJsonObject(request);
  requireModel(body);
  const prompt = requireString(body.prompt, 'prompt');
  recordCall('embeddings');
  sendJson(response, 200, { embedding: deterministicEmbedding(prompt) });
}

async function handleChat(request, response) {
  const body = await readJsonObject(request);
  const { model, messages, stream } = validateChatRequest(body);
  const content = chatContent(body, messages);
  recordCall('chat', stream ? 'chatStream' : 'chatNonStream');
  if (stream) {
    await streamChat(response, model, messages, content);
    return;
  }
  sendJson(response, 200, chatResponse(model, messages, content));
}

async function handleReset(request, response) {
  const body = await readJsonObject(request);
  if (Object.keys(body).length !== 0) {
    throw new HttpError(400, 'reset body must be an empty JSON object');
  }
  state.calls = emptyCounters();
  state.lastCalledAt = emptyTimestamps();
  state.resetAt = new Date().toISOString();
  sendJson(response, 200, { reset: true, resetAt: state.resetAt });
}

async function handleRequest(request, response) {
  const url = new URL(request.url || '/', 'http://mock-ollama.local');
  if (url.search !== '') {
    throw new HttpError(400, 'query parameters are not supported');
  }

  switch (url.pathname) {
    case '/api/tags':
      if (request.method !== 'GET') {
        methodNotAllowed(response, 'GET');
        return;
      }
      recordCall('tags');
      sendJson(response, 200, tagsPayload());
      return;
    case '/api/embed':
      if (request.method !== 'POST') {
        methodNotAllowed(response, 'POST');
        return;
      }
      await handleEmbed(request, response);
      return;
    case '/api/embeddings':
      if (request.method !== 'POST') {
        methodNotAllowed(response, 'POST');
        return;
      }
      await handleLegacyEmbeddings(request, response);
      return;
    case '/api/chat':
      if (request.method !== 'POST') {
        methodNotAllowed(response, 'POST');
        return;
      }
      await handleChat(request, response);
      return;
    case '/__mock/state':
      if (request.method !== 'GET') {
        methodNotAllowed(response, 'GET');
        return;
      }
      sendJson(response, 200, {
        serverStartedAt,
        currentTime: new Date().toISOString(),
        resetAt: state.resetAt,
        calls: { ...state.calls },
        lastCalledAt: { ...state.lastCalledAt },
      });
      return;
    case '/__mock/reset':
      if (request.method !== 'POST') {
        methodNotAllowed(response, 'POST');
        return;
      }
      await handleReset(request, response);
      return;
    default:
      sendJson(response, 404, { error: 'not found' });
  }
}

const server = http.createServer((request, response) => {
  handleRequest(request, response).catch((error) => {
    if (response.headersSent) {
      response.destroy();
      return;
    }
    const status = error instanceof HttpError ? error.status : 500;
    const message = error instanceof HttpError ? error.message : 'internal server error';
    sendJson(response, status, { error: message });
  });
});

server.requestTimeout = 10_000;
server.headersTimeout = 10_000;
server.listen(PORT, HOST, () => {
  console.log(`Mock Ollama listening on http://${HOST}:${PORT}`);
});

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  console.log(`Mock Ollama received ${signal}, shutting down`);
  server.close((error) => {
    process.exitCode = error ? 1 : 0;
  });
  setTimeout(() => server.closeAllConnections(), 5_000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
