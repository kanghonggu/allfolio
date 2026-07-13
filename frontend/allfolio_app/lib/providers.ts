import type { AccountProvider } from '@/types/unified'

/**
 * API/지갑 조회로 자산을 자동 동기화할 수 있는 프로바이더 목록(단일 소스).
 *
 * 주의: 여기 없는 `STOCK`은 별개다. STOCK은 외부 API가 아니라 수동 입력한
 * 거래내역(ua_stock_trades)을 재계산하는 방식이라, 계좌 상세에서만 Sync를
 * 노출한다(목록 페이지에서는 제외). 그 차이는 각 호출부에서 명시적으로 처리한다.
 */
export const SYNCABLE_PROVIDERS: ReadonlySet<AccountProvider> = new Set<AccountProvider>([
  'BINANCE', 'UPBIT', 'BITHUMB', 'COINONE', 'BYBIT', 'OKX', 'WALLET', 'KIS',
])

/** API/지갑 기반 자동 동기화가 가능한 프로바이더인지 여부. */
export function isSyncable(provider: AccountProvider | string | null | undefined): boolean {
  return !!provider && SYNCABLE_PROVIDERS.has(provider as AccountProvider)
}
