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

function open(id: string) { openId.value = id; tab.value = 'doc' }
function byTag(tag: string) { filterTag.value = tag; tab.value = 'search' }
function logout() { token.clear(); authed.value = false }
</script>

<template>
  <LoginView v-if="!authed" @done="authed = true" />
  <template v-else>
    <nav>
      <strong>Data Wiki</strong>
      <button :class="{ active: tab === 'search' }" @click="tab = 'search'">Поиск</button>
      <button :class="{ active: tab === 'import' }" @click="tab = 'import'">Импорт</button>
      <button :class="{ active: tab === 'stats' }" @click="tab = 'stats'">Статистика</button>
      <span class="grow" />
      <button @click="logout">Выйти</button>
    </nav>
    <main>
      <!-- v-show keeps the search state while a document is open -->
      <SearchView v-show="tab === 'search'" :tag="filterTag" @open="open" />
      <DocumentView v-if="tab === 'doc' && openId" :id="openId" @close="tab = 'search'" @tag="byTag" />
      <ImportView v-if="tab === 'import'" @open="open" />
      <StatsView v-if="tab === 'stats'" @tag="byTag" />
    </main>
  </template>
</template>
