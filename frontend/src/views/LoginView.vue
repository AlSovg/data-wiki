<script setup lang="ts">
import { ref } from 'vue'
import { api, token } from '../api'

const emit = defineEmits<{ done: [] }>()
const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit(mode: 'login' | 'register') {
  error.value = ''
  busy.value = true
  try {
    if (mode === 'register') await api.auth('register', email.value, password.value)
    token.set((await api.auth('login', email.value, password.value)).accessToken, email.value.trim().toLowerCase())
    emit('done')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <aside class="intro">
      <strong class="brand">Data Wiki</strong>
      <svg class="map" viewBox="0 0 360 360" aria-hidden="true">
        <circle v-for="r in [170, 125, 80, 38]" :key="r" cx="180" cy="180" :r="r" fill="none" stroke="var(--line)" />
        <rect x="175" y="175" width="10" height="10" transform="rotate(45 180 180)" fill="none" stroke="var(--ink)" stroke-width="1.5" />
        <line x1="180" y1="180" x2="204" y2="160" stroke="var(--accent)" />
        <circle cx="204" cy="160" r="7" fill="var(--accent)" />
        <circle cx="112" cy="140" r="5" fill="var(--ink)" />
        <circle cx="258" cy="92" r="5" fill="var(--ink)" />
        <circle cx="40" cy="220" r="5" fill="none" stroke="var(--hollow)" stroke-width="1.5" />
        <circle cx="290" cy="290" r="5" fill="none" stroke="var(--hollow)" stroke-width="1.5" />
      </svg>
      <div class="pitch">
        <p class="lead">Заметки в Markdown и поиск, который их находит.</p>
        <p class="muted">Импорт, версии, BM25 / TF-IDF / гибридное ранжирование с подсветкой совпадений.</p>
        <p class="mono stack">Lucene · PostgreSQL · Redis</p>
      </div>
    </aside>
    <form class="form" @submit.prevent="submit('login')">
      <h1>Вход</h1>
      <label>Email
        <input v-model="email" class="field" type="email" inputmode="email" placeholder="you@example.com" required autocomplete="username" />
      </label>
      <label>Пароль
        <input v-model="password" class="field" :class="{ bad: error }" type="password" minlength="8" maxlength="72" required
               autocomplete="current-password" :aria-invalid="!!error" aria-describedby="pw-hint" />
        <span id="pw-hint" class="hint">8–72 символа</span>
      </label>
      <div v-if="error" role="alert" class="alert">{{ error }}</div>
      <button class="btn primary" :disabled="busy">Войти</button>
      <button type="button" class="btn" :disabled="busy" @click="submit('register')">Создать аккаунт</button>
    </form>
  </div>
</template>

<style scoped>
.login { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) }
.intro { position: relative; padding: 40px 56px; border-right: 1px solid var(--line); display: flex; flex-direction: column; overflow: hidden }
.brand { font-size: 18px; font-weight: 600 }
.map { width: min(420px, 90%); margin: auto }
.pitch p { margin: 0 0 6px; max-width: 420px }
.lead { font-size: 20px; font-weight: 500; line-height: 1.35 }
.stack { margin-top: 14px !important; font-size: 12px; color: var(--faint) }
.form { width: 100%; max-width: 380px; margin: auto; padding: 32px 16px; display: flex; flex-direction: column; gap: 14px }
.form h1 { font-size: 26px; margin-bottom: 4px }
label { display: flex; flex-direction: column; gap: 6px; font-size: 14px; color: var(--ink-3) }
.field { color: var(--ink) }
.field.bad { border-color: var(--accent) }
.hint { font-size: 12px; color: var(--muted) }
.form .btn { height: 44px }

@media (max-width: 720px) {
  .login { grid-template-columns: 1fr; grid-template-rows: auto 1fr }
  .intro { height: 200px; padding: 16px; border-right: 0; border-bottom: 1px solid var(--line) }
  .brand { font-size: 17px }
  .map { position: absolute; right: -70px; top: -80px; width: 360px }
  .pitch { margin-top: auto; max-width: 200px }
  .pitch p:not(.lead) { display: none }
  .lead { font-size: 14px; font-weight: 400; color: var(--ink-3) }
  .form { margin: 0; max-width: none; padding: 28px 16px 24px }
  .form .btn { height: 48px }
}
</style>
