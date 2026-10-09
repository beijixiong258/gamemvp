<script setup lang="ts">
import { computed } from 'vue'
import { useGameStore } from '../stores/game'
const game = useGameStore()
const attributes = computed(() => game.player ? [
  ['智力', game.player.characterZhili], ['道德', game.player.characterDaode], ['政治', game.player.characterZhengzhi],
  ['交际', game.player.characterJiaoji], ['体能', game.player.characterTineng],
] : [])
const abilities = computed(() => game.detail ? [
  ['识字', game.detail.scholarProfile.abilityShizi], ['经义', game.detail.scholarProfile.abilityJingyi],
  ['文章', game.detail.scholarProfile.abilityWenzhang], ['策论', game.detail.scholarProfile.abilityCelun],
  ['文学', game.detail.scholarProfile.abilityWenxue],
] : [])
const titles = computed(() => { try { return JSON.parse(game.player?.titlesJson || '[]') as string[] } catch { return [] } })
</script>
<template>
  <template v-if="game.detail && game.player">
    <div class="stats-hero"><div><span>总学识</span><strong>{{ game.detail.knowledgeTotal }}</strong></div><div><span>钱包</span><strong>{{ game.player.wallet }}<small> 文</small></strong></div></div>
    <h3>身心</h3>
    <dl class="stat-grid"><div><dt>健康</dt><dd>{{ game.player.characterJiankang }}</dd></div><div><dt>体力</dt><dd>{{ game.player.stamina }} / {{ game.player.maxStamina }}</dd></div></dl>
    <p v-if="game.overworked" class="blocked-reason">体力不高于 {{ game.detail.actionRules.overworkThreshold }} 点，已进入过劳状态。</p>
    <p class="fine-print">健康达到 50 点时不影响学习效率。休息最多恢复健康至 {{ game.detail.actionRules.restHealthCap }} 点，已有更高健康值保持不变。</p>
    <h3>通用属性</h3><dl class="stat-grid"><div v-for="[label, value] in attributes" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></div></dl>
    <h3>书生能力</h3><dl class="stat-grid"><div v-for="[label, value] in abilities" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></div></dl>
    <h3>身份</h3><p>{{ game.player.degree || '尚无功名' }} · {{ game.player.officialPosition || '尚未任职' }}</p>
    <p v-if="titles.length">{{ titles.join(' · ') }}</p>
  </template>
</template>
