<script setup lang="ts">
import { computed } from 'vue'
import { useGameStore } from '../stores/game'
import { allows } from '../config/locations'
import type { Location } from '../config/locations'
import { assets } from '../config/assets'
import { formatReadingReward } from '../config/reading'
import type { LibraryBook } from '../types/game'
const props = defineProps<{ book: LibraryBook; location: Location }>()
const emit = defineEmits<{ go: [id: string]; reading: [] }>()
const game = useGameStore()
const status = computed(() => props.book.completed ? '已完成' : props.book.readable ? '可阅读' : '条件不足')
const canAct = computed(() => game.canWrite && !game.sick && game.detail?.save.status === 'STUDYING')
const questionReady = computed(() => game.question?.bookCode === props.book.bookCode)
const readReason = computed(() => game.mainActionBlockedReason(props.book.fatigueCost))
const readingReason = computed(() => questionReady.value ? ''
  : game.mainActionBlockedReason(props.book.fatigueCost))
const supplierPlace = computed(() => props.book.supplierNpcCode === 'NPC_DOUBAO' ? 'classroom' : 'bookshop')
async function read() {
  if (!canAct.value || readReason.value || !props.book.readable || props.book.completed || !allows(props.location, 'READ_BOOK')) return
  await game.action('READ_BOOK', props.location.sceneCode!, props.book.bookCode)
}
async function reading() {
  if (!canAct.value || readingReason.value || !props.book.readable || props.book.completed || !props.book.playerReadingEnabled
    || !allows(props.location, 'READ_BOOK_PLAYER')) return
  if (game.question?.bookCode === props.book.bookCode) { emit('reading'); return }
  await game.askReading(props.book.bookCode, props.location.sceneCode!)
  if (game.question?.bookCode === props.book.bookCode) emit('reading')
}
</script>
<template>
  <article class="book-card">
    <img :src="assets.book" alt="" class="book-image" loading="lazy" />
    <div class="book-body">
      <div class="book-title"><h3>{{ book.bookName }}</h3><span class="tag" :style="{ borderColor: book.rarityColor }">{{ book.rarityName }}</span><span class="tag" :class="{ success: book.completed || book.readable }">{{ status }}</span></div>
      <p class="book-description">{{ book.knowledgeSummary }}</p>
      <div class="reading-progress"><progress :value="book.currentProgress" :max="Math.max(1, book.requiredProgress)" :aria-label="book.bookName + '阅读进度'"></progress><small>{{ book.currentProgress }} / {{ book.requiredProgress }}</small></div>
      <p class="book-meta">持有 {{ book.ownedQuantity }} 本 · 已获学识 {{ book.acquiredKnowledge }} / {{ book.totalKnowledge }}<span v-if="book.price"> · 售价 {{ book.price }} 文</span></p>
      <p class="book-meta">读满成长：{{ formatReadingReward(book.readingReward) || '无' }}。随阅读进度发放，各项上限 100。</p>
      <p class="book-meta">阅读：基础疲劳消耗 {{ book.fatigueCost }} 点体力，占用本回合主要行动额度。</p>
      <p v-if="book.playerReadingEnabled && !book.completed" class="book-meta">阅读（手动）：生成阅读题目不消耗体力；提交阅读回答消耗 {{ book.fatigueCost }} 点体力并占用主要行动额度。出题前须留足本书阅读体力，题目仅本回合有效。</p>
      <p v-if="!book.completed && book.blockedReasons.length" class="blocked-reason">{{ book.blockedReasons.join('；') }}</p>
      <p v-if="!book.completed && !book.playerReadingEnabled" class="book-meta">本书不支持阅读（手动）。</p>
      <div class="button-row">
        <button v-if="book.completed" disabled>已完成</button>
        <template v-else-if="book.ownedQuantity === 0">
          <button v-if="location.id !== supplierPlace" :disabled="!canAct" @click="emit('go', supplierPlace)">{{ book.price ? '去书铺购买' : '去讲堂领取' }} →</button>
          <button v-else class="primary" :disabled="!canAct || (game.player?.wallet ?? 0) < book.price" @click="game.acquire(book, location.sceneCode!)">{{ book.price ? '购买 · ' + book.price + ' 文' : '领取教材' }}</button>
        </template>
        <button v-else-if="!book.readable" disabled>条件不足</button>
        <template v-else>
          <button v-if="allows(location, 'READ_BOOK')" class="primary" :disabled="!canAct || !!readReason" :title="readReason" @click="read">阅读 · {{ book.fatigueCost }} 体力</button>
          <button v-else :disabled="!canAct" @click="emit('go', 'study')">去书房阅读 →</button>
          <button v-if="book.playerReadingEnabled && allows(location, 'READ_BOOK_PLAYER')" :disabled="!canAct || !!readingReason" :title="readingReason" @click="reading">{{ questionReady ? '继续阅读（手动）' : '阅读（手动）' }}</button>
          <button v-else-if="book.playerReadingEnabled" :disabled="!canAct" @click="emit('go', 'classroom')">去讲堂阅读（手动） →</button>
        </template>
      </div>
      <p v-if="book.ownedQuantity > 0 && book.readable && !book.completed && readReason" class="blocked-reason">阅读：{{ readReason }}</p>
      <p v-if="book.ownedQuantity > 0 && book.readable && !book.completed && book.playerReadingEnabled && readingReason && readingReason !== readReason" class="blocked-reason">阅读（手动）：{{ readingReason }}</p>
    </div>
  </article>
</template>