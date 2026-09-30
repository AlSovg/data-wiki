<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { api, type SearchResult } from '../api'

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
const size = 20

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
        method: '', total: list.total, page: list.page, size: list.size, tookMs: 0, cached: false,
        hits: list.items.map((document) => ({ document, score: 0, highlights: [] })),
      }
    }
  } catch (e) {
    error.value = (e as Error).message
  }
}

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
watch(() => props.tag, (t) => { if (t) { tags.value = t; run() } })
</script>

<template>
  <form class="row" @submit.prevent="run(0)">
    <input v-model="q" type="search" placeholder="Поиск по документам" />
    <select v-model="method"><option v-for="m in methods" :key="m" :value="m">{{ m }}</option></select>
    <button class="primary">Найти</button>
  </form>
  <div class="row">
    <input v-if="method === 'hybrid'" v-model="weights" placeholder="веса: bm25:0.7,tfidf:0.3" />
    <input v-model="tags" placeholder="теги (через запятую)" />
    <input v-model="category" placeholder="категория" />
    <input v-model="author" placeholder="автор" />
    <select v-model="sort">
      <option value="relevance">по релевантности</option>
      <option value="updated_at">по дате</option>
      <option value="size_bytes">по размеру</option>
    </select>
  </div>
  <div v-if="error" class="err">{{ error }}</div>
  <template v-if="result">
    <p class="muted">
      Найдено: {{ result.total }}<template v-if="result.method"> · {{ result.method }} · {{ result.tookMs }} мс<template v-if="result.cached"> · из кэша</template></template>
    </p>
    <div v-for="h in result.hits" :key="h.document.id" class="card">
      <h3 @click="emit('open', h.document.id)">{{ h.document.title }}</h3>
      <div class="muted">
        {{ h.document.author }} · {{ new Date(h.document.updatedAt).toLocaleDateString() }} · v{{ h.document.version }}
        <template v-if="h.score"> · {{ h.score.toFixed(3) }}</template>
      </div>
      <div><span v-for="t in h.document.tags" :key="t" class="tag" @click="tags = t; run(0)">{{ t }}</span></div>
      <div v-for="(hl, i) in h.highlights" :key="i" class="muted" v-html="clean(hl.snippet)" />
    </div>
    <div class="row">
      <button :disabled="page === 0" @click="run(page - 1)">←</button>
      <button :disabled="(page + 1) * size >= result.total" @click="run(page + 1)">→</button>
    </div>
  </template>
</template>
