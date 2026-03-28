import { QueryClient } from '@tanstack/react-query'

export const queryClient = new QueryClient({
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
