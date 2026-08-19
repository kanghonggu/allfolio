import axios from 'axios'
import type { DisclosureFeed } from '@/types/disclosure'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/disclosures`

export function createDisclosureApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    // from은 백엔드가 KST 기준 30일 전으로 기본값을 잡는다 — 여기서 안 보낸다.
    // 기간 선택 컨트롤을 만들지 않기로 했으므로 파라미터를 노출할 이유가 없다(설계 5절)
    feed: async (): Promise<DisclosureFeed> => (await api.get<DisclosureFeed>('')).data,
  }
}
