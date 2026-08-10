'use client'

/**
 * 문의에 함께 보낼 재현 정보를 모아 둔다 (AF-94).
 *
 * 사용자는 "여기 이상해요" 한 줄만 써도 재현이 가능해야 한다. 직전 API 에러와
 * 콘솔 에러가 실질적으로 스크린샷을 대체하므로, 첨부파일 없이도 조사할 수 있다.
 * 메모리에만 담고 아무 데도 저장하지 않는다 — 보내는 시점에만 요청에 실린다.
 */
const MAX_CONSOLE_ERRORS = 5

const consoleErrors: string[] = []
let lastApiError: string | null = null

function push(message: string) {
  consoleErrors.push(message.slice(0, 500))
  if (consoleErrors.length > MAX_CONSOLE_ERRORS) consoleErrors.shift()
}

/** 앱 전역에서 한 번만 — 콘솔 에러와 처리되지 않은 예외를 모은다 */
export function installDiagnostics() {
  if (typeof window === 'undefined') return
  if ((window as any).__allfolioDiagnostics) return
  ;(window as any).__allfolioDiagnostics = true

  const original = console.error
  console.error = (...args: unknown[]) => {
    push(args.map(String).join(' '))
    original(...args)
  }

  window.addEventListener('error', (e) => push(`${e.message} @ ${e.filename}:${e.lineno}`))
  window.addEventListener('unhandledrejection', (e) => push(`unhandled: ${String(e.reason)}`))
}

/** React Query 전역 에러 훅에서 호출 — 상태코드 + 엔드포인트만 남긴다 */
export function recordApiError(error: unknown) {
  const e = error as { response?: { status?: number; config?: { method?: string; url?: string } } }
  const status = e?.response?.status
  if (!status) return
  const method = e.response?.config?.method?.toUpperCase() ?? ''
  const url = e.response?.config?.url ?? ''
  lastApiError = `${status} ${method} ${url}`.trim().slice(0, 500)
}

export function collectDiagnostics() {
  return {
    pageUrl: typeof window === 'undefined' ? null : window.location.pathname + window.location.search,
    userAgent: typeof navigator === 'undefined' ? null : navigator.userAgent,
    viewport: typeof window === 'undefined' ? null : `${window.innerWidth}x${window.innerHeight}`,
    lastApiError,
    consoleErrors: [...consoleErrors],
  }
}
