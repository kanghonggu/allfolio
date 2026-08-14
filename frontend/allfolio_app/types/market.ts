/**
 * GET /api/market 응답 (AF-104).
 *
 * **숫자가 전부 string이다.** 백엔드가 BigDecimal을 스케일 보존해서 보내므로
 * (`"2.7500"`, `"-1.20"`) number로 받으면 자릿수가 사라진다. 표시용으로는 그대로 쓰고,
 * 계산이 필요할 때만 Number()로 바꾼다.
 */
export interface MarketSnapshot {
  /** null = 플래그 off(서버가 안 실었다), [] = 켜져 있고 데이터 없음 */
  domestic: IndexQuoteView[] | null
  overseas: IndexQuoteView[] | null
  /** null = 데이터 없음 */
  fx: FxSnapshot | null
  rates: RateView[]
  flags: MarketFlags
}

export interface IndexQuoteView {
  code: string
  price: string
  change: string
  changeRate: string
  /** 장중 | 장마감 | 개장전 */
  marketStatus: string
  tradeDate: string
  /** OPEN | MID | CLOSE */
  slot: string
}

export interface FxSnapshot {
  baseDate: string
  roundNo: number
  /** **UTC다.** new Date()로 읽으면 로컬로 해석돼 KST 사용자에게 9시간 이르게 보인다 */
  collectedAt: string
  quotes: FxQuoteView[]
}

export interface FxQuoteView {
  currency: string
  baseRate: string
  /** 은행이 그 통화를 현찰·송금으로 취급 안 하면 null이다 — 0이 아니라 `-`로 그린다 */
  cashBuy: string | null
  cashSell: string | null
  remitSend: string | null
  remitReceive: string | null
  /** null = 직전 기준일에 그 통화가 없었다 */
  change: string | null
  changeRate: string | null
}

export interface RateView {
  code: string
  value: string
  /** **항목마다 다르다.** 기준금리 공표가 시장금리보다 이틀 늦다 */
  quoteDate: string
  /** bp다(%p 아님). null = 비교할 직전 값 없음 */
  changeBp: string | null
}

export interface MarketFlags {
  indicesEnabled: boolean
}
