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
   * 🔴 **서버가 매칭에 쓴 키다** — 상류가 정규화한 결과이지 사용자가 친 문자열이 아니다.
   *
   * 실측(2026-09-08): `126300ln` → `126300LN`, `  116238   chsj  ` → `116238 CHSJ`.
   * 대소문자·공백·괄호 주석은 접히지만 **소재 기호(`CHSJ`)는 안 잘린다** — 자르면 스틸과
   * 금이 한 중앙값에 섞이기 때문이다. 그래서 `116238`과 `116238 CHSJ`는 다른 키다.
   *
   * 이 값을 `asset.symbol`에 넣어야 다음 평가에서 같은 표본을 다시 찾는다.
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

/**
 * 모델명으로 찾은 ref 후보 (AF-207). 서버가 watchpricedata `/api/refs`를 대신 부른다 —
 * 브라우저는 상류를 직접 부르지 않는다.
 */
export interface WatchRefCandidate {
  /** 표시용 원문 ref */
  ref: string
  /** 🔴 확인 단계(`lookupRef`)에 **그대로** 넘길 키 */
  refKey: string
  brand: string | null
  modelTitle: string | null
  officialPriceKrw: number | null
}

/**
 * 🔴 **`OK`+빈 목록과 `UNAVAILABLE`은 다른 답이다.** 앞은 "후보가 없다", 뒤는 "검색을 못 했다"
 * (상류 타임아웃·장애·배포 전). 화면 안내가 갈린다.
 */
export interface WatchRefSearch {
  status: 'OK' | 'UNAVAILABLE'
  candidates: WatchRefCandidate[]
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

    /**
     * 모델명·ref 일부로 후보를 찾는다. 서버의 상류 타임아웃은 3초라 그보다 조금 넉넉하게 끊는다 —
     * 자동완성이 등록 흐름을 붙잡으면 안 된다. 실패는 호출부가 `UNAVAILABLE`과 같이 다룬다.
     */
    searchRefs: async (q: string, signal?: AbortSignal): Promise<WatchRefSearch> =>
      (await api.get<WatchRefSearch>('/refs/search', { params: { q }, timeout: 6_000, signal })).data,
  }
}
