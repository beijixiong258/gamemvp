<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import RequestStatus from './RequestStatus.vue'
defineProps<{ title: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
let previous: HTMLElement | null = null
onMounted(async () => {
  previous = document.activeElement as HTMLElement
  await nextTick()
  dialog.value?.showModal()
  document.body.classList.add('modal-open')
})
onUnmounted(() => { document.body.classList.remove('modal-open'); previous?.focus() })
function outside(event: MouseEvent) { if (event.target === dialog.value) emit('close') }
</script>
<template>
  <dialog ref="dialog" :class="['modal', { wide }]" aria-labelledby="panel-title" @cancel.prevent="emit('close')" @click="outside">
    <div class="modal-inner">
      <header class="section-heading"><h2 id="panel-title">{{ title }}</h2><button class="icon-button" aria-label="收起面板" @click="emit('close')">×</button></header>
      <div class="modal-body"><slot /></div>
      <RequestStatus embedded />
    </div>
  </dialog>
</template>
