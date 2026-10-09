<script setup lang="ts">
import type { Exam } from '../types/game'
defineProps<{ exam: Exam }>()
</script>
<template>
  <div class="exam-score"><span>本次成绩</span><strong>{{ exam.finalScore ?? '—' }}</strong><p>{{ exam.status === 'COMPLETED_PASS' ? '通过' : exam.status === 'COMPLETED_FAIL' ? '未通过' : '尚未结算' }} · 合格线 {{ exam.passThreshold }}</p></div>
  <p v-if="exam.aiContent" class="prose evaluation">{{ exam.aiContent }}</p>
  <details class="answer-details"><summary>查看本次答卷</summary><p class="prose">{{ exam.playerInput || exam.aiAnswerText || '本次未保存答卷正文。' }}</p></details>
  <details class="answer-details"><summary>成绩明细</summary><p v-if="exam.playerChoice === 'PLAYER'" class="muted">本场以答卷为主要评分依据，结合角色能力、身体状态与少量运气影响。以下保留角色与原始骰点记录，相对自动基线的调整不等于答卷评分。</p><dl class="stat-grid">
    <div><dt>{{ exam.playerChoice === 'PLAYER' ? '角色能力参考' : '基础能力' }}</dt><dd>{{ exam.baseAbilityScore }}</dd></div><div><dt>身体状态</dt><dd>{{ exam.stateOffset }}</dd></div>
    <div><dt>骰点</dt><dd>{{ exam.diceRoll }}</dd></div><div><dt>{{ exam.playerChoice === 'PLAYER' ? '原始运气修正' : '运气修正' }}</dt><dd>{{ exam.luckOffset }}</dd></div><div><dt>{{ exam.playerChoice === 'PLAYER' ? '相对自动基线调整' : '内容修正' }}</dt><dd>{{ exam.aiPlayerContentModifier ?? 0 }}</dd></div>
  </dl></details>
</template>
