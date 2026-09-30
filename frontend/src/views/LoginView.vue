<script setup lang="ts">
import { ref } from 'vue'
import { api, token } from '../api'

const emit = defineEmits<{ done: [] }>()
const email = ref('')
const password = ref('')
const error = ref('')

async function submit(mode: 'login' | 'register') {
  error.value = ''
  try {
    if (mode === 'register') await api.auth('register', email.value, password.value)
    token.set((await api.auth('login', email.value, password.value)).accessToken)
    emit('done')
  } catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <main style="max-width: 360px; margin-top: 15vh">
    <h2>Data Wiki</h2>
    <form class="row" style="flex-direction: column" @submit.prevent="submit('login')">
      <input v-model="email" type="email" placeholder="email" required autocomplete="username" />
      <input v-model="password" type="password" placeholder="пароль (8–72 символа)" minlength="8" required autocomplete="current-password" />
      <button class="primary">Войти</button>
      <button type="button" @click="submit('register')">Зарегистрироваться</button>
      <div v-if="error" class="err">{{ error }}</div>
    </form>
  </main>
</template>
