<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
import { assets } from '../config/assets'
import { examName } from '../config/locations'
import ExamResult from '../components/ExamResult.vue'
const game = useGameStore()
const route = useRoute()
const exam = computed(() => game.detail?.exams.find(e => e.id === route.params.examId && e.characterId === game.player?.id))
const answer = useDraft(() => 'mvp:exam-answer:' + route.params.examId)
const image = computed(() => exam.value?.examType === 'EXAM_XIANSHI' ? assets.exam : assets.classroom)
async function submit() { if (exam.value && answer.value.trim()) await game.submitExam(exam.value.id, answer.value.trim()) }
</script>
<template>
  <main id="main-content" class="exam-page" :style="{ '--scene-image': 'url(' + image + ')' }">
    <header class="brand-line"><RouterLink to="/" class="back-light">← 存档</RouterLink><span>{{ game.player?.name }} · {{ game.detail?.save.age }} 岁</span><small>{{ game.detail?.save.currentYear }} 年</small></header>
    <article v-if="exam" class="exam-paper paper">
      <header class="exam-heading"><span class="eyebrow">书卷检验所学</span><h1>{{ examName(exam.examType) }}</h1><p>{{ exam.status === 'READY' ? '静下心来，读一读这道题。' : '这一场考试，已经落笔。' }}</p></header>
      <section class="exam-question"><span class="eyebrow">题目</span><p class="question-text">{{ exam.questionText }}</p></section>
      <template v-if="exam.status === 'READY'">
        <div class="thought-section"><h2>先理一理思路</h2><p v-if="exam.aiThoughtBubble" class="prose">{{ exam.aiThoughtBubble }}</p>
          <template v-else><p class="muted">根据人物已有的能力和学识，整理可供参考的思路。</p><button :disabled="!game.canWrite" @click="game.thought(exam.id)">看看我的思路</button></template></div>
        <form class="writing-form" @submit.prevent="submit"><h2>亲自作答</h2><label class="sr-only" for="exam-answer">考试答卷</label><textarea id="exam-answer" v-model="answer" rows="10" maxlength="8000" :disabled="!game.canWrite" placeholder="写下你的答案……"></textarea>
          <div class="form-bottom"><small>{{ answer.length }} / 8000</small><button class="primary" :disabled="!game.canWrite || !answer.trim()">交卷</button></div></form>
        <div class="auto-exam"><div><h3>也可以交给人物自行应考</h3><p class="muted">根据人物的学识与状态完成答卷，提交后使用实际考试结果。</p></div><button :disabled="!game.canWrite" @click="game.submitExam(exam.id)">系统代行 →</button></div>
      </template>
      <template v-else><ExamResult :exam="exam" /><div class="exam-continue"><RouterLink class="button primary large" :to="{name:'game',params:{saveId:route.params.saveId},query:{place:'classroom'}}">收好答卷，继续求学 →</RouterLink></div></template>
    </article>
    <section v-else class="paper exam-paper empty-state"><h1>没有找到这场考试</h1><button @click="game.refresh()">重新读取</button><RouterLink class="button" :to="{ name: 'game', params: { saveId: route.params.saveId } }">返回人物</RouterLink></section>
  </main>
</template>
