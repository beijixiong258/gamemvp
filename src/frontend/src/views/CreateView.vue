<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
import { assets } from '../config/assets'
const game = useGameStore()
const router = useRouter()
const name = useDraft(() => 'mvp:create:name')
const region = ref('')
const regionLoading = ref(true)
const validName = computed(() => Array.from(name.value.trim()).length >= 2 && Array.from(name.value.trim()).length <= 8)
async function regions() {
  regionLoading.value = true
  await game.loadRegions()
  if (!region.value) region.value = game.regions[0]?.id ?? ''
  regionLoading.value = false
}
onMounted(regions)
async function start() {
  if (!validName.value || !region.value || game.busy) return
  const id = await game.createLife(name.value.trim(), region.value)
  if (id) await router.push({ name: 'game', params: { saveId: id }, query: { place: 'home' } })
}
</script>
<template>
  <main id="main-content" class="entry-page creation-page" :style="{ '--scene-image': 'url(' + assets.study + ')' }">
    <header class="brand-line"><RouterLink to="/" class="back-light">← 返回存档</RouterLink><span>豪杰成长计划plus</span></header>
    <form class="creation-card paper" @submit.prevent="start">
      <span class="eyebrow">第一章 · 人生初始</span><h1>落笔，是你的名字。</h1>
      <p class="muted">你将从六岁开始，在故乡求学长大。</p>
      <label class="field">人物姓名<input v-model="name" autocomplete="off" placeholder="输入 2 至 8 个字" maxlength="16" :disabled="game.busy" required /></label>
      <p v-if="name && !validName" class="field-hint">姓名需要 2 至 8 个字符。</p>
      <fieldset class="region-picker" :disabled="game.busy"><legend>选择故乡</legend>
        <p v-if="regionLoading">正在读取出生地……</p>
        <label v-for="place in game.regions" :key="place.id" :class="['region-option', { selected: region === place.id }]">
          <input v-model="region" type="radio" name="region" :value="place.id" /><span>{{ place.regionName }}</span><small>广东 · 惠州</small>
        </label>
        <button v-if="!regionLoading && !game.regions.length" type="button" @click="regions">重新读取出生地</button>
      </fieldset>
      <div v-if="game.creationUncertain" class="inline-note">
        <p>上次开局可能已经成功。先返回存档列表核对，避免重复创建。</p>
        <RouterLink to="/" class="text-button">查看存档</RouterLink>
        <button type="button" class="text-button" @click="game.confirmCreationChecked()">已核对，允许重新开局</button>
      </div>
      <button class="primary large full" :disabled="!validName || !region || regionLoading || game.busy || game.creationUncertain">开始人生 <span aria-hidden="true">→</span></button>
      <p class="fine-print">每次行动确认后，进度自动保存。童年往事可以进入家中后再慢慢回想。</p>
    </form>
  </main>
</template>
