import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, request, segment } from '../api/http'
import { formatReadingReward } from '../config/reading'
import type { Dialogue, FamilyBackground, LibraryBook, OperationKind, OperationResult, PendingRequest,
  ReadingQuestion, ReadingReward, Region, SaveDetail, SaveSummary, SupplyOffer } from '../types/game'

export function readLocal<T>(key: string, fallback: T): T {
  try { const value = localStorage.getItem(key); return value ? JSON.parse(value) as T : fallback } catch { return fallback }
}
export function writeLocal(key: string, value: unknown): boolean {
  try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, JSON.stringify(value)); return true } catch { return false }
}
const key = (id: string, name: string) => 'mvp:' + id + ':' + name
const message = (error: unknown) => error instanceof Error ? error.message : '暂时无法完成，请稍后重试。'

export const useGameStore = defineStore('game', () => {
  const detail = ref<SaveDetail | null>(null)
  const saves = ref<SaveSummary[]>([])
  const regions = ref<Region[]>([])
  const dialogue = ref<Dialogue | null>(null)
  const question = ref<ReadingQuestion | null>(null)
  const pending = ref<PendingRequest | null>(null)
  const busy = ref(false)
  const loading = ref(false)
  const stale = ref(false)
  const error = ref('')
  const busyLabel = ref('')
  const outcome = ref<OperationResult | null>(null)
  const notice = ref('')
  const creationUncertain = ref(readLocal('mvp:creation-uncertain', false))
  let readEpoch = 0
  const canWrite = computed(() => !!detail.value && !busy.value && !loading.value && !pending.value && !stale.value)
  const player = computed(() => detail.value?.character)
  const readyExam = computed(() => detail.value?.exams.find(e => e.characterId === player.value?.id && e.status === 'READY'))
  const sick = computed(() => !!player.value && (player.value.characterJiankang <= 0 || player.value.sickTurnsRemaining > 0))
  const majorActionUsed = computed(() => !!detail.value && player.value?.majorActionTurn === detail.value.save.totalTurnNumber)
  const aiStaminaCost = computed(() => detail.value?.actionRules.aiStaminaCost ?? 0)
  const overworked = computed(() => !!player.value && !!detail.value
    && player.value.stamina <= detail.value.actionRules.overworkThreshold)
  function staminaBlockedReason(cost: number) {
    if (!detail.value) return '请先读取最新进度。'
    return detail.value.character.stamina < cost
      ? '体力不足：需要 ' + cost + ' 点，当前剩余 ' + detail.value.character.stamina + ' 点。' : ''
  }
  function mainActionBlockedReason(cost: number) {
    if (detail.value?.save.status !== 'STUDYING') return '请先完成当前考试。'
    if (sick.value) return '重病期间请结束回合进入强制休养。'
    if (majorActionUsed.value) return '本回合主要行动已使用，请结束回合后继续。'
    return staminaBlockedReason(cost)
  }
  const path = () => segment(detail.value!.save.id)
  const actor = () => segment(detail.value!.character.id)

  async function loadSaves() {
    const epoch = ++readEpoch
    loading.value = true; error.value = ''
    try {
      const fresh = await request<SaveSummary[]>('/save')
      if (epoch === readEpoch) saves.value = fresh
    } catch (e) { if (epoch === readEpoch) error.value = message(e) }
    finally { if (epoch === readEpoch) loading.value = false }
  }
  async function loadRegions() {
    try { regions.value = await request<Region[]>('/save/birth-regions') } catch (e) { error.value = message(e) }
  }
  function confirmCreationChecked() {
    creationUncertain.value = false; writeLocal('mvp:creation-uncertain', null); error.value = ''
  }
  async function createLife(characterName: string, birthRegionId: string): Promise<string | null> {
    if (busy.value || creationUncertain.value) return null
    busy.value = true; busyLabel.value = '正在准备新的人生'; error.value = ''
    if (!writeLocal('mvp:creation-uncertain', true)) {
      error.value = '浏览器无法保存请求状态，请允许本地存储后再开局。'; busy.value = false; return null
    }
    try {
      const result = await request<{ save: { id: string } }>('/save/start', 'POST', { characterName, birthRegionId })
      confirmCreationChecked()
      return result.save.id
    } catch (e) {
      creationUncertain.value = !(e instanceof ApiError && e.status >= 400 && e.status < 500
        && e.status !== 408 && e.status !== 429)
      writeLocal('mvp:creation-uncertain', creationUncertain.value || null)
      error.value = message(e)
      return null
    } finally { busy.value = false }
  }

  function restoreQuestion(id: string) {
    const saved = readLocal<ReadingQuestion | null>(key(id, 'question'), null)
    const book = detail.value?.books.find(book => book.bookCode === saved?.bookCode)
    question.value = saved?.turnNumber === detail.value?.save.totalTurnNumber && book?.readable && book.playerReadingEnabled ? saved : null
    if (!question.value) writeLocal(key(id, 'question'), null)
  }
  async function syncDetail(epoch: number) {
    if (!detail.value || epoch !== readEpoch) return
    const id = detail.value.save.id
    const fresh = await request<SaveDetail>('/save/' + segment(id))
    if (epoch !== readEpoch || detail.value?.save.id !== id) return
    detail.value = fresh; stale.value = false
    restoreQuestion(id)
    restoreDialogue(fresh)
  }
  function restoreDialogue(fresh: SaveDetail) {
    // 活跃会话只由服务端确认；浏览器缓存丢失或残留均不能决定会话是否存在。
    if (fresh.activeDialogue) dialogue.value = fresh.activeDialogue
    else if (!dialogue.value?.ended || dialogue.value.saveId !== fresh.save.id) dialogue.value = null
    // 当前页保留刚结束的交谈供查看，本地仅缓存仍活跃的会话 ID。
    writeLocal(key(fresh.save.id, 'dialogue'), fresh.activeDialogue?.id ?? null)
  }
  async function enter(id: string) {
    if (busy.value) return
    const epoch = ++readEpoch
    loading.value = true; error.value = ''; notice.value = ''; outcome.value = null
    detail.value = null; dialogue.value = null; question.value = null; stale.value = false
    const savedPending = readLocal<PendingRequest | null>(key(id, 'pending'), null)
    pending.value = savedPending?.saveId === id ? savedPending : null
    try {
      // 内容补齐同时核算旧阅读收益；后续详情使用补齐后的真实属性。
      const restored = await request<ReadingReward>('/save/' + segment(id) + '/content', 'POST')
      if (epoch !== readEpoch) return
      const fresh = await request<SaveDetail>('/save/' + segment(id))
      if (epoch !== readEpoch) return
      detail.value = fresh
      const restoredText = formatReadingReward(restored)
      if (restoredText) notice.value = '已补齐阅读成长：' + restoredText + '。'
      restoreQuestion(id)
      restoreDialogue(fresh)
    } catch (e) { if (epoch === readEpoch) { error.value = message(e); stale.value = true } }
    finally { if (epoch === readEpoch) loading.value = false }
  }
  async function refresh() {
    if (busy.value || loading.value) return
    if (!detail.value) return
    const epoch = ++readEpoch
    loading.value = true; error.value = ''
    try { await syncDetail(epoch) }
    catch (e) { if (epoch === readEpoch) { error.value = message(e); stale.value = true } }
    finally { if (epoch === readEpoch) loading.value = false }
  }
  function clearPending() {
    if (pending.value) writeLocal(key(pending.value.saveId, 'pending'), null)
    pending.value = null
  }
  async function retry() {
    if (!pending.value || busy.value || loading.value) return
    const operation = pending.value
    const epoch = ++readEpoch
    busy.value = true; busyLabel.value = operation.label; error.value = ''; notice.value = ''
    let result: unknown
    try {
      result = await request<unknown>(operation.path, 'POST', operation.body)
    } catch (e) {
      error.value = message(e)
      const status = e instanceof ApiError ? e.status : 0
      if (status >= 400 && status < 500 && status !== 408 && status !== 429) clearPending()
      if (status === 409) {
        try { await syncDetail(epoch) } catch { if (epoch === readEpoch) stale.value = true }
      }
      busy.value = false
      return
    }

    // POST 已确认完成；之后的读档失败只重读，不能重新创建一次写操作。
    clearPending()
    const data = (result ?? {}) as OperationResult
    outcome.value = data
    if (operation.kind === 'dialogue-start') dialogue.value = result as Dialogue
    if (data.dialogue) dialogue.value = data.dialogue
    if (dialogue.value) writeLocal(key(operation.saveId, 'dialogue'), dialogue.value.ended ? null : dialogue.value.id)
    if (operation.kind === 'question') {
      question.value = result as ReadingQuestion
      writeLocal(key(operation.saveId, 'question'), question.value)
    }
    if (operation.kind === 'answer') {
      question.value = null; writeLocal(key(operation.saveId, 'question'), null)
    }
    if (operation.kind === 'background' && detail.value) detail.value.familyBackground = result as FamilyBackground
    notice.value = data.feedback || [data.narrative, data.summary || data.applied?.summary].filter(Boolean).join('\n')
      || (operation.kind === 'acquire' ? '已取得' + data.equipmentName + ' ×' + data.quantity + '，花费 ' + data.cost + ' 文。'
        : operation.kind === 'question' ? '阅读题目已备好，请写下你的回答。'
        : operation.kind === 'background' ? '童年往事已记下。' : operation.kind === 'thought' ? '思路已整理好。' : '')
    try { await syncDetail(epoch) } catch (e) {
      if (epoch === readEpoch) {
        stale.value = true
        error.value = '操作已完成，但最新进度读取失败。请刷新进度后继续。' + message(e)
      }
    } finally { busy.value = false }
  }
  async function commit(kind: OperationKind, label: string, route: string, body?: Record<string, unknown>) {
    if (!canWrite.value || !detail.value) return
    const operation: PendingRequest = { kind, label, path: route, body, saveId: detail.value.save.id }
    if (!writeLocal(key(operation.saveId, 'pending'), operation)) {
      error.value = '浏览器无法保存请求状态，请允许本地存储后继续。'
      return
    }
    pending.value = JSON.parse(JSON.stringify(operation)) as PendingRequest
    await retry()
  }
  const freshId = () => crypto.randomUUID()
  function action(actionCode: string, sceneCode: string, bookCode?: string) {
    if (!detail.value) return
    return commit('action', '正在结算这次行动', '/turn/' + path() + '/action',
      { requestId: freshId(), actionCode, sceneCode, bookCode, expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function endTurn() {
    if (!detail.value || detail.value.save.status !== 'STUDYING') return
    return commit('end-turn', '正在结束回合', '/turn/' + path() + '/end-turn',
      { requestId: freshId(), expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function free(sceneCode: string, text: string) {
    if (!detail.value) return
    return commit('free', '正在回应你的行动', '/turn/' + path() + '/actor/' + actor() + '/free',
      { requestId: freshId(), sceneCode, text, expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function acquire(book: LibraryBook, sceneCode: string) {
    return commit('acquire', book.price ? '正在买书' : '正在领取教材', '/equipment/' + path() + '/' + actor() + '/acquire',
      { requestId: freshId(), sceneCode, supplierNpcCode: book.supplierNpcCode, equipmentCode: book.bookCode, quantity: 1 })
  }
  function acquireSupply(offer: SupplyOffer, sceneCode: string) {
    if (!detail.value) return
    return commit('acquire', '正在购买物品', '/equipment/' + path() + '/' + actor() + '/acquire',
      { requestId: freshId(), sceneCode, supplierNpcCode: offer.equipment.supplierNpcCode,
        equipmentCode: offer.equipment.equipmentCode, quantity: 1 })
  }
  function pickup(itemId: string, sceneCode: string) {
    if (!detail.value) return
    return commit('pickup', '正在拾取物品', '/equipment/' + path() + '/' + actor() + '/pickup',
      { requestId: freshId(), itemId, sceneCode, expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function useItem(itemId: string, sceneCode: string) {
    if (!detail.value) return
    return commit('use', '正在使用物品', '/equipment/' + path() + '/' + actor() + '/use',
      { requestId: freshId(), itemId, sceneCode, expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function startDialogue(counterpartId: string, sceneCode: string) {
    if (dialogue.value && !dialogue.value.ended) { notice.value = '请先结束当前对话，再与另一位人物交谈。'; return }
    return commit('dialogue-start', '正在开始交谈', '/dialogue/' + path() + '/actor/' + actor() + '/start',
      { requestId: freshId(), counterpartId, sceneCode })
  }
  function sendDialogue(text: string, endDialogue = false) {
    if (!dialogue.value || dialogue.value.ended) return
    return commit('dialogue-message', endDialogue ? '正在结束对话' : '对方正在回应',
      '/dialogue/' + path() + '/' + segment(dialogue.value.id) + '/message',
      { requestId: freshId(), text, expectedVersion: dialogue.value.version, endDialogue })
  }
  function abandonDialogue() {
    if (!dialogue.value || dialogue.value.ended) return
    return commit('dialogue-abandon', '正在离开对话',
      '/dialogue/' + path() + '/' + segment(dialogue.value.id) + '/abandon',
      { requestId: freshId(), expectedVersion: dialogue.value.version })
  }
  function askReading(bookCode: string, sceneCode: string) {
    if (!detail.value) return
    return commit('question', '塾师正在生成阅读题目', '/book/' + path() + '/' + segment(bookCode) + '/player/question',
      { sceneCode, expectedTurnNumber: detail.value.save.totalTurnNumber })
  }
  function answerReading(text: string) {
    if (!question.value) return
    return commit('answer', '塾师正在评阅你的阅读回答', '/book/' + path() + '/' + segment(question.value.bookCode) + '/player/answer',
      { questionId: question.value.questionId, text })
  }
  function thought(examId: string) { return commit('thought', '正在整理应考思路', '/exam/' + path() + '/' + segment(examId) + '/thought') }
  function submitExam(examId: string, text?: string) {
    return commit('exam', text === undefined ? '正在完成答卷' : '正在评阅答卷',
      '/exam/' + path() + '/' + segment(examId) + (text === undefined ? '/auto' : '/player'),
      text === undefined ? undefined : { requestId: freshId(), text })
  }
  function background() { return commit('background', '正在回想六岁以前的往事', '/save/' + path() + '/background') }

  return { detail, player, saves, regions, dialogue, question, pending, busy, loading, stale, error, busyLabel,
    notice, outcome, canWrite, readyExam, sick, majorActionUsed, aiStaminaCost, overworked, staminaBlockedReason, mainActionBlockedReason, creationUncertain, confirmCreationChecked,
    loadSaves, loadRegions, createLife, enter, refresh, retry, action, endTurn, free, acquire, acquireSupply, pickup, useItem,
    startDialogue, sendDialogue, abandonDialogue, askReading, answerReading, thought, submitExam, background }
})
