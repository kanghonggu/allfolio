/**
 * 시장 데이터 코드 → 한글 라벨 (AF-104).
 *
 * **백엔드는 코드만 싣는다.** 표시명이 프런트 몫인 이유: 설정의 `nameContains`는 KIS 응답을
 * 검증하는 부분 문자열이지 이름이 아니고("다우존스 산업"), 지수를 새로 추가해도 화면 문구는
 * 어차피 사람이 정해야 한다.
 *
 * **모르는 코드는 코드 그대로 보여준다.** 백엔드에 종목이 추가됐는데 여기를 안 고치면
 * 라벨이 없는 채로라도 값이 보여야 한다 — 빈칸이면 종목이 사라진 것처럼 보인다.
 */
const INDEX_LABELS: Record<string, string> = {
  KOSPI: '코스피',
  KOSDAQ: '코스닥',
  KOSPI200: '코스피 200',
  KOSDAQ150: '코스닥 150',
  KRX300: 'KRX 300',
  SPX: 'S&P 500',
  NASDAQ: '나스닥 종합',
  DOW: '다우존스 산업',
  NASDAQ100: '나스닥 100',
  VIX: 'VIX 변동성',
  STOXX50: '유로 STOXX 50',
  NIKKEI225: '니케이 225',
  HANGSENG: '항셍',
  SHANGHAI: '상해종합',
}

const RATE_LABELS: Record<string, string> = {
  BASE_RATE: '한국은행 기준금리',
  CALL_ON: '콜금리(익일물)',
  CD_91D: 'CD 91일',
  KTB_3Y: '국고채 3년',
  KTB_10Y: '국고채 10년',
  CORP_AA3Y: '회사채 AA- 3년',
  US_FFR: '연방기금금리',
  UST_2Y: '미국채 2년',
  UST_10Y: '미국채 10년',
  UST_30Y: '미국채 30년',
}

/** 금리 탭의 2단 구성. 여기 없는 코드는 '기타'로 모인다 */
const KR_RATES = ['BASE_RATE', 'CALL_ON', 'CD_91D', 'KTB_3Y', 'KTB_10Y', 'CORP_AA3Y']
const US_RATES = ['US_FFR', 'UST_2Y', 'UST_10Y', 'UST_30Y']

export function indexLabel(code: string): string {
  return INDEX_LABELS[code] ?? code
}

export function rateLabel(code: string): string {
  return RATE_LABELS[code] ?? code
}

export type RateCountry = 'KR' | 'US' | 'ETC'

export function rateCountry(code: string): RateCountry {
  if (KR_RATES.includes(code)) return 'KR'
  if (US_RATES.includes(code)) return 'US'
  return 'ETC'
}

/**
 * 통화 코드 → 한글 국가·통화명. 58통화 전부를 적지 않는다 —
 * 자주 보는 것만 두고 나머지는 코드로 남긴다. 표에 코드 열이 따로 있어 정보가 없어지지 않는다.
 */
const CURRENCY_LABELS: Record<string, string> = {
  USD: '미국 달러', EUR: '유로', JPY: '일본 엔', CNY: '중국 위안',
  GBP: '영국 파운드', AUD: '호주 달러', CAD: '캐나다 달러', HKD: '홍콩 달러',
  CHF: '스위스 프랑', SGD: '싱가포르 달러', THB: '태국 바트', VND: '베트남 동',
}

/**
 * 한글 이름이 있으면 그것, 없으면 null.
 *
 * **표시용은 이쪽을 쓴다.** [currencyLabel]의 코드 폴백을 이름 칸에 그대로 그리면
 * `AED  AED  384.5000`처럼 코드가 두 번 나온다 — 58통화 중 46통화가 그랬다.
 * 코드 열이 바로 옆에 있으니 이름 칸은 비워도 정보가 사라지지 않는다.
 */
export function currencyName(code: string): string | null {
  return CURRENCY_LABELS[code] ?? null
}

/**
 * 이름이 없으면 코드로 대체한다. **검색처럼 "무언가 문자열이 필요한" 자리 전용이다** —
 * 코드 열 옆에 그리면 같은 코드가 두 번 보인다. 화면 표시는 [currencyName]을 쓸 것.
 */
export function currencyLabel(code: string): string {
  return CURRENCY_LABELS[code] ?? code
}
