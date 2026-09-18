<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'

defineProps<{
  submitting: boolean
  error: string
}>()

const emit = defineEmits<{
  submit: [username: string, password: string]
  retry: []
}>()

const username = ref('')
const password = ref('')
const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0)

function submit() {
  emit('submit', username.value.trim(), password.value)
}
</script>

<template>
  <main class="login-page" aria-labelledby="login-heading">
    <section class="login-panel">
      <a class="brand login-brand" href="/" aria-label="Support AI">
        <span class="brand-mark" aria-hidden="true">s<span>✳</span></span>
        <span>Support<span class="brand-ai">AI</span></span>
      </a>
      <span class="eyebrow">SECURE WORKSPACE</span>
      <h1 id="login-heading">登录工作空间</h1>
      <p class="login-copy">使用已分配的账号进入技术支持工作台。</p>

      <form class="login-form" @submit.prevent="submit">
        <label for="username">用户名</label>
        <ElInput id="username" v-model="username" name="username" autocomplete="username" :disabled="submitting" />
        <label for="password">密码</label>
        <ElInput id="password" v-model="password" name="password" type="password" show-password autocomplete="current-password" :disabled="submitting" />
        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
        <ElButton native-type="submit" type="primary" class="login-button" :loading="submitting" :disabled="!canSubmit">
          登录
        </ElButton>
      </form>

      <button v-if="error" type="button" class="retry-link" :disabled="submitting" @click="emit('retry')">重新连接认证服务</button>
    </section>
  </main>
</template>
