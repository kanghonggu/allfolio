/**
 * 보고서 지표 도움말 문구 (AF-205).
 *
 * **문구는 계산 코드를 읽고 쓴 것이다 — 계산이 바뀌면 여기도 같이 바뀌어야 한다.**
 * 각 항목 위 주석이 근거 위치다. 추측으로 다듬지 말 것: 데모에서 "기간 손익 · 초과수익 ·
 * 순입출금"을 서로 연관된 값으로 오해한 게 이 파일의 출발점이라, 문구가 계산과 어긋나면
 * 도움말이 오해를 오히려 굳힌다.
 *
 * 벤치마크 문구는 **화면마다 따로** 있다 — R-02와 B-06이 지수 기준점을 다르게 잡는다(아래 주석).
 * B-02 "벤치마크 대비 알파"에는 일부러 없다 — 그 값은 마지막 하루의 일간 알파라 기간 알파가 아니다.
 */
export const METRIC_HELP = {
  // ── 수익률 (R-02 수익률 보고서 · R-01 월간 운용보고서) ─────────────────
  // ReturnsCalculator.twr / segments: 구간 r = (NAV_i − NAV_{i−1} − 순입출금) / (NAV_{i−1} + 입금), 곱으로 연결
  twr:
    '입출금 효과를 빼고 운용만으로 낸 수익률입니다. 평가액을 잰 날 사이 구간마다 ' +
    '(평가액 변화 − 입출금) ÷ (직전 평가액 + 입금)을 구해 곱으로 이어 붙입니다. 연율이 아닌 기간 수익률입니다.',
  // ReturnsCalculator.xirrPeriodReturn: 기초 NAV·입출금·기말 NAV로 XIRR(연율)을 풀고 (1+연율)^(일수/365)−1
  mwr:
    '기초 평가액, 기간 중 입출금, 기말 평가액을 실제 현금흐름으로 놓고 푼 내부수익률(XIRR)을 이 기간 길이로 환산한 값입니다. ' +
    '언제 돈을 넣고 뺐는지가 반영돼 TWR과 달라질 수 있습니다.',
  // ReturnsCalculator.calculate: investmentPnl = endNav − startNav − netFlow
  investmentPnl:
    '기말 평가액 − 기초 평가액 − 순입출금입니다. 새로 넣거나 뺀 돈은 제외하고 투자로 늘거나 줄어든 금액만 남깁니다.',
  // ReturnsCalculator.calculate: netFlow = 기초 관측일 다음 날 ~ 기말 관측일의 Σ(입금 − 출금)
  // CashFlow.signedKrw: 계좌 간 이체·환전(TRANSFER_*, FX_*)은 0
  netFlow:
    '이 기간에 외부에서 넣은 돈(입금)에서 뺀 돈(출금)을 뺀 금액입니다. 손익이 아니며, 계좌 간 이체·환전은 들어가지 않습니다.',
  // GetReturnsAnalysisUseCase.benchmarkComparison: 설정한 BM의 [from, to] 종가 중 첫 종가 → 마지막 종가
  benchmarkPeriodReturn:
    '설정한 비교 지수의 이 기간 수익률입니다. 조회 기간 안의 첫 종가 대비 마지막 종가로 재며, 가격 지수라 배당은 들어가지 않습니다.',
  // GetReturnsAnalysisUseCase.benchmarkComparison: excessReturn = twr − periodReturn
  excessReturn:
    '같은 기간 TWR에서 지수 수익률을 뺀 차이(%p)입니다. 수익률끼리의 차이라 금액이 아니며, ' +
    '옆의 기간 손익금액·순입출금과는 직접 이어지지 않습니다. 플러스면 지수보다 잘한 것입니다.',

  // ── 벤치마크 비교 (B-06) ─────────────────────────────────────────────
  // ReportService.indexPeriodReturn: 기간 시작일 당일 또는 그 직전 마지막 종가 → 최신 종가
  benchmarkIndexReturn:
    '지수의 이 기간 수익률입니다. 기간 시작일(휴장이면 그 직전 거래일) 종가 대비 최신 종가로 재며, 가격 지수라 배당은 들어가지 않습니다.',
  // ReportService.benchmark (AF-107): alpha = periodTwrPercent(같은 since) − 지수 수익률, 기저 없으면 null
  benchmarkAlpha:
    '같은 기간 포트폴리오 TWR에서 이 지수의 수익률을 뺀 차이(%p)입니다. 플러스면 지수보다 잘한 것입니다. ' +
    '평가액 기록이 기간 시작까지 거슬러 가지 않으면 비교할 기준이 없어 "—"로 표시합니다.',

  // returns/page.tsx 워터폴: 기초 + 입금 − 출금 ± 투자손익 = 기말 (투자손익은 위 investmentPnl과 같은 값)
  flowEffect:
    '기초 평가액에 입금을 더하고 출금을 빼고 투자손익을 더하면 기말 평가액이 됩니다. ' +
    '자산이 늘어난 게 새로 넣은 돈 때문인지 투자 성과 때문인지 나눠 보여 줍니다.',
  // MonthlyReportGenerator flowDecomposition: 기초 NAV + 순유입(netFlow) + 투자손익 = 기말 NAV
  flowEffectMonthly:
    '기초 평가액에 순유입(입금 − 출금)과 투자손익을 더하면 기말 평가액이 됩니다. ' +
    '자산이 늘어난 게 새로 넣은 돈 때문인지 투자 성과 때문인지 나눠 보여 줍니다.',
  // ReturnsCalculator.attribute: 구간마다 환율을 전일로 고정한 수익률을 자산 기여로, (1+r)/(1+r_자산)−1을 환율 기여로
  currencyAttribution:
    '기간 수익(TWR)을, 환율이 전날 그대로였다고 볼 때의 수익(자산)과 환율 변동으로 생긴 차이(환율)로 나눈 값입니다. ' +
    '둘은 더하지 않고 곱해서 맞습니다: (1+자산)×(1+환율)−1 = TWR.',
  // MonthlyReportGenerator.annualizedVolatility: 관측일 간 NAV 변화율(입출금 미보정)의 표본 표준편차 × √252
  monthlyVolatility:
    '이 달 평가액을 잰 날 사이 변화율의 표준편차를 연 단위로 환산(×√252)한 값입니다. 입출금이 있던 날의 평가액 변화도 그대로 들어갑니다.',
  // MonthlyReportGenerator body.performance.month.endNav: 기간 마지막 관측일의 NAV
  endNav: '이 달 마지막으로 평가액을 잰 날의 원화 환산 평가액입니다.',

  // ── 리스크 (B-04) ─ risk_daily 최신 행. SnapshotTriggerService가 직전 30일 일간 수익률로 RiskEngine을 돌린다 ──
  // RiskEngine.computeStdDev: 표본 표준편차(n−1)
  dailyVolatility:
    '최근 30일간 하루 수익률(전일 대비 평가액 변화율)이 평균에서 얼마나 흔들렸는지를 나타낸 표준편차입니다. 클수록 하루 등락이 큽니다.',
  // RiskEngine: annualizedVolatility = σ × √252
  annualizedVolatility:
    '하루 변동성에 √252(1년 거래일 수의 제곱근)를 곱해 1년 단위로 환산한 값입니다.',
  // RiskEngine.computeHistoricalVar95: 일간 수익률을 오름차순 정렬해 floor(n×0.05)번째 값
  var95:
    '최근 30일간 하루 수익률을 나쁜 순으로 줄 세웠을 때 하위 5% 자리의 값입니다. 과거 기준으로 "나쁜 날엔 하루에 이 정도 잃었다"는 뜻이며, 손실 상한을 보장하지 않습니다.',
  // RiskEngine.computeMaxDrawdown: 일간 수익률을 누적한 지수의 고점 대비 최저 하락률 — 입력이 최근 30일뿐
  maxDrawdown:
    '최근 30일 안에서, 그때까지의 최고점 대비 가장 크게 떨어졌던 비율입니다. 전체 보유 기간의 최대 낙폭이 아닙니다.',

  // ── 수익률 분석 (B-02) ─────────────────────────────────────────────
  // ReportService.performance: (Σ 현재 평가액 − Σ 매입원가) / Σ 매입원가, 모두 원화 환산, 현재 보유 자산 기준
  costBasisReturn:
    '지금 가진 자산의 평가액 합계가 매입 원가 합계보다 몇 % 높은지입니다(원화 환산). 입출금 시점이나 이미 판 자산은 반영되지 않아 TWR과 다릅니다.',

  // ── ESG (B-10) ────────────────────────────────────────────────────
  // EsgEngine.calculate: 자산 유형별 고정 E·S·G 점수를 원화 평가액 비중으로 가중평균, 총점 = E×0.35 + S×0.30 + G×0.35
  esgTotal:
    '자산 유형(주식·현금·암호화폐 등)마다 정해 둔 E·S·G 기본 점수를 평가액 비중으로 평균 낸 뒤, E×35% + S×30% + G×35%로 합친 0~100점입니다. ' +
    '개별 기업의 실제 ESG 평가가 아닙니다.',
  // EsgEngine.rating: 85↑ A+ · 75↑ A · 65↑ B+ · 55↑ B · 45↑ C+ · 그 아래 C
  esgRating:
    'ESG 총점으로 매긴 등급입니다. 85점 이상 A+, 75점 이상 A, 65점 이상 B+, 55점 이상 B, 45점 이상 C+, 그 아래는 C입니다.',
} as const

export type MetricHelpKey = keyof typeof METRIC_HELP
