'use client'

import { useState, useMemo } from 'react'
import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useGoalApi } from '@/lib/useApi'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts'

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(n)
}
function fmtShort(n: number) {
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(0)}만`
  return n.toLocaleString('ko-KR')
}
function digitsOnly(s: string) { return s.replace(/[^\d]/g, '') }
function fmtComma(n: number) { return n > 0 ? Math.round(n).toLocaleString('ko-KR') : '' }

function MoneyInput({ label, value, onChange, hint }: {
  label: string; value: number; onChange: (v: number) => void; hint?: string
}) {
  return (
    <div>
      <label className="mb-1 block text-xs text-gray-400">{label}</label>
      <div className="relative">
        <input
          type="text" inputMode="numeric"
          placeholder="0"
          value={fmtComma(value)}
          onChange={e => { const d = digitsOnly(e.target.value); onChange(d ? parseInt(d) : 0) }}
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none pr-8"
        />
        <span className="absolute right-3 top-2 text-xs text-gray-500">원</span>
      </div>
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

function RateInput({ label, value, onChange, hint }: {
  label: string; value: number; onChange: (v: number) => void; hint?: string
}) {
  return (
    <div>
      <label className="mb-1 block text-xs text-gray-400">{label}</label>
      <div className="relative">
        <input
          type="number"
          step="0.1" min="0" max="100"
          value={value}
          onChange={e => onChange(parseFloat(e.target.value) || 0)}
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none pr-8"
        />
        <span className="absolute right-3 top-2 text-xs text-gray-500">%</span>
      </div>
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

interface SimPoint { year: number; value: number; label: string }

function simulate(
  initial: number,
  monthly: number,
  annualRate: number,
  years: number,
): SimPoint[] {
  const monthlyRate = annualRate / 100 / 12
  const points: SimPoint[] = []
  let value = initial

  for (let y = 0; y <= years; y++) {
    points.push({ year: y, value: Math.round(value), label: `${y}년` })
    // 1년치 복리 계산 (월 단위)
    for (let m = 0; m < 12; m++) {
      value = value * (1 + monthlyRate) + monthly
    }
  }
  return points
}

function monthsToGoal(initial: number, monthly: number, annualRate: number, target: number): number | null {
  if (initial >= target) return 0
  if (monthly <= 0 && annualRate <= 0) return null
  const monthlyRate = annualRate / 100 / 12
  let value = initial
  for (let m = 1; m <= 12 * 100; m++) {
    value = value * (1 + monthlyRate) + monthly
    if (value >= target) return m
  }
  return null
}

function reverseMonthly(initial: number, annualRate: number, target: number, months: number): number {
  // 목표액 = initial*(1+r)^n + monthly * ((1+r)^n - 1)/r
  // monthly = (target - initial*(1+r)^n) * r / ((1+r)^n - 1)
  const r = annualRate / 100 / 12
  if (months <= 0) return 0
  if (r === 0) return Math.max(0, (target - initial) / months)
  const factor = Math.pow(1 + r, months)
  const needed = (target - initial * factor) * r / (factor - 1)
  return Math.max(0, Math.round(needed))
}

export default function SimulatorPage() {
  const goalApi = useGoalApi()
  const { data: goalsData } = useQuery({
    queryKey: ['goals'],
    queryFn: () => goalApi!.list(),
    enabled: !!goalApi,
  })

  const [initial, setInitial] = useState(0)
  const [monthly, setMonthly] = useState(0)
  const [annualRate, setAnnualRate] = useState(7)
  const [years, setYears] = useState(20)
  const [selectedGoalId, setSelectedGoalId] = useState<string>('')

  // 목표 선택 시 자동으로 initial = 현재NAV, target 표시
  const selectedGoal = goalsData?.goals.find(g => g.id === selectedGoalId)
  const targetAmount = selectedGoal ? Number(selectedGoal.targetAmount) : 0

  // 목표의 남은 일수 → 개월수
  const goalMonths = selectedGoal?.daysRemaining != null
    ? Math.ceil(selectedGoal.daysRemaining / 30)
    : null

  const chartData = useMemo(() => simulate(initial, monthly, annualRate, years), [initial, monthly, annualRate, years])
  const finalValue = chartData[chartData.length - 1]?.value ?? 0

  const monthsNeeded = targetAmount > 0
    ? monthsToGoal(initial, monthly, annualRate, targetAmount)
    : null

  // 역산: 목표 기간 내 달성하려면 월 얼마?
  const suggestedMonthly = goalMonths && targetAmount > 0
    ? reverseMonthly(initial, annualRate, targetAmount, goalMonths)
    : null

  const totalDeposit = initial + monthly * 12 * years
  const totalInterest = finalValue - totalDeposit

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">투자 시뮬레이터</h1>
      </div>
      <p className="text-xs text-gray-500 -mt-4">복리 효과와 목표 달성 기간을 시뮬레이션합니다</p>

      {/* 목표 연동 */}
      {goalsData && goalsData.goals.length > 0 && (
        <div className="rounded-xl border border-violet-800 bg-violet-950/20 p-4">
          <p className="text-xs text-gray-400 mb-2">목표 트래커 연동 (선택)</p>
          <select
            value={selectedGoalId}
            onChange={e => {
              const g = goalsData.goals.find(g => g.id === e.target.value)
              setSelectedGoalId(e.target.value)
              if (g) setInitial(Math.round(Number(g.currentAmount)))
            }}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none"
          >
            <option value="">목표 선택 (선택 시 현재 자산 자동 입력)</option>
            {goalsData.goals.map(g => (
              <option key={g.id} value={g.id}>
                {g.name} — 목표 {fmt(Number(g.targetAmount))}
              </option>
            ))}
          </select>
          {selectedGoal && (
            <div className="mt-2 flex flex-wrap gap-3 text-xs text-gray-400">
              <span>목표: <strong className="text-violet-400">{fmt(targetAmount)}</strong></span>
              {goalMonths != null && <span>남은 기간: <strong className="text-amber-400">{goalMonths}개월</strong></span>}
              {suggestedMonthly != null && (
                <span>
                  목표 달성 월 적립액:
                  <strong className="text-emerald-400 ml-1">{fmt(suggestedMonthly)}</strong>
                </span>
              )}
            </div>
          )}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        {/* 입력 */}
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 space-y-4">
          <h2 className="text-sm font-semibold text-gray-300">시뮬레이션 조건</h2>
          <MoneyInput label="초기 투자금" value={initial} onChange={setInitial} hint="현재 총 자산 또는 시작 금액" />
          <MoneyInput label="월 적립액" value={monthly} onChange={setMonthly} hint="매달 추가 투자하는 금액" />
          <RateInput label="연간 수익률 (%)" value={annualRate} onChange={setAnnualRate} hint="과거 코스피 평균 약 8%, S&P500 약 10%" />

          <div>
            <label className="mb-1 block text-xs text-gray-400">투자 기간</label>
            <div className="flex items-center gap-3">
              <input
                type="range" min={1} max={40} value={years}
                onChange={e => setYears(parseInt(e.target.value))}
                className="flex-1 accent-blue-500"
              />
              <span className="w-16 text-right text-sm font-semibold text-white tabular-nums">{years}년</span>
            </div>
            <div className="mt-1 flex justify-between text-xs text-gray-600">
              <span>1년</span><span>40년</span>
            </div>
          </div>
        </div>

        {/* 결과 요약 */}
        <div className="space-y-4">
          <div className="rounded-xl border border-blue-800 bg-blue-950/20 p-5">
            <p className="text-xs text-gray-500">{years}년 후 예상 자산</p>
            <p className="mt-2 text-3xl font-bold tabular-nums text-blue-400">{fmt(finalValue)}</p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
              <p className="text-xs text-gray-500">총 납입액</p>
              <p className="mt-1.5 text-lg font-bold tabular-nums text-gray-200">{fmt(totalDeposit)}</p>
            </div>
            <div className="rounded-xl border border-emerald-900 bg-gray-900 p-4">
              <p className="text-xs text-gray-500">복리 수익</p>
              <p className="mt-1.5 text-lg font-bold tabular-nums text-emerald-400">
                {totalInterest > 0 ? `+${fmt(totalInterest)}` : fmt(totalInterest)}
              </p>
            </div>
          </div>

          {targetAmount > 0 && (
            <div className={`rounded-xl border p-4 ${monthsNeeded === 0
              ? 'border-emerald-800 bg-emerald-950/20'
              : monthsNeeded != null
                ? 'border-amber-800 bg-amber-950/20'
                : 'border-red-800 bg-red-950/20'}`}>
              <p className="text-xs text-gray-500">목표 {fmt(targetAmount)} 달성까지</p>
              {monthsNeeded === 0 ? (
                <p className="mt-1 text-lg font-bold text-emerald-400">이미 달성!</p>
              ) : monthsNeeded != null ? (
                <p className="mt-1 text-lg font-bold tabular-nums text-amber-400">
                  {Math.floor(monthsNeeded / 12)}년 {monthsNeeded % 12}개월
                </p>
              ) : (
                <p className="mt-1 text-sm text-red-400">현재 조건으로는 100년 내 달성 불가</p>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 차트 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">자산 성장 추이</h2>
        <ResponsiveContainer width="100%" height={300}>
          <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
            <defs>
              <linearGradient id="simGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
            <XAxis dataKey="label" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
            <YAxis
              tickFormatter={v => `₩${fmtShort(v)}`}
              tick={{ fontSize: 11, fill: '#6b7280' }}
              tickLine={false}
              axisLine={false}
              width={70}
            />
            <Tooltip
              formatter={(v: number) => [fmt(v), '예상 자산']}
              contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
              labelStyle={{ color: '#d1d5db' }}
            />
            {targetAmount > 0 && (
              <ReferenceLine
                y={targetAmount}
                stroke="#a855f7"
                strokeDasharray="6 3"
                label={{ value: `목표 ${fmtShort(targetAmount)}`, position: 'insideTopRight', fill: '#a855f7', fontSize: 11 }}
              />
            )}
            <Area
              type="monotone"
              dataKey="value"
              name="예상 자산"
              stroke="#3b82f6"
              strokeWidth={2}
              fill="url(#simGrad)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* 연도별 상세 테이블 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-x-auto">
        <div className="px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">연도별 예상 자산</h2>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3">연차</th>
              <th className="px-4 py-3 text-right">예상 자산</th>
              <th className="px-4 py-3 text-right">복리 수익</th>
              <th className="px-4 py-3 text-right">수익률</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {chartData.filter((_, i) => i % Math.max(1, Math.floor(years / 10)) === 0 || i === years).map(p => {
              const deposit = initial + monthly * 12 * p.year
              const interest = p.value - deposit
              const rate = deposit > 0 ? ((p.value / deposit - 1) * 100) : 0
              return (
                <tr key={p.year} className={`hover:bg-gray-800/40 ${targetAmount > 0 && p.value >= targetAmount ? 'bg-violet-900/10' : ''}`}>
                  <td className="px-6 py-3 font-medium text-gray-300">{p.year}년</td>
                  <td className="px-4 py-3 text-right tabular-nums text-blue-400">{fmt(p.value)}</td>
                  <td className="px-4 py-3 text-right tabular-nums text-emerald-400">
                    {interest > 0 ? `+${fmt(interest)}` : fmt(interest)}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                    {rate > 0 ? `+${rate.toFixed(1)}%` : `${rate.toFixed(1)}%`}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
