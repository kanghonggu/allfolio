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
export function priceAsOfLabel(priceAsOf: string | null): string {
  if (!priceAsOf) return ''
  const parts = priceAsOf.split('-')
  const month = parts[1]
  const day = parts[2]
  if (!month || !day) return ''
  return `${Number(month)}/${Number(day)} 종가 기준`
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
