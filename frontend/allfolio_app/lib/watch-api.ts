import axios from 'axios'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/watch`

/**
 * ref 확인 결과 (W6).
 *
 * **`found=false`가 오류가 아니다.** 표본이 3건 미만이면 서버가 값을 안 주고, 그때도
 * 등록은 되어야 한다 — 시세를 못 구하는 시계도 자산으로는 존재한다.
 */
export interface WatchRefLookup {
  found: boolean
  /**
   * 🔴 **서버가 매칭에 쓴 키다 — "정규화된 값"이 아니다.**
   *
   * 실측(2026-09-02) 상류는 입력을 그대로 되울린다(`116238 CHSJ` → `116238 CHSJ`).
   * 그래도 이 값을 `asset.symbol`에 넣는 이유는 그것이 조회에 실제로 쓰인 키이고,
   * 상류가 나중에 정규화를 하게 되면 자동으로 따라가기 때문이다.
   */
  ref?: string
  sampleSize?: number
  medianKrw?: number
  /** 🔴 관측일이 아니라 30일 창의 끝이다 — 화면 문구가 그걸 말해야 한다 */
  asOf?: string
  windowDays?: number
  confidence?: string
  officialPriceKrw?: number
}

export function createWatchApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    // 상류(watchpricedata)를 직접 부르는 유일한 사용자 경로라 여유를 둔다.
    // 평가 경로는 로컬 캐시만 읽으므로 이 지연에 묶이지 않는다.
    timeout: 25_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    /** ref 하나를 확인한다. 없으면 `found=false`이고 오류가 아니다 */
    lookupRef: async (ref: string): Promise<WatchRefLookup> =>
      (await api.get<WatchRefLookup>('/refs/lookup', { params: { ref } })).data,
  }
}
