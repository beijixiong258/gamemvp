import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia } from 'pinia'

let server, useGameStore, pinia
const originalFetch = globalThis.fetch
const originalWindow = globalThis.window
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const saved = new Map()
const activeDialogue = { id: 'dialogue', saveId: 'save', version: 0, ended: false }
const detail = () => ({ save: { id: 'save', totalTurnNumber: 0, status: 'STUDYING' },
  character: { id: 'player', stamina: 50 }, books: [], exams: [], activeDialogue })

before(async () => {
  // 使用项目已有的 Vite 转译 TypeScript，不增加测试依赖或启动 HTTP 服务。
  server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, watch: null }, appType: 'custom', logLevel: 'error' })
  ;({ useGameStore } = await server.ssrLoadModule('/src/stores/game.ts'))
  globalThis.window = { setTimeout, clearTimeout }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value),
    removeItem: key => saved.delete(key) } })
})
beforeEach(() => { saved.clear(); pinia = createPinia() })
after(async () => {
  globalThis.fetch = originalFetch
  globalThis.window = originalWindow
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else delete globalThis.localStorage
  await server?.close()
})
function game() {
  const store = useGameStore(pinia)
  store.detail = detail()
  store.dialogue = { ...activeDialogue }
  return store
}

test('确认未结算的 AI 失败显示业务原因并解除操作锁', async () => {
  const store = game()
  globalThis.fetch = async () => Response.json({ status: 502, requestRejected: true,
    detail: 'AI行动处理失败，本次未结算，请稍后重试' }, { status: 502 })
  await store.sendDialogue('先生，这个字是什么意思？')
  assert.equal(store.error, 'AI行动处理失败，本次未结算，请稍后重试')
  assert.equal(store.pending, null)
  assert.equal(saved.has('mvp:save:pending'), false)
  assert.equal(store.canWrite, true)
  assert.equal(store.dialogue.ended, false)
})

for (const status of [502, 503, 408, 429]) {
  test(`结果未知的 ${status} 保留原请求，刷新也不误解除锁`, async () => {
    const store = game()
    globalThis.fetch = async (_path, options) => options.method === 'GET'
      ? Response.json(detail()) : Response.json({ status }, { status })
    await store.sendDialogue('先生好')
    const requestId = store.pending.body.requestId
    await store.refresh()
    assert.equal(store.pending.body.requestId, requestId)
    assert.equal(JSON.parse(saved.get('mvp:save:pending')).body.requestId, requestId)
    assert.equal(store.canWrite, false)
  })
}

test('响应丢失后重试复用请求编号，成功后恢复操作', async () => {
  const store = game()
  const requests = []
  globalThis.fetch = async (_path, options) => {
    if (options.method === 'GET') return Response.json(detail())
    requests.push(JSON.parse(options.body))
    if (requests.length === 1) throw new TypeError('connection lost')
    return Response.json({ dialogue: activeDialogue })
  }
  await store.sendDialogue('先生好')
  assert.equal(store.canWrite, false)
  await store.retry()
  assert.deepEqual(requests[1], requests[0])
  assert.equal(store.pending, null)
  assert.equal(store.canWrite, true)
})

test('体力不足的 409 显示具体原因并读取最新状态', async () => {
  const store = game()
  globalThis.fetch = async (_path, options) => options.method === 'GET'
    ? Response.json({ ...detail(), character: { id: 'player', stamina: 12 } })
    : Response.json({ detail: '你累了，先休息吧。当前体力不足以支付本轮交谈' }, { status: 409 })
  await store.sendDialogue('先生好')
  assert.match(store.error, /你累了/)
  assert.equal(store.player.stamina, 12)
  assert.equal(store.pending, null)
  assert.equal(store.canWrite, true)
})

test('已确认拒绝的新建请求不留下开局结果未知状态', async () => {
  const store = useGameStore(pinia)
  globalThis.fetch = async () => Response.json({ detail: 'AI结果未采用，请重试', requestRejected: true }, { status: 502 })
  assert.equal(await store.createLife('验收', '7'), null)
  assert.equal(store.creationUncertain, false)
  assert.equal(saved.has('mvp:creation-uncertain'), false)
})
