import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { runtimeIdentity } from '../../evaluation/scripts/f32-runner.mjs';

const output = process.argv[2];
assert(output && !existsSync(output));
const app = JSON.parse(execFileSync('docker', ['inspect', 'support-ai-f31-source4-app-1'], { encoding: 'utf8' }))[0];
const topK = app.Config.Env.find(entry => entry.startsWith('SUPPORT_AI_RAG_TOP_K='))?.split('=')[1] ?? null;
assert(topK === null || /^[0-9]+$/.test(topK));
// 环境变量仅参与哈希，不在产物或输出中保留其原值。
const withoutTopK = { ...app, Config: { ...app.Config, Env: app.Config.Env.filter(entry => !entry.startsWith('SUPPORT_AI_RAG_TOP_K=')) } };
const identity = { recordedAt: new Date().toISOString(), ...runtimeIdentity(app), topKEnvironment: topK,
  runtimeWithoutTopKHash: runtimeIdentity(withoutTopK).runtimeHash,
  scriptSha256: createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex') };
writeFileSync(output, JSON.stringify(identity, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, imageId: identity.imageId, topK }));
