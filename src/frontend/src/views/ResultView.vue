<script setup lang="ts">
import { computed } from 'vue'
import { useGameStore } from '../stores/game'
import { assets } from '../config/assets'
import { examName } from '../config/locations'
import ExamResult from '../components/ExamResult.vue'
const game = useGameStore()
const exam = computed(() => game.detail?.exams.find(e => e.examType === 'EXAM_XIANSHI' && e.characterId === game.player?.id && e.status !== 'READY'))
const exams = computed(() => game.detail?.exams.filter(e => e.characterId === game.player?.id && e.status !== 'READY') ?? [])
</script>
<template>
  <main v-if="game.detail" id="main-content" class="result-page" :style="{ '--scene-image': 'url(' + assets.county + ')' }">
    <header class="brand-line"><span class="seal">卷</span><span>豪杰成长计划plus</span><RouterLink to="/" class="back-light">返回存档 →</RouterLink></header>
    <article class="result-paper paper"><span class="eyebrow">十年寒窗 · 此卷暂收</span><h1>{{ game.player?.name }}的人生一卷</h1>
      <p class="result-lead">从六岁入塾，到十六岁县试。你在这 {{ game.detail.save.totalTurnNumber }} 个回合里，写下了自己的求学岁月。</p>
      <ExamResult v-if="exam" :exam="exam" />
      <div class="result-exams"><h2>几度应考</h2><div v-for="item in exams" :key="item.id"><span>{{ examName(item.examType) }}</span><strong>{{ item.finalScore }} 分</strong><span>{{ item.status === 'COMPLETED_PASS' ? '通过' : item.status === 'COMPLETED_FAIL' ? '未通过' : '尚未结算' }}</span></div></div>
      <h2>那些记得的事</h2><ol class="timeline"><li v-for="event in game.detail.milestones" :key="event.id"><small>第 {{ event.occurredTurnNumber }} 回合</small><p>{{ event.eventSummary }}</p></li></ol>
      <p class="ending-note">这一段人生，写到这里。<br />今后的路，留待来日。</p>
      <div class="button-row centered"><RouterLink class="button primary large" to="/new">再启一段人生 →</RouterLink><RouterLink class="button large" to="/">返回存档</RouterLink></div>
    </article>
  </main>
</template>
