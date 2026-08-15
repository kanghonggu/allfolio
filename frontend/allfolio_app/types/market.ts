/**
 * GET /api/market 응답 (AF-104).
 *
 * **숫자는 전부 JSON number다.** 백엔드는 BigDecimal 스케일을 의도적으로 정해
 * (`2.7500`, `-1.20`) 자릿수까지 담아 보내지만, `JSON.parse`가 JS number로 바꾸면서
 * 그 스케일을 버린다 — 받는 쪽에는 `2.75`, `-1.2`만 남고 되살릴 방법이 없다.
 * 그래서 **표시할 때 자릿수를 다시 고정해야 한다**: `lib/market-format.ts`의 `fixed()`를 쓸 것.
 * (전에 이 파일은 이 필드들을 string으로 선언했다. 런타임 타입은 타입스크립트가 검사하지 않아
 *  틀린 채로 빌드가 통과했고, 화면에는 `2.75 / 2.769 / 3.796`처럼 자릿수가 들쭉날쭉 나갔다.)
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
  price: number
  change: number
  changeRate: number
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
  baseRate: number
  /** 은행이 그 통화를 현찰·송금으로 취급 안 하면 null이다 — 0이 아니라 `-`로 그린다 */
  cashBuy: number | null
  cashSell: number | null
  remitSend: number | null
  remitReceive: number | null
  /** null = 직전 기준일에 그 통화가 없었다. **0과 다르다** — `x.change ?`로 갈라선 안 된다 */
  change: number | null
  changeRate: number | null
}

export interface RateView {
  code: string
  value: number
  /** **항목마다 다르다.** 기준금리 공표가 시장금리보다 이틀 늦다 */
  quoteDate: string
  /** bp다(%p 아님). null = 비교할 직전 값 없음. **0과 다르다** — `!= null`로 가를 것 */
  changeBp: number | null
}

export interface MarketFlags {
  indicesEnabled: boolean
}
