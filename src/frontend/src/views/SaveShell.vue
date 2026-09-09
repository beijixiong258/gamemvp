<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useGameStore } from '../stores/game'
const game = useGameStore()
const route = useRoute()
const router = useRouter()
watch(() => String(route.params.saveId ?? ''), id => { if (id) void game.enter(id) }, { immediate: true })
watch(() => [game.loading, game.busy, game.detail?.save.status, game.readyExam?.id, route.fullPath], () => {
  if (game.loading || game.busy || !game.detail || game.detail.save.id !== route.params.saveId) return
  const params = { saveId: game.detail.save.id }
  if (game.detail.save.status === 'COMPLETED' && route.name !== 'result') {
    void router.replace({ name: 'result', params }); return
  }
  if (game.readyExam && (route.name !== 'exam' || route.params.examId !== game.readyExam.id)) {
    void router.replace({ name: 'exam', params: { ...params, examId: game.readyExam.id } }); return
  }
  if (route.name === 'result' && game.detail.save.status !== 'COMPLETED') void router.replace({ name: 'game', params })
}, { flush: 'post' })
</script>
<template>
  <main v-if="game.loading && !game.detail" id="main-content" class="loading-page">
    <span class="seal">卷</span><h1>翻开这段人生</h1><span class="spinner"></span><p>正在读取人物、书籍与经历……</p>
  </main>
  <main v-else-if="!game.detail" id="main-content" class="loading-page">
    <h1>暂时没能读到这段人生</h1><p>请确认后端与数据库已启动，再重新读取。</p>
    <div class="button-row"><button class="primary" :disabled="game.loading" @click="game.enter(String(route.params.saveId))">重新读取</button><RouterLink class="button" to="/">返回存档</RouterLink></div>
  </main>
  <RouterView v-else />
</template>
