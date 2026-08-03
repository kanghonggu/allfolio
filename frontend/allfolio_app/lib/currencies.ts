// 지원 통화 단일 소스 (QA P2) — BE Currencies.SUPPORTED 화이트리스트와 동기 유지
export const SUPPORTED_CURRENCIES = ['KRW', 'USD', 'USDT', 'BTC', 'ETH'] as const

export type SupportedCurrency = (typeof SUPPORTED_CURRENCIES)[number]
