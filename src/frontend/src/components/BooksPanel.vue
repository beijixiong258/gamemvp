<script setup lang="ts">
import { computed, ref } from 'vue'
import { useGameStore } from '../stores/game'
import { allows } from '../config/locations'
import type { Location } from '../config/locations'
import { assets } from '../config/assets'
import type { LibraryBook } from '../types/game'
const props = defineProps<{ location: Location }>()
const emit = defineEmits<{ go: [id: string]; reading: [] }>()
const game = useGameStore()
const filter = ref(props.location.id === 'bookshop' ? 'paid' : 'all')
const search = ref('')
const books = computed(() => (game.detail?.books ?? []).filter(b =>
  b.bookName.includes(search.value.trim()) && (filter.value === 'all' || filter.value === 'owned' && b.ownedQuantity > 0
    || filter.value === 'free' && b.price === 0 || filter.value === 'paid' && b.price > 0)))
function supplierPlace(book: LibraryBook) { return book.supplierNpcCode === 'NPC_XIANSHENG' ? 'classroom' : 'bookshop' }
async function reading(book: LibraryBook) {
  if (game.question?.bookCode === book.bookCode) { emit('reading'); return }
  await game.askReading(book.bookCode, props.location.sceneCode!)
  if (game.question) emit('reading')
}
</script>
<template>
  <div class="book-toolbar"><label class="search-field"><span class="sr-only">搜索书名</span><input v-model="search" type="search" placeholder="找一本书……" /></label>
    <div class="segmented" aria-label="书目筛选"><button v-for="tab in [{id:'all',label:'全部'}, {id:'owned',label:'已持有'}, {id:'free',label:'免费教材'}, {id:'paid',label:'书铺书目'}]" :key="tab.id" :class="{ active: filter === tab.id }" :aria-pressed="filter === tab.id" @click="filter = tab.id">{{ tab.label }}</button></div>
  </div>
  <p v-if="location.id === 'library'" class="inline-note">藏书阁可以查书、读书。免费教材请到讲堂向窦苞领取。</p>
  <p v-if="game.question" class="inline-note">《{{ game.question.bookName }}》的体会题已经备好。<button class="text-button" @click="emit('reading')">继续写体会</button></p>
  <div v-if="!books.length" class="empty-state"><p>没有找到符合条件的书。</p></div>
  <article v-for="book in books" :key="book.bookCode" class="book-card">
    <img :src="assets.book" alt="" class="book-image" loading="lazy" />
    <div class="book-body"><div class="book-title"><h3>{{ book.bookName }}</h3><span class="tag" :style="{ borderColor: book.rarityColor }">{{ book.rarityName }}</span><span v-if="book.completed" class="tag success">已完成</span></div>
      <p class="book-description">{{ book.knowledgeSummary }}</p>
      <div class="reading-progress"><progress :value="book.currentProgress" :max="Math.max(1, book.requiredProgress)" :aria-label="book.bookName + '阅读进度'"></progress><small>{{ book.currentProgress }} / {{ book.requiredProgress }}</small></div>
      <p class="book-meta">持有 {{ book.ownedQuantity }} 本 · 已获学识 {{ book.acquiredKnowledge }} / {{ book.totalKnowledge }}<span v-if="book.price"> · 售价 {{ book.price }} 文</span></p>
      <p v-if="book.blockedReasons.length" class="blocked-reason">{{ book.blockedReasons.join('；') }}</p>
      <div class="button-row">
        <template v-if="book.ownedQuantity === 0">
          <button v-if="location.id !== supplierPlace(book)" @click="emit('go', supplierPlace(book))">{{ book.price ? '去书铺购买' : '去讲堂领取' }} →</button>
          <button v-else class="primary" :disabled="!game.canWrite || game.sick || (game.player?.wallet ?? 0) < book.price" @click="game.acquire(book, location.sceneCode!)">{{ book.price ? '购买 · ' + book.price + ' 文' : '领取教材' }}</button>
        </template>
        <button v-else-if="book.completed" disabled>已完成</button>
        <template v-else>
          <button v-if="allows(location, 'READ_BOOK')" class="primary" :disabled="!game.canWrite || game.sick || !book.readable" @click="game.action('READ_BOOK', location.sceneCode!, book.bookCode)">读书 · 一回合</button>
          <button v-else @click="emit('go', 'study')">去书房读书 →</button>
          <button v-if="book.playerReadingEnabled && allows(location, 'READ_BOOK_PLAYER')" :disabled="!game.canWrite || game.sick || !book.readable" @click="reading(book)">以身入局 · 写体会</button>
          <button v-else-if="book.playerReadingEnabled" @click="emit('go', 'classroom')">去讲堂写体会 →</button>
        </template>
      </div>
    </div>
  </article>
</template>
