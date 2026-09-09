<script setup lang="ts">
import type { Exam } from '../types/game'
defineProps<{ exam: Exam }>()
</script>
<template>
  <div class="exam-score"><span>本次成绩</span><strong>{{ exam.finalScore ?? '—' }}</strong><p>{{ exam.status === 'COMPLETED_PASS' ? '通过' : exam.status === 'COMPLETED_FAIL' ? '未通过' : '尚未结算' }} · 合格线 {{ exam.passThreshold }}</p></div>
  <p v-if="exam.aiContent" class="prose evaluation">{{ exam.aiContent }}</p>
  <details class="answer-details"><summary>查看本次答卷</summary><p class="prose">{{ exam.playerInput || exam.aiAnswerText || '本次未保存答卷正文。' }}</p></details>
  <details class="answer-details"><summary>成绩明细</summary><dl class="stat-grid">
    <div><dt>基础能力</dt><dd>{{ exam.baseAbilityScore }}</dd></div><div><dt>身体状态</dt><dd>{{ exam.stateOffset }}</dd></div>
    <div><dt>骰点</dt><dd>{{ exam.diceRoll }}</dd></div><div><dt>运气修正</dt><dd>{{ exam.luckOffset }}</dd></div><div><dt>内容修正</dt><dd>{{ exam.aiPlayerContentModifier ?? 0 }}</dd></div>
  </dl></details>
</template>
