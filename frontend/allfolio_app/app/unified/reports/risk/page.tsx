'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { DailyRisk } from '@/types/report'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'

function fmtPct(n: number | null | undefined, decimals = 2) {
  if (n === null || n === undefined) return '—'
  return `${n.toFixed(decimals)}%`
}
function fmtN(n: number | null | undefined, decimals = 4) {
  if (n === null || n === undefined) return '—'
  return n.toFixed(decimals)
}

export default function RiskPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'risk'],
    queryFn: () => reportApi!.risk(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const hasData = data.volatility !== null

  const mddColor = !data.maxDrawdown ? 'text-gray-400'
    : data.maxDrawdown < -0.2 ? 'text-red-400'
    : data.maxDrawdown < -0.1 ? 'text-amber-400'
    : 'text-emerald-400'

  const volColor = !data.annualizedVolatility ? 'text-gray-400'
    : data.annualizedVolatility > 0.3 ? 'text-red-400'
    : data.annualizedVolatility > 0.15 ? 'text-amber-400'
    : 'text-emerald-400'

  const chartData = data.series.map((d: DailyRisk) => ({
    date: d.date,
    vol: Number(d.annualizedVolatility) * 100,
    var95: Number(d.var95) * 100,
    mdd: Number(d.maxDrawdown) * 100,
  }))

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">리스크 분석</h1>
      </div>
      {data.latestDate && (
        <p className="text-xs text-gray-500">기준일: {data.latestDate}</p>
      )}

      {!hasData && (
        <div className="rounded-xl border border-amber-800 bg-amber-950/30 p-4 text-sm text-amber-400">
          리스크 이력 데이터가 없습니다. 성과 데이터가 쌓이면 지표가 자동으로 계산됩니다.
        </div>
      )}

      {/* KPI Grid */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <RiskCard
          label="변동성 (일)"
          value={fmtPct(data.volatility !== null ? Number(data.volatility) * 100 : null)}
          desc="일별 수익률 표준편차"
        />
        <RiskCard
          label="변동성 (연환산)"
          value={fmtPct(data.annualizedVolatility !== null ? Number(data.annualizedVolatility) * 100 : null)}
          valueClass={volColor}
          desc="√252 환산"
        />
        <RiskCard
          label="VaR 95%"
          value={fmtPct(data.var95 !== null ? Number(data.var95) * 100 : null)}
          valueClass="text-red-400"
          desc="95% 신뢰수준 최대 손실"
        />
        <RiskCard
          label="최대 낙폭 (MDD)"
          value={fmtPct(data.maxDrawdown !== null ? Number(data.maxDrawdown) * 100 : null)}
          valueClass={mddColor}
          desc="고점 대비 최대 하락"
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <RiskCard
          label="Sharpe Ratio"
          value={fmtN(data.sharpeRatio)}
          desc="위험 대비 수익 (무위험 5% 기준)"
          valueClass={data.sharpeRatio !== null ? (Number(data.sharpeRatio) > 1 ? 'text-emerald-400' : Number(data.sharpeRatio) > 0 ? 'text-amber-400' : 'text-red-400') : 'text-gray-400'}
        />
        <RiskCard
          label="Calmar Ratio"
          value={fmtN(data.calmarRatio)}
          desc="연수익 / MDD"
          valueClass={data.calmarRatio !== null ? (Number(data.calmarRatio) > 1 ? 'text-emerald-400' : 'text-amber-400') : 'text-gray-400'}
        />
      </div>

      {/* Risk Metrics Guide */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">리스크 지표 해석 가이드</h2>
        <div className="grid gap-3 sm:grid-cols-2 text-xs text-gray-500">
          <div><span className="text-gray-400 font-medium">변동성</span> — 낮을수록 안정적. 15% 이하: 양호, 30% 이상: 고위험</div>
          <div><span className="text-gray-400 font-medium">VaR 95%</span> — "95% 확률로 하루에 이 이상 잃지 않음"</div>
          <div><span className="text-gray-400 font-medium">MDD</span> — 고점 대비 최대 하락폭. -20% 이하: 위험 주의</div>
          <div><span className="text-gray-400 font-medium">Sharpe</span> — 1 이상: 우수, 0~1: 보통, 0 미만: 부진</div>
          <div><span className="text-gray-400 font-medium">Calmar</span> — MDD 대비 수익. 1 이상: 양호</div>
          <div><span className="text-gray-400 font-medium">HHI</span> — 0.25 초과 시 집중 위험</div>
        </div>
      </div>

      {/* Historical Charts */}
      {chartData.length > 0 && (
        <>
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">연환산 변동성 추이</h2>
            <ResponsiveContainer width="100%" height={240}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
                <YAxis tickFormatter={(v) => `${v.toFixed(0)}%`} tick={{ fontSize: 11, fill: '#6b7280' }} axisLine={false} tickLine={false} />
                <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`]} contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }} />
                <Line type="monotone" dataKey="vol" name="연환산 변동성" stroke="#f59e0b" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">최대 낙폭 (MDD) 추이</h2>
            <ResponsiveContainer width="100%" height={240}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
                <YAxis tickFormatter={(v) => `${v.toFixed(0)}%`} tick={{ fontSize: 11, fill: '#6b7280' }} axisLine={false} tickLine={false} />
                <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`]} contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }} />
                <ReferenceLine y={0} stroke="#374151" />
                <Line type="monotone" dataKey="mdd" name="MDD" stroke="#ef4444" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="var95" name="VaR 95%" stroke="#8b5cf6" strokeWidth={1.5} dot={false} strokeDasharray="5 5" />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}

function RiskCard({ label, value, desc, valueClass = 'text-white' }: {
  label: string; value: string; desc?: string; valueClass?: string
}) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-2xl font-bold tabular-nums ${valueClass}`}>{value}</p>
      {desc && <p className="mt-1 text-xs text-gray-600">{desc}</p>}
    </div>
  )
}
function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
