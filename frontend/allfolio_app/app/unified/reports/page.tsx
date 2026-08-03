'use client'

import { useState } from 'react'
import Link from 'next/link'

const REPORTS = [
  {
    href:  '/unified/reports/summary',
    group: 'basic',
    title: '포트폴리오 요약',
    desc:  'NAV, 총 손익, 자산 수 및 유형별 현황',
    color: 'border-blue-700 hover:border-blue-500',
    badge: '📊',
  },
  {
    href:  '/unified/reports/performance',
    group: 'basic',
    title: '수익률 분석',
    desc:  '기간별 수익률(1W/1M/3M/YTD/1Y), 일별 NAV 시계열',
    color: 'border-emerald-700 hover:border-emerald-500',
    badge: '📈',
  },
  {
    href:  '/unified/reports/returns',
    group: 'institutional',
    title: '수익률 보고서 (TWR·MWR)',
    desc:  '기관급 수익률 — 입출금 왜곡 제거(TWR), 체감 수익률(MWR), 입출금 효과 분해',
    color: 'border-lime-700 hover:border-lime-500',
    badge: '📉',
  },
  {
    href:  '/unified/reports/monthly-report',
    group: 'institutional',
    title: '월간 운용보고서',
    desc:  'R-01 기관급 월간 리포트 — 성과·익스포저·계좌·입출금 분해, PDF 인쇄',
    color: 'border-indigo-700 hover:border-indigo-500',
    badge: '📄',
  },
  {
    href:  '/unified/reports/dividend-report',
    group: 'institutional',
    title: '배당·이자 보고서',
    desc:  'R-03 기관급 배당 리포트 — 세전·원천징수·세후, 종목·국가별 집계, PDF 인쇄',
    color: 'border-amber-700 hover:border-amber-500',
    badge: '💵',
  },
  {
    href:  '/unified/reports/cost-report',
    group: 'institutional',
    title: '비용 보고서',
    desc:  'R-04 기관급 비용 리포트 — 수수료·거래세, 비용률·TER, 브로커별 매트릭스, PDF 인쇄',
    color: 'border-orange-700 hover:border-orange-500',
    badge: '💳',
  },
  {
    href:  '/unified/reports/holdings-report',
    group: 'institutional',
    title: '월말 보유 명세서',
    desc:  'R-05 기관급 보유 명세 — 종목별 수량·평단·평가액·평가손익, 계좌·자산군별 소계, PDF 인쇄',
    color: 'border-pink-700 hover:border-pink-500',
    badge: '📑',
  },
  {
    href:  '/unified/reports/cashflow-report',
    group: 'institutional',
    title: '현금흐름 보고서',
    desc:  'R-06 기관급 현금흐름 — 입금·출금·매수·매도·배당·수수료, 순현금흐름, 월별 추이, PDF 인쇄',
    color: 'border-fuchsia-700 hover:border-fuchsia-500',
    badge: '💧',
  },
  {
    href:  '/unified/reports/esg-screening',
    group: 'institutional',
    title: 'ESG 스크리닝',
    desc:  'R-07 투자배제·ESG — E/S/G 점수, 종목별 등급, 배제리스트 위반 스크리닝, PDF 인쇄',
    color: 'border-green-700 hover:border-green-500',
    badge: '🌿',
  },
  {
    href:  '/unified/reports/allocation',
    group: 'basic',
    title: '자산 배분',
    desc:  '유형·통화별 비중, 상위 보유 종목, HHI 집중도',
    color: 'border-purple-700 hover:border-purple-500',
    badge: '🥧',
  },
  {
    href:  '/unified/reports/risk',
    group: 'basic',
    title: '리스크 분석',
    desc:  'Volatility, VaR95, MDD, Sharpe, Calmar 비율',
    color: 'border-red-700 hover:border-red-500',
    badge: '⚠️',
  },
  {
    href:  '/unified/reports/positions',
    group: 'basic',
    title: '포지션 & 손익',
    desc:  '평균 매입가, 미실현 손익, 수익률 순위',
    color: 'border-amber-700 hover:border-amber-500',
    badge: '💼',
  },
  {
    href:  '/unified/reports/benchmark',
    group: 'basic',
    title: '벤치마크 비교',
    desc:  'S&P500 / BTC / KOSPI 대비 알파 및 누적 수익률 차트',
    color: 'border-cyan-700 hover:border-cyan-500',
    badge: '🏁',
  },
  {
    href:  '/unified/reports/networth',
    group: 'basic',
    title: '순자산 추이',
    desc:  '총 자산 - 부채 = 순자산, 유형별 분해 및 NAV 이력 차트',
    color: 'border-teal-700 hover:border-teal-500',
    badge: '🏦',
  },
  {
    href:  '/unified/reports/monthly',
    group: 'basic',
    title: '월별 손익 정산',
    desc:  '월별 수익/손실 바차트, 최고·최저 달, 승률 통계',
    color: 'border-indigo-700 hover:border-indigo-500',
    badge: '📅',
  },
  {
    href:  '/unified/reports/tax',
    group: 'tools',
    title: '세금 계산기',
    desc:  '해외주식 양도세, 금융투자소득세, 배당·이자소득세 예상액',
    color: 'border-rose-700 hover:border-rose-500',
    badge: '🧾',
  },
  {
    href:  '/unified/goals',
    group: 'tools',
    title: '목표 달성 트래커',
    desc:  '집 마련, 은퇴 등 재무 목표를 설정하고 현재 달성률을 추적',
    color: 'border-violet-700 hover:border-violet-500',
    badge: '🎯',
  },
  {
    href:  '/unified/simulator',
    group: 'tools',
    title: '투자 시뮬레이터',
    desc:  '복리 효과로 목표까지 걸리는 기간, 필요 월 적립액 역산',
    color: 'border-sky-700 hover:border-sky-500',
    badge: '🔭',
  },
  {
    href:  '/unified/reports/esg',
    group: 'basic',
    title: 'ESG 점수',
    desc:  '환경·사회·지배구조 기반 포트폴리오 ESG 등급 및 자산별 분석',
    color: 'border-emerald-700 hover:border-emerald-500',
    badge: '🌱',
  },
  {
    href:  '/unified/reports/dividend',
    group: 'basic',
    title: '배당금 보고서',
    desc:  '수령 배당금 합계, 월별 추이, 종목별 배당 이력',
    color: 'border-yellow-700 hover:border-yellow-500',
    badge: '💰',
  },
  {
    href:  '/unified/advisor',
    group: 'tools',
    title: 'AI 금융 상담사',
    desc:  '포트폴리오 데이터 기반 LLM 금융 상담',
    color: 'border-green-700 hover:border-green-500',
    badge: '🤖',
  },
]

