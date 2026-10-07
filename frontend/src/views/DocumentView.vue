<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { api, type Doc, type Permission, type ShareLink, type Version } from '../api'

// opened either by the owner (id) or by a share link (shareToken)
const props = defineProps<{ id?: string; shareToken?: string }>()
const emit = defineEmits<{ close: []; tag: [tag: string] }>()

const doc = ref<Doc | null>(null)
const text = ref('')
const versions = ref<Version[]>([])
const error = ref('')
const saved = ref(false)
const shown = ref<number | null>(null)
const pane = ref<'preview' | 'versions' | 'meta'>('preview')
const permission = ref<Permission | null>(null) // set only for a shared document
const links = ref<ShareLink[]>([])
const copied = ref<Permission | null>(null)
const readOnly = computed(() => permission.value === 'VIEW')
const linkKinds = [['VIEW', 'Просмотр'], ['EDIT', 'Редактирование']] as const
const linkUrl = (p: Permission) => {
  const l = links.value.find((x) => x.permission === p)
  return l ? `${location.origin}${location.pathname}#s/${l.token}` : null
}

// frontmatter is metadata (shown in the aside), not content: marked would render it as a setext heading
const body = computed(() => text.value.replace(/^---\r?\n[\s\S]*?\r?\n---[ \t]*(\r?\n|$)/, ''))
const preview = computed(() => DOMPurify.sanitize(marked.parse(body.value, { async: false }) as string))
const dirty = computed(() => !!doc.value && text.value !== doc.value.content)
const date = (s: string) => new Date(s).toLocaleString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const panes = computed(() => ([['preview', 'Превью'], ['versions', 'Версии'], ['meta', 'Метаданные']] as const)
  .filter(([k]) => !props.shareToken || k !== 'versions'))

