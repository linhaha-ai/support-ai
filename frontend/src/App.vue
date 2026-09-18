<script setup lang="ts">
import { onMounted } from 'vue'
import DashboardView from './DashboardView.vue'
import LoginView from './LoginView.vue'
import { useAuth } from './useAuth'

const { user, loading, submitting, error, initialize, login, logout } = useAuth()

onMounted(initialize)
</script>

<template>
  <div v-if="loading" class="auth-loading" role="status" aria-live="polite" aria-busy="true">
    正在确认身份状态…
  </div>
  <LoginView
    v-else-if="!user"
    :submitting="submitting"
    :error="error"
    @submit="login"
    @retry="initialize"
  />
  <DashboardView
    v-else
    :user="user"
    :logging-out="submitting"
    :auth-error="error"
    @logout="logout"
  />
</template>
