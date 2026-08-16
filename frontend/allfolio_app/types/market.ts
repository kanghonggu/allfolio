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
  /**
   * null = 플래그 off(서버가 안 실었다), [] = 켜져 있고 데이터 없음.
   * **지수와 같은 관례다** — `?? []`로 합치면 재배포 약관 때문에 감춘 탭이 빈 화면으로 노출된다.
   * 탭을 띄울지는 [MarketFlags.commoditiesEnabled]로 가른다.
   */
  commodities: CommodityQuoteView[] | null
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

/**
 * 원자재 한 종 (AF-108).
 *
 * **`unit`·`frequency`가 행에 실려 온다.** 코드로 매핑해 상수로 들고 있으면 설정이 바뀐 날
 * 저장은 멀쩡한데 화면만 조용히 틀린다 — `USD/lb`(우라늄)와 `USc/lb`(설탕·커피)는
 * 한 글자 차이에 100배 차이다. 섹션을 가르는 것도 소스 이름이 아니라 `frequency`로 한다
 * (금이 나중에 붙어도 화면 코드가 안 바뀐다).
 */
export interface CommodityQuoteView {
  code: string
  /** **월간 관측의 거래일은 그 달의 1일이다**(IMF 관측일 규약). 그 날 하루 값이 아니라 그 달의 평균이다 */
  tradeDate: string
  /** 백엔드는 BigDecimal(scale 4)이고 JSON에는 number로 실린다 — string이 아니다 */
  price: number
  /** USD/bbl · USD/MMBtu · USD/MT · USD/lb · USc/lb · index · KRW/g. 설정 표기 그대로다 */
  unit: string
  /**
   * 설정상 `D` | `M`. 화면이 「시세」·「월간 지표」 두 섹션을 가르는 근거다.
   *
   * **`'D' | 'M'` 유니언으로 좁히지 않는다.** 백엔드 필드가 `String`(length 1)이라 설정 오타
   * 한 번이면 `'d'`가 실려 오는데, 선언만 좁혀 봐야 런타임은 안 걸러진다 — AF-104가 그걸로
   * 사고를 냈다(선언은 string이 아니라고 말했지만 백엔드는 number를 보냈다).
   * 좁은 선언은 그 자체로 해롭기도 하다: `commoditySection`의 세 번째 갈래가 타입상 도달 불가로
   * 보여 죽은 코드처럼 읽히고, 지우고 싶어진다. 선언을 코드가 실제로 하는 방어에 맞춘다.
   */
  frequency: string
  /** null = 비교할 직전 값 없음. **0(무변동)과 다르다** — `!= null`로 가를 것 */
  changeValue: number | null
  /** % (직전 값이 0이면 계산 불가라 null). 0과 null은 다르다 */
  changeRate: number | null
}

export interface MarketFlags {
  indicesEnabled: boolean
  /** false면 서버가 원자재를 아예 안 싣는다. **탭 표시 여부는 이 값으로 가른다** */
  commoditiesEnabled: boolean
}
