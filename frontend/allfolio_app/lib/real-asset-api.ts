import axios from 'axios'
import type {
  RealAssetCreateRequest,
  RealAssetCreateResponse,
  RealAssetView,
} from '@/types/real-asset'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/real-assets`

export function createRealAssetApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (): Promise<RealAssetView[]> => (await api.get<RealAssetView[]>('')).data,

    // **사용자를 안 싣는다.** 서버가 JWT에서 꺼낸다 — 본문에 실으면 그 순간 IDOR이다.
    // sourceRef·includeInTwr도 마찬가지로 서버가 정하므로 요청 타입에 자리가 없다.
    create: async (req: RealAssetCreateRequest): Promise<RealAssetCreateResponse> =>
      (await api.post<RealAssetCreateResponse>('', req)).data,
  }
}
