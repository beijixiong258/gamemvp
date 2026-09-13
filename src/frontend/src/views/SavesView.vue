<script setup lang="ts">
import { onMounted } from 'vue'
import { useGameStore } from '../stores/game'
import { assets } from '../config/assets'
const game = useGameStore()
onMounted(() => game.loadSaves())
const created = (date: string) => new Date(date).toLocaleDateString('zh-CN')
</script>
<template>
  <main id="main-content" class="entry-page" :style="{ '--scene-image': 'url(' + assets.home + ')' }">
    <header class="brand-line"><span class="seal">书</span><span>豪杰成长计划plus</span></header>
    <div class="entry-content">
      <section class="entry-intro">
        <h1>豪杰的故事由你来书写</h1>
        <p>当前为最小交付版本（MVP），先开放书生线。你可以从入学开始，体验学习、人物互动和考试，进度会自动保存。</p>
        <RouterLink class="button primary large" to="/new">启程 · 新的人生 <span aria-hidden="true">↗</span></RouterLink>
      </section>
      <section class="save-shelf paper" aria-labelledby="save-title">
        <header class="section-heading"><div><span class="eyebrow">存档</span><h2 id="save-title">读取存档</h2></div>
          <button class="text-button" :disabled="game.loading" @click="game.loadSaves()">刷新</button>
        </header>
        <p v-if="game.creationUncertain" class="inline-note">上次开局的结果尚未确认，请先核对下面是否已有新存档。</p>
        <div v-if="game.loading" class="empty-state" role="status"><span class="spinner"></span><p>正在翻阅存档……</p></div>
        <div v-else-if="!game.saves.length" class="empty-state"><span class="empty-glyph">卷</span><p>{{ game.error ? '暂时无法读取存档。' : '这里还没有写下的人生。' }}</p><small>从新的一局开始，进度会自动记在这里。</small></div>
        <div v-else class="save-list">
          <RouterLink v-for="save in game.saves" :key="save.saveId" class="save-card" :to="{ name: 'game', params: { saveId: save.saveId } }">
            <div class="save-monogram" aria-hidden="true">{{ Array.from(save.characterName)[0] }}</div>
            <div class="save-card-body"><div class="save-card-title"><h3>{{ save.characterName }}</h3></div>
              <p>{{ save.age }} 岁 <span>·</span> {{ save.currentYear }} 年 <span v-if="save.degree">· {{ save.degree }}</span></p>
              <small>建档于 {{ created(save.createdAt) }}</small>
            </div><span class="save-arrow" aria-hidden="true">→</span>
          </RouterLink>
        </div>
      </section>
    </div>
    <footer class="entry-footer">《豪杰成长计划plus》是一款人生模拟游戏，你可以安排角色的行动、培养能力，并与其他人物互动。</footer>
  </main>
</template>
<style scoped>
.entry-intro {
  container-type: inline-size;
  min-width: 0;
}
.entry-intro h1 {
  font-size: clamp(18px, 9cqw, 48px);
  letter-spacing: 0;
  white-space: nowrap;
}
.entry-footer {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.8;
}
</style>
