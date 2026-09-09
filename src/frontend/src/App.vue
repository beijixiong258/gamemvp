<script setup lang="ts">
import { onErrorCaptured, onMounted, onUnmounted, ref } from 'vue'
import RequestStatus from './components/RequestStatus.vue'
import { useGameStore } from './stores/game'
const game = useGameStore()
const renderError = ref(false)
onErrorCaptured(() => { renderError.value = true; return false })
function protectPending(event: BeforeUnloadEvent) {
  if (game.busy) { event.preventDefault(); event.returnValue = '' }
}
function focusMain() {
  const main = document.getElementById('main-content')
  main?.setAttribute('tabindex', '-1')
  main?.focus()
}
onMounted(() => window.addEventListener('beforeunload', protectPending))
onUnmounted(() => window.removeEventListener('beforeunload', protectPending))
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="focusMain">跳到主要内容</a>
  <div v-if="renderError" class="fatal paper">
    <h1>页面暂时没能展开</h1><p>请刷新页面，已经保存的进度仍在。</p>
    <a class="button primary" href="">重新打开</a>
  </div>
  <RouterView v-else />
  <RequestStatus />
</template>
