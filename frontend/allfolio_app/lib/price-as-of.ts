// lib/price-as-of.ts — 자동 평가 자산의 시세 기준일 표시 (A1 · N2)

/**
 * 기준일이 얼마나 묵었는지 판정한다.
 *
 * **임계치가 5인 데는 실측 근거가 있다.** 공공데이터포털 금 시세는 D+1 공표라 평일에도
 * 최소 1일이고, 연휴 뒤에는 4일까지 벌어진다(2026-08-18 실측: 평가일 78일 중
 * 1일 68% · 2일 15% · 3일 14% · 4일 3%, 최대 4).
 *
 * **0은 정상이 아니라 일어나지 않는 값이다.** 임계치를 1~4로 잡으면 정상 운영이 매일
 * 경고로 표시되고, 그러면 사용자가 경고를 무시하게 된다 — 진짜 소스 중단 때 아무도 안 본다.
 */
export const STALE_THRESHOLD_DAYS = 5

/**
 * 자산 유형별 지연 임계치(일).
 *
 * **금의 5일을 부동산에 그대로 쓰면 거의 모든 자산이 "지연"으로 뜬다.** 실측에서
 * (단지, 전용면적) 조합당 거래가 12개월에 중앙 2건이라, 마지막 거래가 몇 달 전인 것이
 * **정상**이다. 매번 경고가 뜨면 아무도 안 보고, 그러면 진짜 이상을 놓친다.
 *
 * 성격 자체가 다르다: 금의 지연은 **소스 중단**이고, 부동산의 공백은 **시장 사실**이다.
 * 그래서 임계치도 문구도 갈라야 한다([priceAsOfLabel] 참조).
 *
 * 180일인 근거: 반년 넘게 그 평형에 거래가 없으면 중앙값이 현재 시세를 대표한다고 보기
 * 어렵다. 12개월 창 안에서도 값이 절반 가까이 10% 넘게 움직였다(p50 +7.7%).
 */
const STALE_THRESHOLD_BY_TYPE: Record<string, number> = {
  REAL_ESTATE: 180,
}

/** 이 유형의 지연 임계치. 모르는 유형은 [STALE_THRESHOLD_DAYS]를 쓴다 */
export function staleThresholdOf(type?: string | null): number {
  // `type && …`로 쓰면 빈 문자열일 때 `''`가 나와 `??`가 안 걸린다 — 명시적으로 본다
  if (!type) return STALE_THRESHOLD_DAYS
  return STALE_THRESHOLD_BY_TYPE[type] ?? STALE_THRESHOLD_DAYS
}

/**
 * `priceAsOf`가 오늘로부터 며칠 전인지. 못 읽으면 `null`.
 *
 * **`new Date('2026-08-14')`로 파싱하지 않는다** — ISO 날짜만 있는 문자열은 UTC 자정으로
 * 해석돼 KST에서 하루 밀린다. 연/월/일을 뜯어 로컬 자정끼리 빼는 쪽이 존에 안 흔들린다
 * (`lib/date.ts`·`CommodityPanel`이 같은 함정을 적어 뒀다).
 */
export function stalenessDays(priceAsOf: string | null, today = new Date()): number | null {
  if (!priceAsOf) return null
  const parts = priceAsOf.split('-')
  if (parts.length !== 3) return null
  const [y, m, d] = parts.map(Number)
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) return null

  const asOf = new Date(y, m - 1, d)
  const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((midnight.getTime() - asOf.getTime()) / 86_400_000)
}

/** `2026-08-14` → `8/14 종가 기준`. 없으면 빈 문자열(화면이 아무것도 안 그린다) */
export function priceAsOfLabel(priceAsOf: string | null, type?: string | null): string {
  if (!priceAsOf) return ''
  const parts = priceAsOf.split('-')
  const month = parts[1]
  const day = parts[2]
  if (!month || !day) return ''
  // **부동산에 "종가"는 틀린 말이다.** 장이 열고 닫는 자산이 아니라 실제로 팔린 건이다.
  const basis = type === 'REAL_ESTATE' ? '실거래' : '종가'
  return `${Number(month)}/${Number(day)} ${basis} 기준`
}

/**
 * 이 포지션에 기준일을 보여 줄지.
 *
 * **`priceAsOf`만으로 판단하지 않고 `valuationMethod`도 본다.** 사용자가 손으로 넣은 값에
 * 어쩌다 기준일이 남아 있는 경우(평가되던 자산을 수동으로 고친 뒤 등) 화면이 그걸
 * "자동 평가된 신선한 값"이라고 설명하게 되기 때문이다. 둘이 같이 맞아야 표시한다.
 */
export function showsPriceAsOf(position: {
  priceAsOf: string | null
  valuationMethod: string
}): boolean {
  return !!position.priceAsOf && position.valuationMethod === 'MARKET_PRICE'
}
