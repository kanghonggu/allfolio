// 경량 토스트 pub/sub — 외부 의존성 없이 전역 에러 피드백 (QA P1 #12).
// queryClient(모듈 싱글턴)에서도 호출할 수 있도록 React 밖에 둔다.

export interface ToastItem {
  id: number
  kind: 'error' | 'info'
  message: string
}

type Listener = (toasts: ToastItem[]) => void

let seq = 0
let toasts: ToastItem[] = []
const listeners = new Set<Listener>()
const AUTO_DISMISS_MS = 5_000

function emit() {
  listeners.forEach(l => l(toasts))
}

function push(kind: ToastItem['kind'], message: string) {
  // 같은 메시지가 이미 떠 있으면 중복 표시하지 않는다 (연쇄 실패 노이즈 방지)
  if (toasts.some(t => t.message === message)) return
  const item: ToastItem = { id: ++seq, kind, message }
  toasts = [...toasts, item]
  emit()
  setTimeout(() => dismiss(item.id), AUTO_DISMISS_MS)
}

export function dismiss(id: number) {
  if (!toasts.some(t => t.id === id)) return
  toasts = toasts.filter(t => t.id !== id)
  emit()
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  listener(toasts)
  return () => { listeners.delete(listener) }
}

export const toast = {
  error: (message: string) => push('error', message),
  info: (message: string) => push('info', message),
}

/** axios/fetch 에러에서 사용자에게 보여줄 메시지 추출 */
export function errorMessage(e: unknown, fallback = '요청에 실패했습니다. 잠시 후 다시 시도해주세요.'): string {
  const err = e as { response?: { data?: { error?: string; message?: string } }; message?: string }
  return err?.response?.data?.error ?? err?.response?.data?.message ?? fallback
}
