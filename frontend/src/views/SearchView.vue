<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { api, type SearchResult } from '../api'
import RelevanceMap from '../components/RelevanceMap.vue'

const props = defineProps<{ tag: string | null }>()
const emit = defineEmits<{ open: [id: string] }>()

const q = ref('')
const method = ref('')
const methods = ref<string[]>([])
const weights = ref('')
const tags = ref('')
const category = ref('')
const author = ref('')
const sort = ref('relevance')
const page = ref(0)
const result = ref<SearchResult | null>(null)
const error = ref('')
const filters = ref(false)
const sel = ref<string | null>(null)
const size = 20
const names: Record<string, string> = { bm25: 'BM25', tfidf: 'TF-IDF', hybrid: 'Гибрид' }

// The server escapes everything except <mark>; sanitize anyway.
const clean = (html: string) => DOMPurify.sanitize(html, { ALLOWED_TAGS: ['mark'] })

async function run(p = 0) {
  error.value = ''
  page.value = p
  try {
    if (q.value.trim()) {
      result.value = await api.search({
        q: q.value, method: method.value, weights: weights.value, tags: tags.value,
        category: category.value, author: author.value, sort: sort.value, page: p, size,
      })
    } else {
      // no query: plain document list
      const list = await api.documents({ tags: tags.value, category: category.value, author: author.value, page: p, size })
      result.value = {
        method: '', total: list.total, page: list.page, size: list.size, tookMs: 0, cached: false, misses: [],
        hits: list.items.map((document) => ({ document, score: 0, highlights: [] })),
      }
    }
    sel.value = result.value.hits[0]?.document.id ?? null
  } catch (e) {
    error.value = (e as Error).message
  }
}

function pick(m: string) { method.value = m; if (q.value.trim()) run(0) }
function byTag(t: string) { tags.value = t; filters.value = true; run(0) }

onMounted(async () => {
  try {
    const [m, s] = await Promise.all([api.methods(), api.settings()])
    methods.value = m
    method.value = s.method
  } catch (e) {
    error.value = (e as Error).message
  }
  run()
})
watch(() => props.tag, (t) => { if (t) { tags.value = t; filters.value = true; run() } })
</script>

<template>
  <main class="page">
    <form class="query" role="search" @submit.prevent="run(0)">
      <label class="box">
        <span class="sr-only">Поисковый запрос</span>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <input v-model="q" type="search" placeholder="Поиск по документам" enterkeyhint="search" />
      </label>
      <div class="methods" role="radiogroup" aria-label="Метод ранжирования">
        <button v-for="m in methods" :key="m" type="button" role="radio" :aria-checked="m === method" @click="pick(m)">{{ names[m] ?? m }}</button>
      </div>
      <button class="btn primary find">Найти</button>
    </form>

    <div class="bar-line">
      <button type="button" class="btn dashed" :aria-expanded="filters" @click="filters = !filters">Фильтры{{ tags || category || author ? ' •' : '' }}</button>
      <p v-if="result" class="meta muted">
        <template v-if="result.method">Запрос: «{{ q }}» · </template>найдено {{ result.total }}<template v-if="result.method"> · {{ names[result.method] ?? result.method }} · <span class="mono">{{ result.tookMs }} мс</span><template v-if="result.cached"> · из кэша</template></template>
      </p>
    </div>
    <div v-if="filters" class="filters">
      <input v-if="method === 'hybrid'" v-model="weights" class="field mono" placeholder="bm25:0.7,tfidf:0.3" aria-label="Веса гибрида" />
      <input v-model="tags" class="field" placeholder="Теги через запятую" aria-label="Теги" />
      <input v-model="category" class="field" placeholder="Категория" aria-label="Категория" />
      <input v-model="author" class="field" placeholder="Автор" aria-label="Автор" />
      <select v-model="sort" class="field" aria-label="Сортировка">
        <option value="relevance">По релевантности</option>
        <option value="updated_at">По дате</option>
        <option value="size_bytes">По размеру</option>
      </select>
      <button class="btn" @click="run(0)">Применить</button>
    </div>
    <div v-if="error" role="alert" class="alert">{{ error }}</div>

    <div v-if="result" class="body" :class="{ list: !result.method }">
      <RelevanceMap v-if="result.method && result.hits.length" :hits="result.hits" :misses="result.misses" :sel="sel"
                     @pick="sel = $event" @open="emit('open', $event)" />
      <section class="results" aria-label="Результаты">
        <h2 class="label">{{ result.method ? 'Результаты' : 'Документы' }}</h2>
        <p v-if="!result.hits.length" class="muted">Ничего не найдено.</p>
        <ol>
          <li v-for="(h, i) in result.hits" :key="h.document.id" :class="{ on: h.document.id === sel && result.method }">
            <button v-if="result.method" class="num mono" :aria-pressed="h.document.id === sel" :aria-label="`Показать на карте: ${h.document.title}`"
                    @click="sel = h.document.id">{{ String(page * size + i + 1).padStart(2, '0') }}</button>
            <div class="hit">
              <div class="head">
                <a href="#" class="title" @click.prevent="emit('open', h.document.id)">{{ h.document.title }}</a>
                <span v-if="h.score" class="score mono">{{ h.score.toFixed(3) }}</span>
              </div>
              <p v-for="(hl, j) in h.highlights" :key="j" class="snip" v-html="clean(hl.snippet)" />
              <p class="info muted">
                {{ h.document.author }} · {{ new Date(h.document.updatedAt).toLocaleDateString('ru-RU') }} · v{{ h.document.version }}
                <button v-for="t in h.document.tags" :key="t" class="chip" @click="byTag(t)">{{ t }}</button>
              </p>
            </div>
          </li>
        </ol>
        <nav v-if="result.total > size" class="pager" aria-label="Страницы">
          <button class="btn" :disabled="page === 0" aria-label="Назад" @click="run(page - 1)">←</button>
          <span class="mono muted">{{ page + 1 }} / {{ Math.ceil(result.total / size) }}</span>
          <button class="btn" :disabled="(page + 1) * size >= result.total" aria-label="Вперёд" @click="run(page + 1)">→</button>
        </nav>
      </section>
    </div>
  </main>
