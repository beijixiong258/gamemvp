<script setup lang="ts">
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
const game = useGameStore()
const text = useDraft(() => 'mvp:reading:' + (game.question?.questionId ?? 'none'))
async function submit() {
  if (!text.value.trim()) return
  await game.answerReading(text.value.trim())
}
</script>
<template>
  <form v-if="game.question" class="writing-form" @submit.prevent="submit">
    <span class="eyebrow">{{ game.question.bookName }} · 读书体会</span>
    <p class="question-text">{{ game.question.question }}</p>
    <label class="field">写下你的理解<textarea v-model="text" rows="9" maxlength="8000" :disabled="!game.canWrite" placeholder="不必照抄书句，试着用自己的话说清楚。" required></textarea></label>
    <div class="form-bottom"><small>{{ text.length }} / 8000 · 提交成功后消耗一回合</small><button class="primary" :disabled="!game.canWrite || !text.trim()">交给塾师评阅</button></div>
  </form>
  <div v-else-if="game.outcome?.evaluation" class="reading-result"><span class="eyebrow">塾师评阅</span><h3>体会评分 {{ game.outcome.score }}</h3><p class="prose">{{ game.outcome.evaluation }}</p><p>{{ game.notice }}</p></div>
  <p v-else class="empty-state">这一回合没有待完成的体会题，请从讲堂的书目中选择一本书。</p>
</template>
