<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useGameStore } from '../stores/game'
import { useDraft } from '../stores/draft'
import { portrait } from '../config/assets'
import type { DialogueMessage } from '../types/game'
const game = useGameStore()
const text = useDraft(() => 'mvp:dialogue-draft:' + (game.dialogue?.id ?? 'none'))
const counterpart = computed(() => game.detail?.npcs.find(n => n.id === game.dialogue?.counterpartId))
const history = computed<DialogueMessage[]>(() => { try { return JSON.parse(game.dialogue?.messagesJson || '[]') } catch { return [] } })
const bottom = ref<HTMLElement>()
watch(() => game.dialogue?.version, async () => {
  const lastActorMessage = [...history.value].reverse().find(item => item.speaker === 'actor' && !item.manualEnd)
  if (lastActorMessage?.text === text.value.trim()) text.value = ''
  await nextTick(); bottom.value?.scrollIntoView({ block: 'nearest' })
})
async function send() {
  if (!text.value.trim()) return
  await game.sendDialogue(text.value.trim())
}
</script>
<template>
  <template v-if="game.dialogue">
    <div class="dialogue-person"><img :src="portrait(counterpart?.npcCode)" :alt="counterpart?.name ?? '对话人物'" /><div><h3>{{ counterpart?.name ?? '对方' }}</h3><p>{{ game.dialogue.ended ? '这场交谈已经结束' : '正在交谈' }} · {{ game.dialogue.version }} / 5 轮</p></div></div>
    <div class="dialogue-history" role="log" aria-live="polite" aria-relevant="additions text">
      <p v-if="!history.length" class="empty-state">有什么想说的，就从第一句话开始吧。</p>
      <div v-for="(item, index) in history" :key="index" :class="['message', item.speaker]">
        <strong>{{ item.speaker === 'actor' ? game.player?.name : counterpart?.name }}</strong>
        <p>{{ item.manualEnd ? '你准备结束这场交谈。' : item.text }}</p>
        <ul v-if="item.executedTrades?.length" class="trade-list"><li v-for="(trade, i) in item.executedTrades" :key="i">取得《{{ trade.equipmentName }}》×{{ trade.quantity }}，花费 {{ trade.cost }} 文</li></ul>
      </div><div ref="bottom"></div>
    </div>
    <form v-if="!game.dialogue.ended" class="writing-form" @submit.prevent="send">
      <label class="sr-only" for="dialogue-text">对话内容</label><textarea id="dialogue-text" v-model="text" rows="3" maxlength="4000" :disabled="!game.canWrite" placeholder="说说你的想法……" required></textarea>
      <div class="form-bottom"><button type="button" class="text-button" :disabled="!game.canWrite" @click="game.sendDialogue('', true)">结束对话</button><button class="primary" :disabled="!game.canWrite || !text.trim()">发送 →</button></div>
      <small class="muted">收起面板会保留对话。每场最多五轮，也可能自然提前结束。</small>
    </form>
    <p v-else class="inline-note">这场对话已结束，实际变化已经保存。</p>
  </template>
  <p v-else class="empty-state">从当前地点的人物列表中，选择一位人物开始交谈。</p>
</template>
