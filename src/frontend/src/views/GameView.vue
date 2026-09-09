<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useGameStore, readLocal, writeLocal } from '../stores/game'
import { useDraft } from '../stores/draft'
import { assets, portrait } from '../config/assets'
import { allows, childrenOf, getLocation, getScene, locations, npcLocation, roleName } from '../config/locations'
import type { Character } from '../types/game'
import ModalPanel from '../components/ModalPanel.vue'
import CharacterStats from '../components/CharacterStats.vue'
import BooksPanel from '../components/BooksPanel.vue'
import ReadingPanel from '../components/ReadingPanel.vue'
import DialoguePanel from '../components/DialoguePanel.vue'
const game = useGameStore()
const route = useRoute()
const router = useRouter()
type Panel = 'stats' | 'books' | 'npcs' | 'places' | 'free' | 'backpack' | 'chronicle' | 'dialogue' | 'reading'
const panel = ref<Panel | null>(null)
const panelTitles: Record<Panel, string> = { stats: '人物小传', books: '书目与阅读', npcs: '此间人物', places: '县中去处', free: '你想做些什么', backpack: '随身行囊', chronicle: '人生记事', dialogue: '一席话', reading: '以身入局 · 读书体会' }
const location = computed(() => getLocation(typeof route.query.place === 'string' ? route.query.place : readLocal('mvp:' + route.params.saveId + ':place', 'home')))
const children = computed(() => childrenOf(location.value.id))
const parent = computed(() => locations.find(l => l.id === location.value.parentId))
const county = computed(() => game.regions.find(r => r.id === game.player?.currentRegionId)?.regionName ?? '故乡')
const placeName = computed(() => location.value.id === 'county' ? county.value + '城' : location.value.label)
const availableNpcs = computed(() => {
  const npcs = game.detail?.npcs.filter(n => n.enabled) ?? []
  if (location.value.sceneCode) return npcs.filter(n => getScene(location.value)?.availableNpcCode.includes(n.npcCode ?? ''))
  return npcs.filter(n => {
    const home = npcLocation(n)
    return home && (location.value.id === 'county' || home.parentId === location.value.id)
  })
})
const milestones = computed(() => [...(game.detail?.milestones ?? [])].reverse())
const freeRoom = ref('')
const freeText = useDraft(() => 'mvp:free-draft:' + route.params.saveId + ':' + location.value.id)
const freeLocations = locations.filter(l => allows(l, 'FREE_ACTION'))
const canAct = computed(() => game.canWrite && !game.sick && game.detail?.save.status === 'STUDYING')
const counterpart = computed(() => game.detail?.npcs.find(n => n.id === game.dialogue?.counterpartId))
onMounted(async () => { if (!game.regions.length) await game.loadRegions() })
watch(() => location.value.id, id => writeLocal('mvp:' + route.params.saveId + ':place', id), { immediate: true })
watch(() => game.question?.questionId, id => { if (id) panel.value = 'reading' })
watch(() => game.dialogue?.id, id => { if (id) panel.value = 'dialogue' })
async function go(id: string) {
  if (!locations.find(l => l.id === id && l.enabled) || game.busy) return
  panel.value = null
  await router.push({ name: 'game', params: { saveId: route.params.saveId }, query: { place: id } })
}
function openFree() { freeRoom.value = location.value.sceneCode ? location.value.id : ''; panel.value = 'free' }
async function talk(npc: Character) {
  const place = npcLocation(npc)
  if (!place || !canAct.value) return
  if (game.dialogue && !game.dialogue.ended) { panel.value = 'dialogue'; return }
  if (location.value.id !== place.id) await go(place.id)
  await game.startDialogue(npc.id, place.sceneCode!)
  panel.value = 'dialogue'
}
async function submitFree() {
  const place = freeLocations.find(l => l.id === freeRoom.value)
  if (!place?.sceneCode || !freeText.value.trim()) return
  const previous = game.outcome
  await game.free(place.sceneCode, freeText.value.trim())
  if (game.outcome !== previous) freeText.value = ''
}
</script>
<template>
  <main v-if="game.detail && game.player" id="main-content" class="game-page" :style="{ '--scene-image': 'url(' + location.image + ')' }">
    <header class="game-topline"><RouterLink to="/" class="back-light">← 存档</RouterLink>
      <span>{{ game.detail.save.currentYear }} 年 · {{ game.detail.save.currentMonth }} 月 · 第 {{ game.detail.save.turnInMonth }} 回合</span>
      <button class="text-button light" :disabled="game.busy || game.loading" @click="game.refresh()">{{ game.loading ? '正在读取…' : '刷新进度' }}</button>
    </header>
    <section class="player-hud paper" aria-label="人物状态">
      <button class="avatar-button" @click="panel = 'stats'"><img :src="assets.avatar" alt="打开人物详情" /></button>
      <div class="player-heading"><span class="eyebrow">求学岁月</span><button class="name-button" @click="panel = 'stats'">{{ game.player.name }}<small>{{ game.detail.save.age }} 岁</small></button><p>{{ county }} · {{ game.player.degree || '尚无功名' }}</p></div>
      <div class="hud-meters"><div><span>健康</span><meter min="0" max="100" :value="game.player.characterJiankang" aria-label="健康"></meter><b>{{ game.player.characterJiankang }}</b></div>
        <div><span>疲劳</span><meter min="0" max="100" :value="game.player.characterPilao" aria-label="疲劳"></meter><b>{{ game.player.characterPilao }}</b></div></div>
      <div class="hud-wallet"><span>钱囊 <b>{{ game.player.wallet }} 文</b></span><span>学识 <b>{{ game.detail.knowledgeTotal }}</b></span></div>
    </section>

    <nav class="location-nav paper" aria-label="地点导航">
      <span class="eyebrow">此刻所在</span><h1>{{ placeName }}</h1>
      <span v-if="parent" class="location-line">{{ parent.id === 'county' ? county + '城' : parent.label }}之内</span>
      <div class="location-links"><button v-for="child in children" :key="child.id" :disabled="!child.enabled || game.busy" @click="go(child.id)">
        <span>{{ child.label }}</span><small v-if="!child.enabled">尚未开放</small><span v-else aria-hidden="true">→</span>
      </button></div>
      <button v-if="parent" class="parent-link" :disabled="game.busy" @click="go(parent.id)">← 返回{{ parent.id === 'county' ? county + '城' : parent.label }}</button>
      <div class="nav-foot">地点切换不消耗回合</div>
    </nav>

    <section class="scene-content">
      <div class="scene-title"><span class="eyebrow light">{{ location.id === 'home' ? '灯火可亲' : '读书与日常' }}</span><h2>{{ placeName }}</h2><p>{{ location.description }}</p></div>
      <div class="scene-actions">
        <button v-if="location.id === 'home'" class="button-ivory" @click="panel = 'chronicle'">回看童年</button>
        <button v-if="allows(location, 'READ_BOOK') || location.id === 'bookshop'" class="button-ivory" @click="panel = 'books'">{{ location.id === 'bookshop' ? '看看书铺' : '打开书目' }}</button>
        <button v-if="allows(location, 'PRACTICE_WRITING')" class="button-ivory" :disabled="!canAct" @click="game.action('PRACTICE_WRITING', location.sceneCode!)">练习文章 · 一回合</button>
        <button v-if="allows(location, 'REST')" class="button-ivory" :disabled="!canAct" @click="game.action('REST', location.sceneCode!)">休息 · 一回合</button>
        <button v-if="game.question" class="button-ivory" @click="panel = 'reading'">继续写体会</button>
      </div>
      <div v-if="game.sick" class="scene-notice warning"><strong>身体需要休养</strong><p>休养会继续至康复或下一个考试节点。</p><button class="primary" :disabled="!game.canWrite" @click="game.action('REST', 'SCENE_JIA_WOSHI')">继续休养</button></div>
      <div v-if="game.dialogue && !game.dialogue.ended" class="conversation-reminder"><button @click="panel = 'dialogue'">继续与{{ counterpart?.name || '对方' }}交谈 · {{ game.dialogue.version }}/5 轮 →</button></div>
      <div v-if="game.notice" class="scene-notice" role="status"><span class="eyebrow">刚刚发生</span><p class="prose">{{ game.notice }}</p>
        <div v-if="game.outcome?.changes" class="change-tags"><span v-if="game.outcome.changes.progressGain">阅读 +{{ game.outcome.changes.progressGain }}</span><span>疲劳 {{ game.outcome.changes.fatigueChange > 0 ? '+' : '' }}{{ game.outcome.changes.fatigueChange }}</span><span>健康 {{ game.outcome.changes.healthChange > 0 ? '+' : '' }}{{ game.outcome.changes.healthChange }}</span></div>
      </div>
    </section>

    <footer class="game-dock">
      <div class="round-actions"><button @click="panel = 'npcs'"><span class="round-icon">人</span><span>人物列表</span></button><button @click="panel = 'places'"><span class="round-icon">路</span><span>其它地点</span></button></div>
      <div class="dock-small"><button @click="panel = 'backpack'">行囊</button><span>·</span><button @click="panel = 'chronicle'">记事</button><span>·</span><small>已行 {{ game.detail.save.totalTurnNumber }} 回合</small></div>
      <button class="free-action-button" @click="openFree"><span class="round-icon">行</span><span>自定义行动</span></button>
    </footer>

    <ModalPanel v-if="panel" :key="panel" :title="panelTitles[panel]" :wide="panel === 'books' || panel === 'reading'" @close="panel = null">
      <CharacterStats v-if="panel === 'stats'" />
      <BooksPanel v-else-if="panel === 'books'" :location="location" @go="go" @reading="panel = 'reading'" />
      <ReadingPanel v-else-if="panel === 'reading'" />
      <DialoguePanel v-else-if="panel === 'dialogue'" />
      <template v-else-if="panel === 'npcs'">
        <p class="muted">{{ location.sceneCode ? '当前房间中的人物。' : '选择人物，会先进入对方所在的房间。' }}</p>
        <div v-if="!availableNpcs.length" class="empty-state"><p>这里没有常驻人物。</p></div>
        <button v-for="npc in availableNpcs" :key="npc.id" class="npc-card" :disabled="!canAct" @click="talk(npc)">
          <img :src="portrait(npc.npcCode)" :alt="npc.name" /><span><strong>{{ npc.name }}</strong><small>{{ roleName(npc.npcCode) }} · {{ npcLocation(npc)?.label }}</small></span><span aria-hidden="true">交谈 →</span>
        </button>
      </template>
      <div v-else-if="panel === 'places'" class="places-grid"><button v-for="place in locations" :key="place.id" :class="{ selected: location.id === place.id }" :disabled="!place.enabled || game.busy" @click="go(place.id)"><strong>{{ place.id === 'county' ? county + '城' : place.label }}</strong><small>{{ place.enabled ? locations.find(l => l.id === place.parentId)?.label || '地方总览' : '尚未开放' }}</small></button></div>
      <form v-else-if="panel === 'free'" class="writing-form" @submit.prevent="submitFree">
        <label class="field">行动发生在哪里<select v-model="freeRoom" :disabled="!canAct" required><option disabled value="">请选择具体房间或书铺</option><option v-for="place in freeLocations" :key="place.id" :value="place.id">{{ locations.find(l => l.id === place.parentId)?.label }} · {{ place.label }}</option></select></label>
        <label class="field">你想做些什么<textarea v-model="freeText" rows="7" maxlength="8000" :disabled="!canAct" placeholder="用自己的话描述一件想做的事……" required></textarea></label>
        <div class="form-bottom"><small>{{ freeText.length }} / 8000 · 完成后消耗一回合</small><button class="primary" :disabled="!canAct || !freeRoom || !freeText.trim()">付诸行动 →</button></div>
      </form>
      <template v-else-if="panel === 'backpack'">
        <div v-if="!game.detail.backpack.length" class="empty-state"><span class="empty-glyph">囊</span><p>行囊尚空。先到讲堂领取教材吧。</p><button @click="go('classroom')">前往讲堂 →</button></div>
        <div v-for="item in game.detail.backpack" :key="item.equipment.id" class="inventory-row"><img :src="assets.book" alt="" loading="lazy" /><div><h3>{{ item.equipment.equipmentName }} <small>×{{ item.quantity }}</small></h3><p>{{ item.equipment.description }}</p><span class="tag">{{ item.equipment.rarityName }}</span></div></div>
      </template>
      <template v-else-if="panel === 'chronicle'">
        <span class="eyebrow">六岁以前</span><p class="prose">{{ game.detail.familyBackground.backgroundSummary }}</p>
        <button :disabled="!game.canWrite" @click="game.background()">回想童年往事</button>
        <p class="fine-print">已经写下的童年往事会直接复用。</p>
        <h3 class="timeline-title">一路走来</h3><ol class="timeline"><li v-for="event in milestones" :key="event.id"><small>第 {{ event.occurredTurnNumber }} 回合</small><p>{{ event.eventSummary }}</p></li></ol><p v-if="!milestones.length" class="muted">往后的故事，还等着你来写。</p>
      </template>
    </ModalPanel>
  </main>
</template>
