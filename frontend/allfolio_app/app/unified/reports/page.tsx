'use client'

import Link from 'next/link'

const REPORTS = [
  {
    href:  '/unified/reports/summary',
    title: '포트폴리오 요약',
    desc:  'NAV, 총 손익, 자산 수 및 유형별 현황',
    color: 'border-blue-700 hover:border-blue-500',
    badge: '📊',
  },
  {
    href:  '/unified/reports/performance',
    title: '수익률 분석',
    desc:  '기간별 수익률(1W/1M/3M/YTD/1Y), 일별 NAV 시계열',
    color: 'border-emerald-700 hover:border-emerald-500',
    badge: '📈',
  },
  {
    href:  '/unified/reports/allocation',
    title: '자산 배분',
    desc:  '유형·통화별 비중, 상위 보유 종목, HHI 집중도',
    color: 'border-purple-700 hover:border-purple-500',
    badge: '🥧',
  },
  {
    href:  '/unified/reports/risk',
    title: '리스크 분석',
    desc:  'Volatility, VaR95, MDD, Sharpe, Calmar 비율',
    color: 'border-red-700 hover:border-red-500',
    badge: '⚠️',
  },
  {
    href:  '/unified/reports/positions',
    title: '포지션 & 손익',
    desc:  '평균 매입가, 미실현 손익, 수익률 순위',
    color: 'border-amber-700 hover:border-amber-500',
    badge: '💼',
  },
  {
    href:  '/unified/reports/benchmark',
    title: '벤치마크 비교',
    desc:  'S&P500 / BTC / KOSPI 대비 알파 및 누적 수익률 차트',
    color: 'border-cyan-700 hover:border-cyan-500',
    badge: '🏁',
  },
]

export default function ReportsHub() {
  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold">보고서</h1>
        <p className="mt-1 text-sm text-gray-400">포트폴리오의 다양한 측면을 분석합니다</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {REPORTS.map((r) => (
          <Link
            key={r.href}
            href={r.href}
            className={`group rounded-xl border bg-gray-900 p-6 transition-colors ${r.color}`}
          >
            <div className="text-3xl mb-3">{r.badge}</div>
            <h2 className="text-base font-semibold text-gray-100 group-hover:text-white">{r.title}</h2>
            <p className="mt-1.5 text-sm text-gray-500 group-hover:text-gray-400">{r.desc}</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
