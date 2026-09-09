<script setup lang="ts">
import { onMounted } from 'vue'
import { useGameStore } from '../stores/game'
import { assets } from '../config/assets'
import { statusName } from '../config/locations'
const game = useGameStore()
onMounted(() => game.loadSaves())
const created = (date: string) => new Date(date).toLocaleDateString('zh-CN')
</script>
<template>
  <main id="main-content" class="entry-page" :style="{ '--scene-image': 'url(' + assets.home + ')' }">
    <header class="brand-line"><span class="seal">书</span><span>豪杰成长计划plus</span><small>一卷书 · 一段人生</small></header>
    <div class="entry-content">
      <section class="entry-intro">
        <span class="eyebrow light">岭南 · 嘉靖年间</span>
        <h1>从一声书响，<br />写起你的人生。</h1>
        <p>六岁入塾，十六岁赴县试。<br />在读书、交谈与日常选择中，走出自己的路。</p>
        <RouterLink class="button primary large" to="/new">启程 · 新的人生 <span aria-hidden="true">↗</span></RouterLink>
      </section>
      <section class="save-shelf paper" aria-labelledby="save-title">
        <header class="section-heading"><div><span class="eyebrow">往事存于此</span><h2 id="save-title">续上未完的一页</h2></div>
          <button class="text-button" :disabled="game.loading" @click="game.loadSaves()">刷新</button>
        </header>
        <p v-if="game.creationUncertain" class="inline-note">上次开局的结果尚未确认，请先核对下面是否已有新存档。</p>
        <div v-if="game.loading" class="empty-state" role="status"><span class="spinner"></span><p>正在翻阅存档……</p></div>
        <div v-else-if="!game.saves.length" class="empty-state"><span class="empty-glyph">卷</span><p>{{ game.error ? '暂时无法读取存档。' : '这里还没有写下的人生。' }}</p><small>从新的一局开始，进度会自动记在这里。</small></div>
        <div v-else class="save-list">
          <RouterLink v-for="save in game.saves" :key="save.saveId" class="save-card" :to="{ name: 'game', params: { saveId: save.saveId } }">
            <div class="save-monogram" aria-hidden="true">{{ Array.from(save.characterName)[0] }}</div>
            <div class="save-card-body"><div class="save-card-title"><h3>{{ save.characterName }}</h3><span class="tag">{{ statusName(save.status) }}</span></div>
              <p>{{ save.age }} 岁 <span>·</span> {{ save.currentYear }} 年 <span v-if="save.degree">· {{ save.degree }}</span></p>
              <small>建档于 {{ created(save.createdAt) }}</small>
            </div><span class="save-arrow" aria-hidden="true">→</span>
          </RouterLink>
        </div>
      </section>
    </div>
    <footer class="entry-footer">人间烟火里，且读且行。</footer>
  </main>
</template>
