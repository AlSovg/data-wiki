<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { api, type Doc, type Version } from '../api'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: []; tag: [tag: string] }>()

const doc = ref<Doc | null>(null)
const text = ref('')
const versions = ref<Version[]>([])
const error = ref('')
const saved = ref(false)

const preview = computed(() => DOMPurify.sanitize(marked.parse(text.value, { async: false }) as string))

async function load() {
  error.value = ''
  try {
    doc.value = await api.document(props.id)
    text.value = doc.value.content
    versions.value = await api.versions(props.id)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function save() {
  error.value = ''
  try {
    await api.save(props.id, text.value)
    saved.value = true
    setTimeout(() => (saved.value = false), 2000)
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function remove() {
  if (!confirm('Удалить документ?')) return
  try {
    await api.remove(props.id)
    emit('close')
  } catch (e) {
    error.value = (e as Error).message
  }
}

// Loads an old version into the editor; saving it creates a new version.
async function showVersion(v: number) {
  text.value = (await api.version(props.id, v)).content
}

watch(() => props.id, load, { immediate: true })
</script>

<template>
  <div class="row">
    <button @click="emit('close')">← К поиску</button>
    <button class="primary" @click="save">Сохранить</button>
    <button class="danger" @click="remove">Удалить</button>
    <span v-if="saved" class="muted">сохранено</span>
  </div>
  <div v-if="error" class="err">{{ error }}</div>
  <template v-if="doc">
    <h2>{{ doc.title }}</h2>
    <div class="muted">{{ doc.author }} · {{ doc.wordCount }} слов · версия {{ doc.version }}</div>
    <div><span v-for="t in doc.tags" :key="t" class="tag" @click="emit('tag', t)">{{ t }}</span></div>
    <div class="row muted" style="margin-top: 8px">
      Версии:
      <button v-for="v in versions" :key="v.version" @click="showVersion(v.version)">v{{ v.version }}</button>
    </div>
    <div class="split">
      <textarea v-model="text" spellcheck="false" />
      <div class="preview" v-html="preview" />
    </div>
  </template>
</template>
