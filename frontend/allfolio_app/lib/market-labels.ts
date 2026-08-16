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

/**
 * 원자재 코드 → 한글 라벨 (AF-108). 코드는 `application.yml`의 `market-commodity` 그대로다.
 *
 * **단위를 여기 안 적는다.** 단위는 관측과 함께 행에 실려 오므로(CommodityQuoteView.unit)
 * 그쪽을 쓴다 — 여기 상수로 두면 설정이 바뀐 날 저장은 멀쩡한데 화면만 조용히 틀린다.
 *
 * `GOLD_KRX`는 아직 수집이 없다(FSC 인증키 보류, Task 4). **그래도 미리 넣어 둔다** —
 * 붙는 날 라벨만 없어서 표에 `GOLD_KRX`가 그대로 노출되는 걸 막는다.
 */
const COMMODITY_LABELS: Record<string, string> = {
  WTI: 'WTI 원유',
  BRENT: '브렌트유',
  NATGAS: '천연가스',
  COPPER: '구리',
  NICKEL: '니켈',
  ZINC: '아연',
  ALUMINUM: '알루미늄',
  IRON_ORE: '철광석',
  COAL_AU: '석탄(호주)',
  URANIUM: '우라늄',
  WHEAT: '밀',
  CORN: '옥수수',
  SOYBEANS: '대두',
  SUGAR: '설탕',
  COFFEE: '커피',
  ALL_INDEX: '원자재 종합지수',
  GOLD_KRX: '금(KRX)',
}

export function indexLabel(code: string): string {
  return INDEX_LABELS[code] ?? code
}

export function rateLabel(code: string): string {
  return RATE_LABELS[code] ?? code
}

export function commodityLabel(code: string): string {
  return COMMODITY_LABELS[code] ?? code
}

export type CommoditySection = 'DAILY' | 'MONTHLY' | 'ETC'

/**
 * 원자재 탭의 2단 구성. **소스 이름이나 코드가 아니라 주기로 가른다** —
 * 금(FSC, frequency=D)이 붙어도 화면 코드가 안 바뀌게 하려는 것이다.
 *
 * **`ETC`가 있는 이유**: 백엔드 필드가 `String`(length 1)이라 설정 오타 한 번이면 `'d'`가
 * 실려 온다. 그때 갈 곳이 없으면 그 종목은 어느 섹션에도 안 떠 화면에서 조용히 사라진다 —
 * 값이 틀린 것보다 없어진 것이 알아채기 더 어렵다.
 * (금리 탭의 [rateCountry]가 같은 이유로 '기타'를 남긴다.)
 *
 * **인자를 `'D' | 'M'`이 아니라 string으로 받는 이유는 그 fallback을 지키기 위해서다.**
 * 유니언으로 받아도 마지막 `return 'ETC'`는 그대로 컴파일되고 그대로 emit된다 — tsc는 타입을
 * 지울 뿐 분기를 지우지 않으므로, 런타임에 `'d'`가 오면 유니언 선언이어도 'ETC'가 반환된다.
 * 위험한 건 컴파일러가 아니라 **읽는 사람**이다: 유니언이면 그 갈래가 타입상 도달 불가(`never`)로
 * 보여 죽은 코드로 읽히고, 누군가 지운다. 종목이 사라지려면 그 삭제가 있어야 한다.
 * string으로 받으면 지울 이유가 안 생긴다.
 */
export function commoditySection(frequency: string): CommoditySection {
  if (frequency === 'D') return 'DAILY'
  if (frequency === 'M') return 'MONTHLY'
  return 'ETC'
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
