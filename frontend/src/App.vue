<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import { useHealthCheck } from './useHealthCheck'

const { state, detail, lastChecked, check } = useHealthCheck()
const statusTitle = computed(() => ({
  loading: '正在连接后端',
  up: '后端连接成功',
  down: '后端尚未就绪',
  unreachable: '暂时无法连接',
})[state.value])
const statusLabel = computed(() => ({
  loading: '检查中',
  up: '运行正常',
  down: '需要检查',
  unreachable: '连接中断',
})[state.value])
const checkedTime = computed(() => lastChecked.value?.toLocaleTimeString('zh-CN', {
  hour12: false,
}) ?? '等待首次检查')

onMounted(check)
</script>

<template>
  <div class="workspace">
    <aside class="sidebar" aria-label="工作空间">
      <a class="brand" href="/" aria-label="Support AI 运行概览">
        <span class="brand-mark" aria-hidden="true">s<span>✳</span></span>
        <span>Support<span class="brand-ai">AI</span></span>
      </a>
      <div class="workspace-label">WORKSPACE <span>01</span></div>
      <div class="navigation-item" aria-current="page">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="14.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="3.5" y="14.5" width="6" height="6" rx="1.5"/><rect x="14.5" y="14.5" width="6" height="6" rx="1.5"/></svg>
        运行概览
        <span class="navigation-dot"></span>
      </div>
      <div class="sidebar-note">
        <span class="small-label">BUILDING THE FOUNDATION</span>
        <p>从一个可靠的连接开始。</p>
        <span>为知识与服务，准备好基础。</span>
        <div class="phase-progress"><i></i><i></i><i></i><i></i><i></i></div>
        <span class="phase-caption">当前阶段 · F01</span>
      </div>
      <div class="sidebar-bottom"><span class="local-dot"></span>本地开发工作空间<span>v0.1</span></div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <span><span class="breadcrumb-muted">工作空间</span><span class="breadcrumb-slash">/</span>运行概览</span>
        <span class="environment-label"><span></span>开发环境</span>
      </header>

      <main>
        <div class="page-heading">
          <div>
            <span class="eyebrow">SYSTEM OVERVIEW</span>
            <h1>准备好，开始连接。</h1>
            <p>查看应用运行状态，让下一步开发从这里开始。</p>
          </div>
          <span class="phase-badge">F01 <span>工程基础</span></span>
        </div>

        <section class="status-card" :data-state="state" aria-labelledby="connection-heading">
          <div class="card-header"><h2 id="connection-heading">服务连接</h2><span class="status-pill"><i></i>{{ statusLabel }}</span></div>
          <div class="connection-visual" aria-hidden="true">
            <div class="connection-node frontend-node"><svg viewBox="0 0 32 32" fill="none"><rect x="4" y="6" width="24" height="18" rx="3"/><path d="M4 12h24M12 28h8M16 24v4"/><circle cx="8" cy="9" r=".7" fill="currentColor"/><circle cx="11" cy="9" r=".7" fill="currentColor"/></svg><span>应用页面</span></div>
            <div class="connection-line"><span>{{ state === 'loading' ? '···' : state === 'up' ? '✓' : '!' }}</span></div>
            <div class="connection-node backend-node"><svg viewBox="0 0 32 32" fill="none"><rect x="6" y="4" width="20" height="10" rx="3"/><rect x="6" y="18" width="20" height="10" rx="3"/><path d="M11 9h.01M11 23h.01M17 9h5M17 23h5"/></svg><span>后端服务</span></div>
          </div>
          <div class="connection-copy" role="status" aria-live="polite" :aria-busy="state === 'loading'">
            <h3>{{ statusTitle }}</h3>
            <p>{{ detail }}</p>
          </div>
          <ElButton class="check-button" type="primary" :loading="state === 'loading'" @click="check">
            <svg v-if="state !== 'loading'" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M15.6 6.5A6 6 0 1 0 16 12M16 3v4h-4"/></svg>
            {{ state === 'loading' ? '正在检查' : state === 'up' ? '重新检查' : '重试连接' }}
          </ElButton>
          <div class="last-checked">最近检查<span>{{ checkedTime }}</span></div>
        </section>

        <div class="support-grid">
          <section class="overview-card" aria-labelledby="checklist-heading">
            <div class="section-heading"><h2 id="checklist-heading">启动检查</h2><span>基础就绪情况</span></div>
            <div class="check-row"><span class="check-icon ready">✓</span><div><h3>前端页面</h3><p>工作空间已加载</p></div><span class="check-result ready-text">已就绪</span></div>
            <div class="check-row"><span class="check-icon" :class="state === 'up' ? 'ready' : 'pending'">{{ state === 'up' ? '✓' : '·' }}</span><div><h3>后端服务</h3><p>基础服务健康检查</p></div><span class="check-result">{{ statusLabel }}</span></div>
            <div class="check-row"><span class="check-icon pending">·</span><div><h3>模型能力</h3><p>通过独立验收确认</p></div><span class="check-result">待验收</span></div>
          </section>

          <section class="model-card" aria-labelledby="model-heading">
            <span class="small-label">MODEL CONNECTIVITY</span>
            <h2 id="model-heading">本地与云端，按需连接。</h2>
            <p>工程支持接入本地 Ollama 与云端 DashScope，聊天和向量模型可分别配置。</p>
            <div class="model-options"><span><i class="model-local"></i>Ollama <small>本地</small></span><span><i class="model-cloud"></i>DashScope <small>云端</small></span></div>
            <div class="model-note"><span aria-hidden="true">ⓘ</span>服务健康状态不代表模型已完成验收。</div>
          </section>
        </div>

        <footer><span>SUPPORT AI <span class="footer-dot">·</span> 知识驱动，让服务更进一步。</span><span>工程基础 / F01</span></footer>
      </main>
    </div>
  </div>
</template>
