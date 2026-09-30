<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type Stats } from '../api'

const emit = defineEmits<{ tag: [tag: string] }>()
const stats = ref<Stats | null>(null)
const error = ref('')

onMounted(async () => {
  try {
    stats.value = await api.stats()
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>

<template>
  <h2>Статистика</h2>
  <div v-if="error" class="err">{{ error }}</div>
  <template v-if="stats">
    <p>Документов: {{ stats.documents }} · слов: {{ stats.totalWords }} · {{ (stats.totalSizeBytes / 1024).toFixed(1) }} КБ</p>
    <div class="split">
      <div>
        <h3>Теги</h3>
        <span v-for="b in stats.byTag" :key="b.key" class="tag" @click="emit('tag', b.key)">{{ b.key }} · {{ b.count }}</span>
      </div>
      <div>
        <h3>Категории</h3>
        <table><tr v-for="b in stats.byCategory" :key="b.key"><td>{{ b.key }}</td><td>{{ b.count }}</td></tr></table>
        <h3>Авторы</h3>
        <table><tr v-for="b in stats.byAuthor" :key="b.key"><td>{{ b.key }}</td><td>{{ b.count }}</td></tr></table>
      </div>
    </div>
  </template>
</template>
