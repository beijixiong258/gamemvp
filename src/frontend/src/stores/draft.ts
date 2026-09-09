import { ref, watch } from 'vue'
import { readLocal, writeLocal } from './game'
export function useDraft(storageKey: () => string) {
  const text = ref('')
  watch(storageKey, key => { text.value = readLocal<string>(key, '') }, { immediate: true })
  watch(text, value => writeLocal(storageKey(), value))
  return text
}
