// 리디자인 공통 숫자 포맷 — 시안 문법: 모노스페이스 · tabular-nums · 명시적 부호 · U+2212 마이너스
// 기존 lib/report-format.ts(fmtPct=×100, fmtPctScaled=사전스케일)와 스케일 규약이 다르므로 혼용 주의:
// 여기의 pct 계열은 전부 "이미 0~100으로 스케일된 값"을 받는다.

export type PnlTone = 'gain' | 'loss' | 'flat'

const MINUS = '−'

export function won(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const sign = n < 0 ? MINUS : ''
  return `${sign}₩${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

export function wonPlain(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const sign = n < 0 ? MINUS : ''
  return `${sign}${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

export function signWon(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : MINUS}₩${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

/**
 * 통화 금액. **`Intl.NumberFormat`에 통화 코드를 그대로 넘기지 말 것** — 코드가 well-formed가
 * 아니면 생성자가 `RangeError`를 던지고, 렌더 중이면 화면 전체가 죽는다(AF-158: 바이낸스 계좌
 * 상세가 `Application error`로 바뀌었다).
 *
 * 실측 (node, 2026-09-02):
 *
 * | 코드 | 결과 |
 * |---|---|
 * | `KRW` `USD` | `₩1,235` · `US$1,234.50` |
 * | `BTC` `ETH` | `BTC 1,234.50` — **안 던진다.** 3글자 알파벳이라 well-formed이고 기호 대신 코드를 쓴다 |
 * | `USDT` | **`RangeError: Invalid currency code`** — 4글자라 걸린다 |
 *
 * 즉 지원 통화 5종([[SUPPORTED_CURRENCIES]]) 중 실제로 던지는 건 `USDT` 하나다. 그래도
 * **`USDT`만 예외 처리하지 않는다** — 규칙이 "ISO 4217이냐"가 아니라 "Intl이 받느냐"라서,
 * 통화가 하나 늘 때마다 다시 조사하지 않으려면 물어보는 편이 낫다.
 *
 * 폴백 표기는 `USDT 1,234.50`으로, Intl이 `BTC`·`ETH`를 내는 모양과 맞춘다 — 같은 화면에
 * 두 관례가 섞이지 않게.
 */
export function money(n: number | null | undefined, currency = 'KRW'): string {
  if (n === null || n === undefined) return '—'

  if (!acceptsCurrency(currency)) {
    // 숫자만 내면 무슨 돈인지 사라진다. 기호가 없는 통화라 코드를 반드시 붙인다.
    return `${currency} ${n.toLocaleString('ko-KR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`
  }

  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency,
    // KRW는 정수, 그 외는 소수 2자리 — `US$0` 같은 과반올림 방지 (QA P2)
    maximumFractionDigits: currency === 'KRW' ? 0 : 2,
  }).format(n)
}

/** 생성자가 던지는지로 판별한다. 화이트리스트로 적으면 통화가 늘 때마다 여기도 고쳐야 한다 */
const currencySupport = new Map<string, boolean>()

function acceptsCurrency(code: string): boolean {
  const cached = currencySupport.get(code)
  if (cached !== undefined) return cached

  let ok = true
  try {
    new Intl.NumberFormat('ko-KR', { style: 'currency', currency: code })
  } catch {
    ok = false
  }
  currencySupport.set(code, ok)
  return ok
}

/** 이미 0~100 스케일된 퍼센트 (returnRate, unrealizedPnlPct 등) */
export function signPct(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : MINUS}${Math.abs(n).toFixed(digits)}%`
}

export function pct(n: number | null | undefined, digits = 1): string {
  if (n === null || n === undefined) return '—'
  return `${n.toFixed(digits)}%`
}

/** 손익 방향 → 톤. 한국 관례: 상승 빨강(gain) / 하락 파랑(loss) */
export function dirTone(n: number | null | undefined): PnlTone {
  if (n === null || n === undefined || n === 0) return 'flat'
  return n > 0 ? 'gain' : 'loss'
}

export const toneText: Record<PnlTone, string> = {
  gain: 'text-gain',
  loss: 'text-loss',
  flat: 'text-fg-3',
}
