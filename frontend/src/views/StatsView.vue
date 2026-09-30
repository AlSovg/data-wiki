<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type Bucket, type Stats } from '../api'

const emit = defineEmits<{ tag: [tag: string] }>()
const stats = ref<Stats | null>(null)
const error = ref('')
const pct = (b: Bucket, all: Bucket[]) => `${(b.count / Math.max(...all.map((x) => x.count), 1)) * 100}%`

onMounted(async () => {
  try {
    stats.value = await api.stats()
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>

<template>
  <main class="page">
    <h1>Статистика</h1>
    <div v-if="error" role="alert" class="alert">{{ error }}</div>
    <template v-if="stats">
      <section class="panel totals">
        <div><b class="mono">{{ stats.documents }}</b><span class="muted">документов</span></div>
        <div><b class="mono">{{ stats.totalWords.toLocaleString('ru-RU') }}</b><span class="muted">слов</span></div>
        <div><b class="mono">{{ (stats.totalSizeBytes / 1024).toFixed(1) }}</b><span class="muted">КБ</span></div>
      </section>
      <div class="grid">
        <section>
          <h2 class="label">Теги</h2>
          <ul>
            <li v-for="b in stats.byTag" :key="b.key">
              <button class="key" @click="emit('tag', b.key)">{{ b.key }}</button>
              <span class="bar"><span :style="{ width: pct(b, stats.byTag) }" /></span>
              <span class="mono n">{{ b.count }}</span>
            </li>
          </ul>
        </section>
        <section v-for="[title, list] in [['Категории', stats.byCategory], ['Авторы', stats.byAuthor]] as const" :key="title">
          <h2 class="label">{{ title }}</h2>
          <ul>
            <li v-for="b in list" :key="b.key">
              <span class="key">{{ b.key }}</span>
              <span class="bar"><span :style="{ width: pct(b, list) }" /></span>
              <span class="mono n">{{ b.count }}</span>
            </li>
          </ul>
        </section>
      </div>
    </template>
  </main>
</template>

<style scoped>
.totals { display: grid; grid-template-columns: repeat(3, 1fr) }
.totals div { display: flex; flex-direction: column; gap: 2px; padding: 20px 24px; border-left: 1px solid var(--line) }
.totals div:first-child { border-left: 0 }
.totals b { font-size: 34px; font-weight: 500; line-height: 1.1 }
.totals span { font-size: 14px }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 40px }
ul { margin: 12px 0 0; padding: 0; list-style: none }
li { display: grid; grid-template-columns: minmax(0, 140px) minmax(0, 1fr) 36px; gap: 14px; align-items: center; min-height: 36px; font-size: 14px }
.key { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: left }
button.key { min-height: 36px; padding: 0; border: 0; background: none; text-decoration: underline; text-decoration-color: var(--border); text-underline-offset: 3px }
.n { text-align: right; color: var(--ink-3) }

@media (max-width: 720px) {
  .totals div { padding: 14px 12px }
  .totals b { font-size: 24px }
  .totals span { font-size: 12px }
  .grid { gap: 24px }
  li { grid-template-columns: minmax(0, 110px) minmax(0, 1fr) 32px; gap: 10px; min-height: 44px }
  button.key { min-height: 44px }
}
</style>
