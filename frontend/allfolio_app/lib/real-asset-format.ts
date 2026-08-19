// lib/real-asset-format.ts — 실물자산 표시 (A1)

/**
 * 1돈 = 3.75g. 한국에서 금 무게는 돈으로 말한다 — g만 보여 주면 사용자가
 * 자기가 산 것과 대조하려고 매번 나눠야 한다. 그래서 g를 주 단위로 쓰고 돈을 병기한다.
 */
export const GRAMS_PER_DON = 3.75

/**
 * **서버 숫자를 그대로 믿지 않는다.** `NUMERIC` 컬럼은 JSON에서 문자열로 올 수 있고,
 * 타입 선언은 런타임을 검사하지 않는다 — AF-104가 정확히 그것으로 자릿수를 흘렸다.
 * 숫자로 못 읽으면 `null`을 준다(0이 아니다. 0은 "값이 0원"이라는 뜻이라 거짓말이 된다).
 */
export function num(v: string | number | null | undefined): number | null {
  if (v === null || v === undefined) return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * `10.0000` → `10g (2.67돈)`.
 *
 * g는 뒤 0을 지운다(`10.0000`을 그대로 찍으면 정밀도가 있는 척한다). 돈은 소수 둘째 자리 —
 * 1돈 미만도 쓰이므로 반올림해서 0이 되지 않게 한다.
 */
export function gramsWithDon(quantity: string | number | null | undefined): string {
  const g = num(quantity)
  if (g === null) return '—'
  const grams = trimZeros(g)
  const don = g / GRAMS_PER_DON
  return `${grams}g (${don.toFixed(2)}돈)`
}

function trimZeros(n: number): string {
  // toLocaleString은 세 자리 구분을 넣어 준다. 소수는 최대 4자리(컬럼 스케일)까지만.
  return n.toLocaleString('en-US', { maximumFractionDigits: 4 })
}

/** `0.25` → `+25.00%`. 서버는 소수로 준다 — 여기서 ×100 한다 */
export function ratePct(v: string | number | null | undefined): string {
  const r = num(v)
  if (r === null) return '—'
  const pct = r * 100
  return `${pct >= 0 ? '+' : '−'}${Math.abs(pct).toFixed(2)}%`
}

/**
 * 시세 기준일이 얼마나 묵었는지 판정한다.
 *
 * **`stale` 임계치가 5인 데는 실측 근거가 있다.** 공공데이터포털 금 시세는 D+1 공표라
 * 평일에도 최소 1일이고, 연휴 뒤에는 4일까지 벌어진다(2026-08-18 실측: 평가일 78일 중
 * 1일 68% · 2일 15% · 3일 14% · 4일 3%, 최대 4). **0은 정상이 아니라 일어나지 않는 값이다.**
 * 임계치를 1~4로 잡으면 정상 운영이 매일 경고로 표시되고, 그러면 사용자가 경고를 무시한다.
 */
export function stalenessTone(days: number | null): 'ok' | 'stale' {
  if (days === null) return 'ok'
  return days >= 5 ? 'stale' : 'ok'
}

/**
 * `2026-08-14` → `8/14 종가 기준`.
 *
 * **`new Date()`로 파싱하지 않는다** — `'2026-08-14'`는 UTC 자정으로 해석돼 UTC 뒤쪽 존에서
 * 하루 밀린다. 문자열을 자르는 쪽이 존에 안 흔들린다(lib/date.ts·CommodityPanel이 같은 함정을 적어 뒀다).
 */
export function priceAsOfLabel(priceAsOf: string | null): string {
  if (!priceAsOf) return '평가 전'
  const parts = priceAsOf.split('-')
  const month = parts[1]
  const day = parts[2]
  if (!month || !day) return priceAsOf
  return `${Number(month)}/${Number(day)} 종가 기준`
}

export const ASSET_TYPE_LABEL: Record<string, string> = {
  GOLD: '금',
  WATCH: '시계',
  REAL_ESTATE: '부동산',
}

export const SUB_TYPE_LABEL: Record<string, string> = {
  KRX_ACCOUNT: 'KRX 계좌보유분',
  BAR: '골드바',
  JEWELRY: '주얼리',
}
