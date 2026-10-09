<script setup lang="ts">
import { computed } from 'vue'
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
const game = useGameStore()
const book = computed(() => game.detail?.books.find(item => item.bookCode === game.question?.bookCode))
const answerCost = computed(() => book.value?.fatigueCost ?? 0)
const blockedReason = computed(() => book.value ? game.mainActionBlockedReason(answerCost.value) : '请刷新书籍信息后再提交。')
const text = useDraft(() => 'mvp:reading:' + (game.question?.questionId ?? 'none'))
async function submit() {
  if (!text.value.trim() || blockedReason.value) return
  await game.answerReading(text.value.trim())
}
</script>
<template>
  <form v-if="game.question" class="writing-form" @submit.prevent="submit">
    <span class="eyebrow">{{ game.question.bookName }} · 阅读（手动）</span>
    <p class="question-text">{{ game.question.question }}</p>
    <label class="field">写下你的理解<textarea v-model="text" rows="9" maxlength="8000" :disabled="!game.canWrite" placeholder="不必照抄书句，试着用自己的话说清楚。" required></textarea></label>
    <div class="form-bottom"><small>{{ text.length }} / 8000 · 疲劳消耗 {{ answerCost }} 点体力，占用本回合主要行动额度</small><button class="primary" :disabled="!game.canWrite || !!blockedReason || !text.trim()">提交阅读回答</button></div>
    <p class="fine-print">提交后不会自动结束回合；本题在结束回合后失效。</p>
    <p v-if="blockedReason" class="blocked-reason">{{ blockedReason }}</p>
  </form>
  <div v-else-if="game.outcome?.evaluation" class="reading-result"><span class="eyebrow">塾师评阅</span><h3>阅读回答评分 {{ game.outcome.score }}</h3><p class="prose">{{ game.outcome.evaluation }}</p><p>{{ game.notice }}</p></div>
  <p v-else class="empty-state">这一回合没有待完成的阅读题目，请从讲堂的书目中选择一本书。</p>
</template>
