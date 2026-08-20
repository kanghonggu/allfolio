/**
 * GET /api/disclosures 응답 (D1 / S12).
 *
 * 날짜 필드(`rceptDt`·`reportDate`)는 ISO date 문자열이다 — `types/market.ts`의
 * `tradeDate`·`quoteDate`·`baseDate`와 같은 관례(Spring Boot 기본 Jackson 설정은
 * `LocalDate`를 타임스탬프 배열이 아니라 `"2026-08-18"` 문자열로 직렬화한다). 이 레포에
 * 그 관례를 깨는 타입 파일이 없어 그대로 따른다.
 *
 * **`ownedRate`·`changeRate`는 JSON number다.** 백엔드가 `NUMERIC(7,2)`로 스케일을
 * 정해 보내지만 `JSON.parse`가 뒤 0을 버린다(`0.05` → `0.05`, `46.00` → `46`).
 * 표시할 때 자릿수를 다시 고정할 것 — `types/market.ts`가 같은 함정을 적어 두고 있다.
 *
 * **`0.00`은 "변동 없음"이 아니다.** 지분율 0.005% 미만이 반올림된 것이다
 * (실측 `elestock` 3,922행 중 6행이 소수 3자리 이상). 수량 필드(`changeQty`)가 진실을
 * 들고 있다.
 */
export interface DisclosureFeed {
  items: DisclosureItem[]
  insiderTrades: InsiderTradeItem[]
  /** 보유 종목 수. 0이면 "계좌를 연결하세요", 0이 아닌데 items가 비면 "공시가 없습니다" */
  heldCount: number
}

export interface DisclosureItem {
  rceptNo: string
  corpName: string
  stockCode: string | null
  /** 원문 그대로 — 정규화 전. 화면은 이것을 보여준다 */
  reportNm: string
  /** 제출인. Tier 4는 임원 이름, 나머지는 회사 자신 */
  flrNm: string | null
  /** ISO date (`2026-08-18`) */
  rceptDt: string
  /** 1~5. null이면 화이트리스트 미해당인데, 피드에는 is_material만 오므로 실제로는 안 온다 */
  materialTier: number | null
  isCorrection: boolean
  sourceUrl: string
  /** 정정으로 접힌 이전 건 수. 0이면 접히지 않음 */
  supersededCount: number
}

/**
 * 임원·주요주주 소유변동(elestock) 한 건.
 *
 * **매수·매도를 말하지 않는다.** `elestock`에 변동사유 필드가 없어(30개사 3,922행 전건 확인)
 * 무상증자·스톡옵션 행사·상속과 장내매수를 구분할 수 없다. 이 타입에 `changeType` 같은
 * 필드를 추가하지 말 것 — 채울 소스가 없다.
 */
export interface InsiderTradeItem {
  rceptNo: string
  stockCode: string | null
  /** 보고자. OpenDART의 실제 필드명이 `repror`다 — 오타가 아니다 */
  repror: string
  officerPosition: string | null
  /**
   * 등기임원 true / 비등기임원 false / 결측 null. **3-값이다.**
   * 실측(`isu_exctv_rgist_at`, 3,922행): 비등기임원 3,574 · 등기임원 223 · 결측(`-`) 125.
   * 결측을 false로 접으면 그 125건이 "비등기"로 둔갑한다 — 이 레포엔 `0`과 `null`을
   * 혼동해 사고 난 전례가 있다.
   */
  isRegistered: boolean | null
  /** `10%이상주주`(실측 145) · `사실상지배주주`(실측 70) · null(실측 3,707) */
  majorHolderType: string | null
  /** ISO date (`2026-08-18`) */
  reportDate: string
  ownedQty: number | null
  /** 음수 가능. 실측 범위 -58,500,000 ~ 36,000,000 */
  changeQty: number | null
  /** NUMERIC(7,2), 0~100. 표시할 때 `toFixed(2)`로 자릿수를 다시 고정할 것 */
  ownedRate: number | null
  changeRate: number | null
  sourceUrl: string
}