</template>

<style scoped>
.query { display: flex; gap: 12px; align-items: center; flex-wrap: wrap }
.box { flex: 1 1 420px; display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 16px; border: 1px solid var(--ink); border-radius: 8px; background: var(--surface); color: var(--muted) }
.box input { flex: 1; min-width: 0; height: 100%; border: 0; outline: none; background: none; font-size: 18px; color: var(--ink) }
.methods { display: flex; border: 1px solid var(--border); border-radius: 6px; overflow: hidden }
.methods button { height: 40px; padding: 0 14px; border: 0; border-left: 1px solid var(--border); background: none; font-size: 14px; color: var(--ink-3) }
.methods button:first-child { border-left: 0 }
.methods button[aria-checked='true'] { background: var(--ink); color: var(--bg); font-weight: 500 }
.find { height: 52px; padding: 0 22px }
.bar-line { display: flex; align-items: center; gap: 16px; margin-top: -8px }
.meta { margin: 0; font-size: 14px }
.filters { display: flex; flex-wrap: wrap; gap: 10px }
.filters .field { flex: 1 1 160px; height: 40px }
.body { display: grid; grid-template-columns: minmax(0, 560px) minmax(0, 1fr); gap: 48px; align-items: start }
.body.list { grid-template-columns: minmax(0, 1fr) }
.results ol { margin: 0; padding: 0; list-style: none }
.results li { display: flex; gap: 14px; padding: 16px 0; border-bottom: 1px solid var(--line-2) }
.num { flex-shrink: 0; width: 32px; height: 32px; padding: 0; border: 1px solid var(--border); border-radius: 50%; background: none; font-size: 12px; color: var(--muted) }
li.on .num { border-color: var(--accent); background: var(--accent); color: #fff }
.hit { flex: 1; min-width: 0 }
.head { display: flex; justify-content: space-between; gap: 12px; align-items: baseline }
.title { font-size: 17px; font-weight: 500; text-decoration: none }
.title:hover { text-decoration: underline }
li.on .title { color: var(--accent) }
.score { font-size: 13px; color: var(--ink-3) }
.snip { margin: 6px 0 0; font-size: 14px; color: var(--ink-2) }
.info { margin: 8px 0 0; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px }
.info .chip { margin-left: 2px }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding-top: 20px }

@media (max-width: 720px) {
  .box { flex-basis: 100%; height: 48px }
  .box input { font-size: 16px }
  .methods { flex: 1 }
  .methods button { flex: 1; height: 44px; padding: 0 8px }
  .find { display: none }
  .bar-line { flex-wrap: wrap; gap: 8px; margin-top: 0 }
  .filters .field { flex-basis: 100% }
  .body { grid-template-columns: minmax(0, 1fr); gap: 16px }
  .results li { gap: 10px }
  .title { font-size: 16px }
}
</style>
