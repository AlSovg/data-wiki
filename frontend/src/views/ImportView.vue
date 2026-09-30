<script setup lang="ts">
import { ref } from 'vue'
import { api, type ImportReport } from '../api'

const emit = defineEmits<{ open: [id: string] }>()
const report = ref<ImportReport | null>(null)
const error = ref('')
const busy = ref(false)

async function send(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  busy.value = true
  error.value = ''
  try {
    report.value = await api.upload(Array.from(input.files))
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = false
    input.value = ''
  }
}
</script>

<template>
  <h2>Импорт</h2>
  <div class="row">
    <label>Файлы .md / .zip <input type="file" multiple accept=".md,.markdown,.zip" @change="send" /></label>
    <label>Папка <input type="file" webkitdirectory @change="send" /></label>
  </div>
  <p v-if="busy" class="muted">Загрузка…</p>
  <div v-if="error" class="err">{{ error }}</div>
  <template v-if="report">
    <p>Создано: {{ report.created }} · дубликатов: {{ report.duplicates }} · отклонено: {{ report.rejected }}</p>
    <table>
      <tr v-for="f in report.files" :key="f.path">
        <td>{{ f.status }}</td>
        <td><a v-if="f.documentId" href="#" @click.prevent="emit('open', f.documentId)">{{ f.path }}</a><template v-else>{{ f.path }}</template></td>
        <td class="muted">{{ f.reason }} {{ f.warnings.join('; ') }}</td>
      </tr>
    </table>
  </template>
</template>
