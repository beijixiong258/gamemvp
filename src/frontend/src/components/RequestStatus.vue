<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useGameStore } from '../stores/game'
defineProps<{ embedded?: boolean }>()
const game = useGameStore()
const route = useRoute()
const inSave = computed(() => typeof route.params.saveId === 'string')
</script>

<template>
  <aside v-if="game.busy || game.error || (inSave && (game.pending || game.stale))"
    :class="embedded ? 'panel-status' : game.busy ? 'busy-indicator' : 'request-banner'"
    :role="game.busy ? 'status' : 'alert'">
    <template v-if="game.busy">
      <span class="spinner" aria-hidden="true"></span>
      <span>{{ game.busyLabel }}<template v-if="embedded">，请稍候。</template>
        <small v-else>请稍候，结果确认后会更新。</small>
      </span>
    </template>
    <template v-else>
      <p>{{ game.error || '有一项操作尚未确认，请重试原请求或刷新核对进度。' }}</p>
      <div class="button-row">
        <button v-if="inSave && game.pending" :disabled="game.loading" @click="game.retry()">重试原请求</button>
        <button v-if="inSave && game.detail" :disabled="game.loading" @click="game.refresh()">刷新进度</button>
        <button v-if="!game.pending && !game.stale" @click="game.error = ''">收起提示</button>
      </div>
    </template>
  </aside>
</template>
