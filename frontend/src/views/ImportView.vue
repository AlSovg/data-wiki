<script setup lang="ts">
import { ref } from 'vue'
import { api, type ImportReport } from '../api'

const emit = defineEmits<{ open: [id: string] }>()
const report = ref<ImportReport | null>(null)
const error = ref('')
const busy = ref(false)
const over = ref(false)
// created → filled, duplicate/skipped → hollow, rejected → cross
const dot: Record<string, string> = { created: 'dot', duplicate: 'dot hollow', skipped: 'dot hollow', rejected: 'dot cross' }
const status: Record<string, string> = { created: 'создан', duplicate: 'дубликат', skipped: 'пропущен', rejected: 'отклонён' }

async function upload(files: File[]) {
  if (!files.length) return
  busy.value = true
  error.value = ''
  try {
    report.value = await api.upload(files)
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = false
  }
}

async function send(e: Event) {
  const input = e.target as HTMLInputElement
  await upload(Array.from(input.files ?? []))
  input.value = ''
}

// dropped folders are not expanded; pick them with «Выбрать папку»
function drop(e: DragEvent) {
  over.value = false
  upload(Array.from(e.dataTransfer?.files ?? []))
}
</script>

<template>
  <main class="page">
    <h1>Импорт</h1>
    <section class="drop" :class="{ over }" @dragover.prevent="over = true" @dragleave="over = false" @drop.prevent="drop">
      <svg class="rings" viewBox="0 0 200 200" aria-hidden="true">
        <circle v-for="r in [96, 70, 44]" :key="r" cx="100" cy="100" :r="r" fill="none" stroke="var(--ring)" />
        <path d="M100 118V78M84 94l16-16 16 16" fill="none" stroke="var(--ink)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <p class="lead">Перетащите файлы сюда</p>
      <p class="muted hint">.md, .markdown или .zip-архив</p>
      <div class="pick">
        <label class="btn primary">Выбрать файлы<input class="sr-only" type="file" multiple accept=".md,.markdown,.zip" :disabled="busy" @change="send" /></label>
        <label class="btn folder">Выбрать папку<input class="sr-only" type="file" webkitdirectory :disabled="busy" @change="send" /></label>
      </div>
    </section>
    <p v-if="busy" class="muted" role="status">Загрузка…</p>
    <div v-if="error" role="alert" class="alert">{{ error }}</div>
    <section v-if="report" class="report" aria-label="Отчёт об импорте">
      <h2 class="label">Отчёт</h2>
      <div class="counts">
        <span><span class="dot" /> <b class="mono">{{ report.created }}</b> создано</span>
        <span><span class="dot hollow" /> <b class="mono">{{ report.duplicates }}</b> дубликатов</span>
        <span><span class="dot cross" /> <b class="mono">{{ report.rejected }}</b> отклонено</span>
      </div>
      <ul>
        <li v-for="f in report.files" :key="f.path">
          <span :class="dot[f.status] ?? 'dot hollow'" :aria-label="status[f.status] ?? f.status" role="img" />
          <span class="file mono">
            <a v-if="f.documentId" href="#" @click.prevent="emit('open', f.documentId)">{{ f.path }}</a><template v-else>{{ f.path }}</template>
          </span>
          <span class="why muted">{{ [f.reason, ...f.warnings].filter(Boolean).join('; ') || status[f.status] || f.status }}</span>
        </li>
      </ul>
    </section>
  </main>
</template>

<style scoped>
.drop { display: flex; flex-direction: column; align-items: center; padding: 36px 24px; border: 1.5px dashed var(--border); border-radius: 8px; background: var(--surface); text-align: center }
.drop.over { border-color: var(--accent); background: var(--err-bg) }
.rings { width: 150px }
.lead { margin: 8px 0 2px; font-size: 18px; font-weight: 500 }
.hint { margin: 0; font-size: 14px }
.pick { display: flex; gap: 12px; margin-top: 20px }
.pick label { cursor: pointer }
.pick label:focus-within { outline: 2px solid var(--accent); outline-offset: 2px }
.counts { display: flex; gap: 28px; padding: 14px 0; font-size: 14px; color: var(--ink-3) }
.counts > span { display: inline-flex; align-items: center; gap: 8px }
.counts b { font-size: 20px; font-weight: 500; color: var(--ink) }
ul { margin: 0; padding: 0; list-style: none }
li { display: grid; grid-template-columns: 16px minmax(0, 1fr) minmax(0, 1fr); gap: 12px; align-items: center; padding: 10px 0; border-top: 1px solid var(--line-2); font-size: 14px }
.file { overflow-wrap: anywhere }

@media (max-width: 720px) {
  .drop { padding: 24px 16px; border-style: solid }
  .rings { width: 110px }
  .lead { display: none }
  .folder { display: none }
  .pick { align-self: stretch }
  .pick .primary { flex: 1; height: 48px }
  .counts { gap: 18px }
  li { grid-template-columns: 16px minmax(0, 1fr); gap: 4px 12px }
  .why { grid-column: 2; font-size: 13px }
}
</style>