async function load() {
  error.value = ''
  try {
    if (props.shareToken) {
      const d = await api.shared(props.shareToken)
      permission.value = d.permission
      doc.value = d
    } else {
      doc.value = await api.document(props.id!)
    }
    text.value = doc.value.content
    shown.value = doc.value.version
    if (!props.shareToken) [versions.value, links.value] = await Promise.all([api.versions(props.id!), api.links(props.id!)])
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function save() {
  error.value = ''
  try {
    await (props.shareToken ? api.saveShared(props.shareToken, text.value) : api.save(props.id!, text.value))
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
    await api.remove(props.id!)
    emit('close')
  } catch (e) {
    error.value = (e as Error).message
  }
}

// Loads an old version into the editor; saving it creates a new version.
async function showVersion(v: number) {
  text.value = (await api.version(props.id!, v)).content
  shown.value = v
}

async function createLink(p: Permission) {
  try {
    await api.createLink(props.id!, p)
    links.value = await api.links(props.id!)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function revokeLink(p: Permission) {
  if (!confirm('Отключить ссылку? Открыть документ по ней больше не получится.')) return
  try {
    await api.revokeLink(props.id!, p)
    links.value = await api.links(props.id!)
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function copyLink(p: Permission) {
  await navigator.clipboard.writeText(linkUrl(p)!)
  copied.value = p
  setTimeout(() => (copied.value = null), 2000)
}

watch(() => [props.id, props.shareToken], load, { immediate: true })
</script>

<template>
  <main class="page">
    <div class="toolbar">
      <button class="btn back" @click="emit('close')">{{ shareToken ? '← К поиску' : '← К результатам' }}</button>
      <span class="grow" />
      <span v-if="dirty" class="state"><span class="dot" style="background: var(--accent)" /> Не сохранено</span>
      <span v-else-if="saved" class="state muted" role="status">Сохранено</span>
      <span v-if="permission" class="state muted">{{ readOnly ? 'Только просмотр' : 'Совместное редактирование' }}</span>
      <button v-if="!shareToken" class="btn danger" @click="remove">Удалить</button>
      <button v-if="doc && !readOnly" class="btn primary save" :disabled="!dirty" @click="save">Сохранить как v{{ doc.version + 1 }}</button>
    </div>
    <div v-if="error" role="alert" class="alert">{{ error }}</div>
    <template v-if="doc">
      <header>
        <h1>{{ doc.title }}</h1>
        <p class="muted meta">
          {{ doc.author }}<template v-if="doc.category"> · {{ doc.category }}</template> · {{ doc.wordCount }} слов ·
          {{ (doc.sizeBytes / 1024).toFixed(1) }} КБ · {{ date(doc.updatedAt) }}
        </p>
      </header>
      <div class="tabs" role="tablist" aria-label="Разделы документа">
        <button v-for="[k, l] in panes" :key="k" role="tab" :aria-selected="pane === k" @click="pane = k">{{ l }}</button>
      </div>
      <div class="layout" :data-pane="pane">
        <section class="panel work">
          <label class="editor">
            <span class="label">Markdown</span>
            <textarea v-model="text" class="mono" spellcheck="false" :readonly="readOnly" />
          </label>
          <div class="pv">
            <span class="label">Превью</span>
            <article class="md" v-html="preview" />
          </div>
          <p class="note muted">Редактирование доступно на компьютере</p>
        </section>
        <aside class="side">
          <section class="meta-box">
            <h2 class="label">Метаданные</h2>
            <dl>
              <dt>Автор</dt><dd>{{ doc.author }}</dd>
              <dt>Категория</dt><dd>{{ doc.category ?? '—' }}</dd>
              <dt>Создан</dt><dd>{{ date(doc.createdAt) }}</dd>
              <dt>Слов</dt><dd class="mono">{{ doc.wordCount }}</dd>
              <dt>Размер</dt><dd class="mono">{{ doc.sizeBytes }} Б</dd>
            </dl>
            <div class="tags">
              <template v-for="t in doc.tags" :key="t">
                <span v-if="shareToken" class="chip">{{ t }}</span>
                <button v-else class="chip" @click="emit('tag', t)">{{ t }}</button>
              </template>
            </div>
          </section>
          <section v-if="!shareToken" class="share-box">
            <h2 class="label">Доступ по ссылке</h2>
            <p class="muted hint">Открыть сможет любой вошедший пользователь, у кого есть ссылка</p>
            <div v-for="[p, l] in linkKinds" :key="p" class="share-row">
              <span>{{ l }}</span>
              <template v-if="linkUrl(p)">
                <input class="field mono" :value="linkUrl(p)!" readonly :aria-label="`Ссылка: ${l}`" @focus="($event.target as HTMLInputElement).select()">
                <div class="share-actions">
                  <button class="btn" @click="copyLink(p)">{{ copied === p ? 'Скопировано' : 'Копировать' }}</button>
                  <button class="btn danger" @click="revokeLink(p)">Отключить</button>
                </div>
              </template>
              <button v-else class="btn dashed" @click="createLink(p)">Создать ссылку</button>
            </div>
          </section>
          <section v-if="!shareToken" class="ver-box">
            <h2 class="label">Версии</h2>
            <ol class="timeline">
              <li v-if="dirty" class="draft"><span class="dot hollow" /> <span>Черновик</span> <span class="muted">не сохранён</span></li>
              <li v-for="v in [...versions].sort((a, b) => b.version - a.version)" :key="v.version">
                <button :aria-current="shown === v.version ? 'true' : undefined" @click="showVersion(v.version)">
                  <span class="dot" :class="{ hollow: shown !== v.version }" />
                  <span class="mono">v{{ v.version }}</span>
                  <span class="muted">{{ date(v.createdAt) }}</span>
                  <span v-if="v.version === doc.version" class="cur">текущая</span>
                </button>
              </li>
            </ol>
          </section>
        </aside>
      </div>
    </template>
  </main>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap }
.back { border: 0; padding: 0; color: var(--ink-3) }
.grow { flex: 1 }
.state { display: inline-flex; align-items: center; gap: 8px; font-size: 14px }
.meta { margin: 8px 0 0; font-size: 14px }
.tabs { display: none }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 32px; align-items: start }
.work { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); min-height: 560px }
.editor, .pv { display: flex; flex-direction: column; gap: 0; padding: 16px 20px; min-width: 0 }
.editor { border-right: 1px solid var(--line) }
.editor .label, .pv .label { border-bottom-color: var(--line); margin-bottom: 12px }
textarea { flex: 1; min-height: 480px; padding: 12px; border: 1px solid var(--line); border-radius: 4px; background: var(--code); font-size: 13px; line-height: 1.6; resize: vertical }
.note { display: none }
.md { font-size: 15px; line-height: 1.65; overflow-wrap: anywhere }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { margin: 1em 0 .4em; line-height: 1.25 }
.md :deep(h1) { font-size: 24px }
.md :deep(h2) { font-size: 19px }
.md :deep(pre), .md :deep(code) { font-family: var(--mono); font-size: 13px; background: var(--code) }
.md :deep(pre) { padding: 12px; border: 1px solid var(--line); border-radius: 4px; overflow-x: auto }
.md :deep(table) { border-collapse: collapse }
.md :deep(th), .md :deep(td) { padding: 4px 10px; border: 1px solid var(--line) }
.md :deep(blockquote) { margin: 0; padding-left: 14px; border-left: 2px solid var(--border); color: var(--ink-3) }
.side { display: flex; flex-direction: column; gap: 28px }
dl { margin: 12px 0; display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; font-size: 14px }
dt { color: var(--muted) }
dd { margin: 0 }
.tags { display: flex; flex-wrap: wrap; gap: 6px }
.timeline { margin: 12px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 2px }
.timeline li.draft { display: flex; align-items: center; gap: 10px; padding: 8px 0; font-size: 14px }
.timeline button { width: 100%; min-height: 40px; display: flex; align-items: center; gap: 10px; padding: 0 8px; margin: 0 -8px; border: 0; border-radius: 4px; background: none; font-size: 14px; text-align: left }
.timeline button:hover { background: var(--line-2) }
.timeline button[aria-current] .mono { font-weight: 500 }
.cur { margin-left: auto; font-size: 12px; color: var(--accent) }
.hint { margin: 10px 0 4px; font-size: 13px }
.share-row { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; padding: 10px 0; font-size: 14px }
.share-row + .share-row { border-top: 1px solid var(--line) }
.share-row .field { width: 100%; height: 36px; font-size: 12px }
.share-actions { display: flex; gap: 8px }

/* narrow window: editor above preview instead of two cramped columns */
@media (max-width: 1100px) {
  .work { grid-template-columns: minmax(0, 1fr) }
  .editor { border-right: 0; border-bottom: 1px solid var(--line) }
  textarea { min-height: 320px }
}

@media (max-width: 720px) {
  .toolbar .danger, .save, .state { display: none }
  h1 { font-size: 24px }
  .tabs { display: flex; border-bottom: 1px solid var(--line) }
  .tabs button { flex: 1; height: 44px; border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px; background: none; color: var(--muted) }
  .tabs button[aria-selected='true'] { border-bottom-color: var(--ink); color: var(--ink); font-weight: 500 }
  .layout { grid-template-columns: minmax(0, 1fr); gap: 0 }
  .work { grid-template-columns: minmax(0, 1fr); min-height: 0 }
  .editor, .pv .label { display: none }
  .note { display: block; margin: 0; padding: 12px 20px; border-top: 1px solid var(--line); font-size: 13px }
  .layout:not([data-pane='preview']) .work,
  .layout:not([data-pane='meta']) .meta-box,
  .layout:not([data-pane='meta']) .share-box,
  .layout:not([data-pane='versions']) .ver-box { display: none }
  .side .label { display: none }
}
</style>
