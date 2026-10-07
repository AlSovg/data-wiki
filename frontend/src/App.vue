<script setup lang="ts">
import { ref } from 'vue'
import { token } from './api'
import LoginView from './views/LoginView.vue'
import SearchView from './views/SearchView.vue'
import DocumentView from './views/DocumentView.vue'
import ImportView from './views/ImportView.vue'
import StatsView from './views/StatsView.vue'

type Tab = 'search' | 'import' | 'stats' | 'doc'
const authed = ref(!!token.get())
const tab = ref<Tab>('search')
const openId = ref<string | null>(null)
const filterTag = ref<string | null>(null)
const tabs: { id: Tab; label: string; icon: string }[] = [
  { id: 'search', label: 'Поиск', icon: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5' },
  { id: 'import', label: 'Импорт', icon: 'M12 15V4M7 9l5-5 5 5M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4' },
  { id: 'stats', label: 'Статистика', icon: 'M5 20V11M12 20V4M19 20v-6' },
]

// a share link is /#s/<token>: the hash never reaches the server logs
const shareToken = ref<string | null>(null)
function readHash() {
  const m = location.hash.match(/^#s\/([\w-]+)$/)
  if (m) { shareToken.value = m[1]; tab.value = 'doc' }
}
readHash()
window.addEventListener('hashchange', readHash)

function open(id: string) { closeShared(); openId.value = id; tab.value = 'doc' }
function closeShared() {
  if (!shareToken.value) return
  shareToken.value = null
  history.replaceState(null, '', location.pathname + location.search)
}
function closeDoc() { closeShared(); tab.value = 'search' }
function byTag(tag: string) { filterTag.value = tag; tab.value = 'search' }
function logout() { token.clear(); authed.value = false }
// the document screen belongs to search in the navigation
const current = (id: Tab) => tab.value === id || (id === 'search' && tab.value === 'doc')
</script>

<template>
  <LoginView v-if="!authed" @done="authed = true" />
  <div v-else class="shell">
    <header class="top">
      <strong class="brand">Data Wiki</strong>
      <nav class="nav" aria-label="Разделы">
        <button v-for="t in tabs" :key="t.id" :aria-current="current(t.id) ? 'page' : undefined" @click="closeShared(); tab = t.id">{{ t.label }}</button>
      </nav>
      <span class="grow" />
      <span class="email">{{ token.email() }}</span>
      <button class="logout" :aria-label="`Выйти (${token.email()})`" @click="logout">
        <span>Выйти</span>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="8" r="4" /><path d="M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5" /></svg>
      </button>
    </header>
    <!-- v-show keeps the search state while a document is open -->
    <SearchView v-show="tab === 'search'" :tag="filterTag" @open="open" />
    <DocumentView v-if="tab === 'doc' && shareToken" :key="shareToken" :share-token="shareToken" @close="closeDoc" />
    <DocumentView v-else-if="tab === 'doc' && openId" :id="openId" @close="closeDoc" @tag="byTag" />
    <ImportView v-if="tab === 'import'" @open="open" />
    <StatsView v-if="tab === 'stats'" @tag="byTag" />
    <nav class="bottom" aria-label="Разделы">
      <button v-for="t in tabs" :key="t.id" :aria-current="current(t.id) ? 'page' : undefined" @click="closeShared(); tab = t.id">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="t.icon" /></svg>
        {{ t.label }}
      </button>
    </nav>
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; display: flex; flex-direction: column }
.top { height: 64px; flex-shrink: 0; padding: 0 56px; display: flex; align-items: center; gap: 40px; border-bottom: 1px solid var(--line) }
.brand { font-size: 18px; font-weight: 600; letter-spacing: -.01em }
.nav { display: flex; gap: 28px; align-self: stretch }
.nav button { padding: 0; border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px; background: none; color: var(--ink-3) }
.nav button[aria-current] { border-bottom-color: var(--ink); color: var(--ink); font-weight: 500 }
.grow { flex: 1 }
.email { margin-right: -16px; font-size: 14px; color: var(--muted) }
.logout { display: flex; align-items: center; min-height: 44px; padding: 0; border: 0; background: none; color: var(--ink-3) }
.logout svg, .bottom { display: none }

@media (max-width: 720px) {
  .top { height: 52px; padding: 0 16px }
  .brand { font-size: 17px }
  .nav, .email, .logout span { display: none }
  .logout svg { display: block }
  .logout { width: 44px; justify-content: center; margin-right: -10px }
  .bottom { position: sticky; bottom: 0; height: 60px; display: grid; grid-template-columns: repeat(3, 1fr); border-top: 1px solid var(--line); background: var(--surface) }
  .bottom button { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; border: 0; background: none; font-size: 12px; color: var(--muted) }
  .bottom button[aria-current] { color: var(--ink); font-weight: 500 }
}
</style>
