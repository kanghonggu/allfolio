// types/real-asset.ts — 실물자산 (A1)

export type AssetType = 'GOLD' | 'WATCH' | 'REAL_ESTATE'
export type PriceBasis = 'TRADE' | 'ASK'
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW'

/**
 * 목록 한 줄.
 *
 * **평가 관련 필드가 전부 nullable이다 — 서버 계약 그대로다.** 스냅샷이 없으면(등록 당일 ·
 * 배치 전 · 산출 불가) `null`이지 `0`이 아니다. 여기서 `?? 0`으로 접으면 화면이
 * "평가액 0원 · 전액 손실"이라고 말하는데, 사실은 **아직 모른다**는 뜻이다.
 *
 * **숫자 필드를 `number`로 선언했다고 런타임이 그렇게 오는 건 아니다.** AF-104에서 타입
 * 선언만 믿다가 자릿수가 날아간 적이 있다 — 이 화면은 `quantity`·`unitPrice`를 문자열로
 * 받을 수 있는 `NUMERIC` 컬럼에서 오므로 `string | number` 둘 다 견디게 뒀다.
 */
export interface RealAssetView {
  id: string
  assetType: AssetType
  subType: string | null
  name: string
  /** g 단위. NUMERIC(18,4)라 문자열로 올 수 있다 */
  quantity: string | number
  purity: string | number
  acquiredAt: string
  acquiredCostKrw: number
  valuationKrw: number | null
  profitKrw: number | null
  /** 소수(0.25 = 25%). 화면에서 ×100 한다 */
  profitRate: string | number | null
  unitPrice: string | number | null
  /** 'KRW/g' 등. **상수로 가정하지 않는다** — 서버가 행에서 읽어 보낸 값이다 */
  priceUnit: string | null
  valuedOn: string | null
  /** 실제 시세 기준일. **화면에 반드시 노출한다** */
  priceAsOf: string | null
  /** 정상 범위 1~4. 5 이상이면 소스가 멈춘 것 */
  stalenessDays: number | null
  priceBasis: PriceBasis | null
  confidence: Confidence | null
}

export interface RealAssetCreateRequest {
  assetType: AssetType
  subType?: string | null
  name: string
  quantity: string
  purity?: string | null
  acquiredAt: string
  acquiredCostKrw: number
}

export interface RealAssetCreateResponse {
  id: string
}
