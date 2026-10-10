<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
import { portrait } from '../config/assets'
import { attributeLabels } from '../config/reading'
import type { DialogueMessage } from '../types/game'
const game = useGameStore()
const text = useDraft(() => 'mvp:dialogue-draft:' + (game.dialogue?.id ?? 'none'))
const counterpart = computed(() => game.detail?.npcs.find(n => n.id === game.dialogue?.counterpartId))
const history = computed<DialogueMessage[]>(() => { try { return JSON.parse(game.dialogue?.messagesJson || '[]') } catch { return [] } })
const counterpartName = computed(() => counterpart.value?.name
  ?? history.value.find(item => item.speaker === 'counterpart' && item.speakerName)?.speakerName ?? '对方')
const completedRounds = computed(() => history.value.filter(item => item.speaker === 'counterpart').length)
const paidCost = computed(() => game.dialogue?.staminaCost ?? 0)
const blockedReason = computed(() => {
  if (!game.detail || !game.dialogue) return '请先读取最新进度。'
  if (game.sick) return '当前身体状况无法继续交谈，请结束对话。'
  if (game.player!.stamina <= 0) return '你累了，先休息吧。'
  const rules = game.detail.actionRules
  const characters = (game.dialogue.staminaCharacters ?? 0) + text.value.trim().length
  const minimumCost = Math.max(0, Math.max(rules.dialogueMinStaminaCost,
    Math.ceil((characters + 1) / rules.dialogueCharactersPerStamina)) - paidCost.value)
  const minimumReason = game.staminaBlockedReason(minimumCost)
  if (minimumReason) return minimumReason
  const remainingCharacters = (paidCost.value + game.player!.stamina) * rules.dialogueCharactersPerStamina - characters
  return remainingCharacters <= 0 ? '你累了，先休息吧。也可缩短发言后再试。' : ''
})
const numericLabels: Record<string, string> = { ...attributeLabels, characterJiankang: '健康', stamina: '体力' }
function numericChanges(item: DialogueMessage) {
  return Object.entries(item.numericChanges ?? {}).filter(([, value]) => value !== 0)
    .map(([key, value]) => (numericLabels[key] ?? key) + ' ' + (value > 0 ? '+' : '') + value).join('、')
}
const bottom = ref<HTMLElement>()
watch(() => game.dialogue?.version, async () => {
  const lastActorMessage = [...history.value].reverse().find(item => item.speaker === 'actor' && !item.manualEnd)
  if (lastActorMessage?.text === text.value.trim()) text.value = ''
  await nextTick(); bottom.value?.scrollIntoView({ block: 'nearest' })
})
async function send() {
  if (!text.value.trim() || blockedReason.value) return
  await game.sendDialogue(text.value.trim())
}
</script>
<template>
  <template v-if="game.dialogue">
    <div class="dialogue-person"><img :src="portrait(counterpart?.npcCode)" :alt="counterpartName" /><div><h3>{{ counterpartName }}</h3><p>{{ game.dialogue.ended ? '这场交谈已经结束' : '正在交谈' }} · {{ completedRounds }} / 5 轮</p></div></div>
    <div class="dialogue-history" role="log" aria-live="polite" aria-relevant="additions text">
      <p v-if="!history.length" class="empty-state">有什么想说的，就从第一句话开始吧。</p>
      <div v-for="(item, index) in history" :key="index" :class="['message', item.speaker]">
        <strong>{{ item.speakerName || (item.speaker === 'actor' ? game.player?.name : counterpartName) }}</strong>
        <p>{{ item.manualEnd ? '你准备结束这场交谈。' : item.text }}</p>
        <small v-if="item.staminaCost !== undefined" class="muted">本轮消耗 {{ item.staminaCost }} 点体力</small>
        <p v-if="numericChanges(item)" class="inline-note">本轮结算：{{ numericChanges(item) }}</p>
        <ul v-if="item.executedTrades?.length" class="trade-list"><li v-for="(trade, i) in item.executedTrades" :key="i">取得{{ trade.equipmentName }} ×{{ trade.quantity }}，花费 {{ trade.cost }} 文</li></ul>
      </div><div ref="bottom"></div>
    </div>
    <form v-if="!game.dialogue.ended" class="writing-form" @submit.prevent="send">
      <label class="sr-only" for="dialogue-text">对话内容</label><textarea id="dialogue-text" v-model="text" rows="3" maxlength="4000" :disabled="!game.canWrite" placeholder="说说你的想法……" required></textarea>
      <div class="form-bottom"><button type="button" class="text-button" :disabled="!game.canWrite" @click="game.sendDialogue('', true)">结束对话</button><button class="primary" :disabled="!game.canWrite || !!blockedReason || !text.trim()">发送 →</button></div>
      <p class="inline-note">本场已消耗 {{ paidCost }} 点体力，每轮回复后实时扣除。</p>
      <small class="muted">首轮收到回复后至少扣 {{ game.detail?.actionRules.dialogueMinStaminaCost }} 点体力；双方累计文字每 {{ game.detail?.actionRules.dialogueCharactersPerStamina }} 字约耗 1 点，向上取整，后续每轮只补扣差额。每轮立即结算数值，不占主要行动额度。</small>
      <p v-if="blockedReason" class="blocked-reason">{{ blockedReason }}</p>
      <small class="muted">结束对话不再扣体力或生成回复；收起面板保留对话，已扣体力、数值变化和交易不会撤销。每场最多五轮。</small>
    </form>
    <p v-else class="inline-note">这场对话已结束，本场已实时扣除 {{ paidCost }} 点体力，各轮实际变化已经保存。</p>
  </template>
  <p v-else class="empty-state">从当前地点的人物列表中，选择一位人物开始交谈。</p>
</template>
