export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); this.name = 'ApiError' }
}
export async function request<T>(path: string, method: 'GET' | 'POST' = 'GET', body?: unknown): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), method === 'GET' ? 20_000 : 180_000)
  try {
    const response = await fetch('/api' + path, {
      method, signal: controller.signal,
      headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
    const text = await response.text()
    let data: unknown
    try { data = text ? JSON.parse(text) : undefined } catch {
      if (response.ok) throw new ApiError(0, '返回内容无法识别，请确认后端已启动，并刷新核对操作结果。')
    }
    if (!response.ok) {
      const problem = data && typeof data === 'object' ? data as Record<string, unknown> : {}
      const reason = typeof problem.detail === 'string' ? problem.detail : typeof problem.message === 'string' ? problem.message : ''
      const fallback = response.status === 409 ? '游戏状态已变化，请读取最新进度后再操作。'
        : response.status === 502 ? '暂时没有收到完整回应，请保留原请求重试。'
        : response.status === 404 ? '没有找到对应的存档或记录。' : '这次操作未能完成，请稍后重试。'
      // 只显示简短中文业务说明，不把堆栈、HTML或内部英文异常展示给玩家。
      throw new ApiError(response.status, reason && reason.length < 220 && /[\u4e00-\u9fff]/.test(reason) && !/[<>]/.test(reason) ? reason : fallback)
    }
    return data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, controller.signal.aborted
      ? '等待已超时，操作结果还未确认。请重试原请求或刷新核对进度。'
      : '暂时连接不上游戏服务，请确认后端已启动。原输入和请求会保留。')
  } finally { window.clearTimeout(timer) }
}
export const segment = (value: string) => encodeURIComponent(value)