// QA P2: 21개 카드 평면 나열 → 추천 4개 + 카테고리별 접기
const FEATURED_HREFS = [
  '/unified/reports/summary',
  '/unified/reports/returns',
  '/unified/reports/monthly-report',
  '/unified/reports/networth',
]

const GROUP_LABELS: Record<string, string> = {
  basic: '기본 분석',
  institutional: '기관급 리포트 (R-01~R-07)',
  tools: '도구',
}

function ReportCard({ r }: { r: (typeof REPORTS)[number] }) {
  return (
    <Link
      href={r.href}
      className={`group rounded-xl border bg-gray-900 p-6 transition-colors ${r.color}`}
    >
      <div className="text-3xl mb-3">{r.badge}</div>
      <h2 className="text-base font-semibold text-gray-100 group-hover:text-white">{r.title}</h2>
      <p className="mt-1.5 text-sm text-gray-500 group-hover:text-gray-400">{r.desc}</p>
    </Link>
  )
}

export default function ReportsHub() {
  const [showAll, setShowAll] = useState(false)
  const featured = FEATURED_HREFS
    .map((href) => REPORTS.find((r) => r.href === href))
    .filter((r): r is (typeof REPORTS)[number] => !!r)

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold">보고서</h1>
        <p className="mt-1 text-sm text-gray-400">포트폴리오의 다양한 측면을 분석합니다</p>
      </div>

      {/* 추천 보고서 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {featured.map((r) => <ReportCard key={r.href} r={r} />)}
      </div>

      <button
        onClick={() => setShowAll((v) => !v)}
        className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-400 hover:border-gray-500 hover:text-white transition-colors"
      >
        {showAll ? '접기 ▲' : `전체 보고서 보기 (${REPORTS.length}개) ▼`}
      </button>

      {showAll && (['basic', 'institutional', 'tools'] as const).map((g) => (
        <section key={g} className="space-y-4">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-gray-400">{GROUP_LABELS[g]}</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {REPORTS.filter((r) => r.group === g).map((r) => <ReportCard key={r.href} r={r} />)}
          </div>
        </section>
      ))}
    </div>
  )
}
