<script setup lang="ts">
import { computed, ref } from 'vue'
import { useGameStore } from '../stores/game'
import type { Location } from '../config/locations'
import BookCard from './BookCard.vue'
const props = defineProps<{ location: Location }>()
const emit = defineEmits<{ go: [id: string]; reading: [] }>()
const game = useGameStore()
const filter = ref(props.location.id === 'bookshop' ? 'paid' : 'all')
const search = ref('')
const books = computed(() => (game.detail?.books ?? []).filter(b =>
  b.bookName.includes(search.value.trim()) && (filter.value === 'all' || filter.value === 'owned' && b.ownedQuantity > 0
    || filter.value === 'free' && b.price === 0 || filter.value === 'paid' && b.price > 0)))
</script>
<template>
  <div class="book-toolbar"><label class="search-field"><span class="sr-only">搜索书名</span><input v-model="search" type="search" placeholder="找一本书……" /></label>
    <div class="segmented" aria-label="书目筛选"><button v-for="tab in [{id:'all',label:'全部'}, {id:'owned',label:'已持有'}, {id:'free',label:'免费教材'}, {id:'paid',label:'书铺书目'}]" :key="tab.id" :class="{ active: filter === tab.id }" :aria-pressed="filter === tab.id" @click="filter = tab.id">{{ tab.label }}</button></div>
  </div>
  <p v-if="location.id === 'library'" class="inline-note">藏书阁可以查书、读书。免费教材请到讲堂向窦苞领取。</p>
  <p v-if="game.question" class="inline-note">《{{ game.question.bookName }}》的体会题已经备好。<button class="text-button" @click="emit('reading')">继续写体会</button></p>
  <div v-if="!books.length" class="empty-state"><p>没有找到符合条件的书。</p></div>
  <BookCard v-for="book in books" :key="book.bookCode" :book="book" :location="location" @go="emit('go', $event)" @reading="emit('reading')" />
</template>