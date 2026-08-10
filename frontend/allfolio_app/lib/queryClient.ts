import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { errorMessage, toast } from './toast'
import { recordApiError } from './diagnostics'

// 전역 에러 피드백 (QA P1 #12): 페이지가 자체 onError로 배너를 띄우는 경우는
// 이중 노출을 피하기 위해 전역 토스트를 건너뛴다.
export const queryClient = new QueryClient({
  mutationCache: new MutationCache({
    onError: (error, _variables, _context, mutation) => {
      recordApiError(error)   // 문의에 함께 보낼 "직전 API 에러" (AF-94)
      if (mutation.options.onError) return
      toast.error(errorMessage(error))
    },
  }),
  queryCache: new QueryCache({
    onError: (error) => {
      recordApiError(error)
      toast.error(errorMessage(error, '데이터를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.'))
    },
  }),
  defaultOptions: {
    queries: {
      staleTime:           60 * 1000,   // 1분
      gcTime:              5 * 60 * 1000, // 5분
      refetchOnWindowFocus: false,
      retry:               1,
    },
  },
})

// ── Query Keys ────────────────────────────────────────────────
export const queryKeys = {
  snapshot:  (portfolioId: string, tenantId: string) =>
    ['snapshot', portfolioId, tenantId] as const,
  positions: (portfolioId: string) =>
    ['positions', portfolioId] as const,
  trades:    (portfolioId: string) =>
    ['trades', portfolioId] as const,
}
