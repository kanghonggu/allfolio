import axios from 'axios'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/real-estate`

/** 단지 하나의 한 평형 */
export interface ComplexArea {
  /**
   * 전용면적(㎡). **이 값이 그대로 자산에 저장된다.**
   *
   * 반올림하거나 평으로 바꿔 저장하면 안 된다 — 같은 단지 안에서 평형이 1㎡ 미만으로
   * 붙어 있는 쌍이 실측 146건이라(`84.83`↔`84.86`), 정밀도가 곧 매칭이다.
   */
  exclusiveAreaM2: number
  /** 참고 표시용 평. **저장하지 않는다** */
  approxPyeong: number
  /** 이 평형의 최근 거래 수. 얇으면 사용자가 알아야 한다 */
  dealCount: number
}

export interface Complex {
  /** 단지일련번호 (`11680-4929`). **`asset.symbol`에 그대로 들어간다** */
  aptSeq: string
  aptName: string
  umdName: string
  buildYear: number | null
  areas: ComplexArea[]
}

export function createRealEstateApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    /**
     * 단지 검색.
     *
     * **거래가 없었던 단지는 안 나온다.** 국토부 API에 "단지 목록"이라는 것이 없어
     * 우리가 받아 둔 실거래에서 역으로 뽑기 때문이다. 그게 맞는 동작이기도 하다 —
     * 실거래가 없으면 자동 평가도 못 한다. 화면이 그렇게 말해야 한다.
     *
     * @param sgg 법정동 코드 앞 5자리. **필수다** — 전국을 훑으면 "래미안"에 수백 개가 걸린다
     */
    searchComplexes: async (sgg: string, q?: string): Promise<Complex[]> =>
      (await api.get<Complex[]>('/complexes', { params: { sgg, q: q || undefined } })).data,
  }
}
