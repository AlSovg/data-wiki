<script setup lang="ts">
import { computed } from 'vue'
import type { Hit, Meta } from '../api'

// Radial map on desktop, a linear scale on phones. Distance from the centre (or the left edge)
// is the score relative to the top hit; the angle is a stable hash of the id, dot size is document length.
// Misses (documents the query did not match) sit hollow just outside the outer ring; the phone strip omits them.
const props = defineProps<{ hits: Hit[]; misses: Meta[]; sel: string | null }>()
const emit = defineEmits<{ pick: [id: string]; open: [id: string] }>()

const C = 270, R0 = 20, R = 240
const max = computed(() => Math.max(...props.hits.map((h) => h.score), 1e-9))
const angle = (id: string) => {
  let x = 0
  for (const ch of id) x = (x * 31 + ch.charCodeAt(0)) | 0
  return ((x >>> 0) % 360) * Math.PI / 180
}
const short = (s: string) => (s.length > 20 ? s.slice(0, 19) + '…' : s)

const dots = computed(() => props.hits.map((h, i) => {
  const rel = 1 - h.score / max.value
  const r = R0 + (R - R0) * rel
  const a = angle(h.document.id)
  const x = C + r * Math.cos(a), y = C + r * Math.sin(a)
  return {
    id: h.document.id, n: String(i + 1).padStart(2, '0'), title: h.document.title, label: short(h.document.title),
    score: h.score.toFixed(3), x, y, size: 4 + Math.min(4, Math.sqrt(h.document.wordCount) / 6),
    right: x < C + 150, pos: 3 + rel * 94,
  }
}))
// evenly spaced on the outer ring so they never pile up; labels point inward to stay inside the map
const out = computed(() => props.misses.map((m, i) => {
  const a = (i / props.misses.length) * 2 * Math.PI + Math.PI / 7
  const x = C + R * Math.cos(a)
  // labels only while they fit; with many misses the dots alone show the spread
  return { id: m.id, title: m.title, label: props.misses.length <= 12 ? short(m.title) : '', x, y: C + R * Math.sin(a), right: x >= C }
}))
const ray = computed(() => dots.value.find((d) => d.id === props.sel))
// ring radius -> the score it stands for
const rings = computed(() => [1, .75, .5, .25].map((k) => ({ r: R * k, v: (max.value * (1 - (R * k - R0) / (R - R0))).toFixed(2) })))
</script>

<template>
  <figure class="map">
    <figcaption class="label">Карта релевантности</figcaption>
    <svg class="radial" viewBox="0 0 540 540" role="group" aria-label="Карта релевантности: ближе к центру — выше скор">
      <circle v-for="g in rings" :key="g.r" :cx="C" :cy="C" :r="g.r" fill="none" stroke="var(--ring)" />
      <text v-for="g in rings" :key="'t' + g.r" :x="C + 4" :y="C - g.r - 4" class="scale">{{ g.v }}</text>
      <rect :x="C - 5" :y="C - 5" width="10" height="10" :transform="`rotate(45 ${C} ${C})`" fill="var(--surface)" stroke="var(--ink)" stroke-width="1.5" />
      <g v-for="m in out" :key="m.id" class="miss" role="link" tabindex="0" :aria-label="`${m.title}, не найден`"
         @click="emit('open', m.id)" @keydown.enter.prevent="emit('open', m.id)">
        <title>{{ m.title }}</title>
        <circle :cx="m.x" :cy="m.y" r="12" fill="transparent" />
        <circle :cx="m.x" :cy="m.y" r="4" fill="var(--bg)" stroke="var(--hollow)" stroke-width="1.5" />
        <text v-if="m.label" :x="m.right ? m.x - 9 : m.x + 9" :y="m.y + 4" :text-anchor="m.right ? 'end' : 'start'" class="miss-name">{{ m.label }}</text>
      </g>
      <line v-if="ray" :x1="C" :y1="C" :x2="ray.x" :y2="ray.y" stroke="var(--accent)" />
      <g v-for="d in dots" :key="d.id" class="hit" role="button" tabindex="0" :aria-label="`${d.title}, скор ${d.score}`"
         :aria-pressed="d.id === sel" @click="emit('pick', d.id)" @keydown.enter.space.prevent="emit('pick', d.id)">
        <circle :cx="d.x" :cy="d.y" r="16" fill="transparent" />
        <circle v-if="d.id === sel" :cx="d.x" :cy="d.y" :r="d.size + 4" fill="none" stroke="var(--accent)" />
        <circle :cx="d.x" :cy="d.y" :r="d.size" :fill="d.id === sel ? 'var(--accent)' : 'var(--ink)'" />
        <text :x="d.right ? d.x + d.size + 8 : d.x - d.size - 8" :y="d.y + 4" :text-anchor="d.right ? 'start' : 'end'" class="name">
          <tspan class="num">{{ d.n }}</tspan> {{ d.label }}
        </text>
      </g>
    </svg>
    <div class="strip" role="group" aria-label="Шкала релевантности">
      <span class="axis" aria-hidden="true" />
      <span class="origin" aria-hidden="true" />
      <button v-for="d in dots" :key="d.id" :style="{ left: `calc(${d.pos}% - 16px)` }" :aria-label="`${d.title}, скор ${d.score}`"
              :aria-pressed="d.id === sel" @click="emit('pick', d.id)">
        <span :style="{ width: `${d.size * 2}px`, height: `${d.size * 2}px` }" />
      </button>
    </div>
    <div class="ticks mono" aria-hidden="true"><span>{{ max.toFixed(2) }}</span><span>{{ (max / 2).toFixed(2) }}</span><span>0</span></div>
    <p class="legend">
      <span class="dot" /> найден <template v-if="misses.length"><span class="dot hollow" /> не найден</template>
      · ближе к центру — выше скор · размер точки — объём документа
    </p>
  </figure>
</template>

<style scoped>
.map { margin: 0; display: flex; flex-direction: column; gap: 12px }
.radial { width: 100%; max-width: 540px; overflow: visible }
.scale { font: 10px var(--mono); fill: var(--faint) }
.hit { cursor: pointer; outline: none }
.hit:focus-visible circle:first-child { stroke: var(--accent); stroke-width: 2 }
.name { font: 13px var(--sans); fill: var(--ink-2) }
.num { font-family: var(--mono); fill: var(--muted) }
.miss { cursor: pointer; outline: none }
.miss:focus-visible circle:first-of-type { stroke: var(--accent); stroke-width: 2 }
.miss-name { font: 12px var(--sans); fill: var(--faint) }
.miss:hover .miss-name { fill: var(--ink-3) }
.legend { margin: 0; font-size: 13px; color: var(--muted) }
.legend .dot { vertical-align: -1px; margin-right: 4px }
.strip, .ticks { display: none }

@media (max-width: 720px) {
  .map { padding: 12px 14px 10px; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; gap: 8px }
  .map .label { border: 0; padding: 0 }
  .radial, .legend { display: none }
  .strip { display: block; position: relative; height: 44px }
  .axis { position: absolute; left: 0; right: 0; top: 22px; height: 1px; background: var(--line) }
  .origin { position: absolute; left: -1px; top: 18px; width: 8px; height: 8px; border: 1.5px solid var(--ink); transform: rotate(45deg) }
  .strip button { position: absolute; top: 6px; width: 32px; height: 32px; padding: 0; border: 0; background: none; display: flex; align-items: center; justify-content: center }
  .strip button span { border-radius: 50%; background: var(--ink) }
  .strip button[aria-pressed='true'] span { background: var(--accent); box-shadow: 0 0 0 3px var(--surface), 0 0 0 4px var(--accent) }
  .ticks { display: flex; justify-content: space-between; font-size: 10px; color: var(--faint) }
}
</style>
