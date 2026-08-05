'use client'

import { useState, useMemo } from 'react'
import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useGoalApi } from '@/lib/useApi'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { Input, Select } from '@/components/ui/Field'

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
      <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">{label}</label>
      <div className="relative">
        <Input
          type="text" inputMode="numeric"
          placeholder="0"
          value={fmtComma(value)}
          onChange={e => { const d = digitsOnly(e.target.value); onChange(d ? parseInt(d) : 0) }}
          className="pr-8"
        />
        <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-fg-faint">원</span>
      </div>
      {hint && <p className="mt-1 text-xs text-fg-faint">{hint}</p>}
    </div>
  )
}

function RateInput({ label, value, onChange, hint }: {
  label: string; value: number; onChange: (v: number) => void; hint?: string
}) {
  return (
    <div>
      <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">{label}</label>
      <div className="relative">
        <Input
          type="number"
          step="0.1" min="0" max="100"
          value={value}
          onChange={e => onChange(parseFloat(e.target.value) || 0)}
          className="pr-8"
        />
        <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-fg-faint">%</span>
      </div>
      {hint && <p className="mt-1 text-xs text-fg-faint">{hint}</p>}
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
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="투자 시뮬레이터"
        meta="복리 효과와 목표 달성 기간을 시뮬레이션합니다"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 목표 연동 */}
        {goalsData && goalsData.goals.length > 0 && (
          <section className="mb-6 border border-line-card bg-surface-muted p-5">
            <SectionHeader label="목표 트래커 연동 (선택)" />
            <Select
              aria-label="시뮬레이션 연동 목표 선택"
              value={selectedGoalId}
              onChange={e => {
                const g = goalsData.goals.find(g => g.id === e.target.value)
                setSelectedGoalId(e.target.value)
                if (g) setInitial(Math.round(Number(g.currentAmount)))
              }}
            >
              <option value="">목표 선택 (선택 시 현재 자산 자동 입력)</option>
              {goalsData.goals.map(g => (
                <option key={g.id} value={g.id}>
                  {g.name} — 목표 {fmt(Number(g.targetAmount))}
                </option>
              ))}
            </Select>
            {selectedGoal && (
              <div className="mt-2.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-3">
                <span>목표 <Num className="font-medium text-ink">{fmt(targetAmount)}</Num></span>
                {goalMonths != null && <span>남은 기간 <Num className="font-medium text-warn">{goalMonths}개월</Num></span>}
                {suggestedMonthly != null && (
                  <span>
                    목표 달성 월 적립액 <Num className="font-medium text-ok">{fmt(suggestedMonthly)}</Num>
                  </span>
                )}
              </div>
            )}
          </section>
        )}

        <div className="grid gap-6 lg:grid-cols-2">
          {/* 입력 */}
          <div className="space-y-4 border border-line-card bg-surface-muted p-5">
            <SectionHeader label="시뮬레이션 조건" />
            <MoneyInput label="초기 투자금" value={initial} onChange={setInitial} hint="현재 총 자산 또는 시작 금액" />
            <MoneyInput label="월 적립액" value={monthly} onChange={setMonthly} hint="매달 추가 투자하는 금액" />
            <RateInput label="연간 수익률 (%)" value={annualRate} onChange={setAnnualRate} hint="과거 코스피 평균 약 8%, S&P500 약 10%" />

            <div>
              <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">투자 기간</label>
              <div className="flex items-center gap-3">
                <input
                  type="range" min={1} max={40} value={years}
                  onChange={e => setYears(parseInt(e.target.value))}
                  aria-label="투자 기간 (년)"
                  className="flex-1 accent-ink"
                />
                <Num className="w-16 text-right text-sm font-semibold text-ink">{years}년</Num>
              </div>
              <div className="mt-1 flex justify-between font-mono text-[9px] tracking-label text-fg-ghost">
                <span>1년</span><span>40년</span>
              </div>
            </div>
          </div>

          {/* 결과 요약 */}
          <div className="space-y-4">
            <div className="border border-ink bg-surface p-5">
              <Label size="sm" tone="faint">{years}년 후 예상 자산</Label>
              <Num className="mt-2 block text-[26px] font-semibold text-ink">{fmt(finalValue)}</Num>
            </div>

            <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft">
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">총 납입액</Label>
                <Num className="mt-1.5 block text-[15px] font-semibold text-fg-2">{fmt(totalDeposit)}</Num>
              </div>
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">복리 수익</Label>
                <Num className={`mt-1.5 block text-[15px] font-semibold ${totalInterest > 0 ? 'text-gain' : totalInterest < 0 ? 'text-loss' : 'text-fg-2'}`}>
                  {totalInterest > 0 ? `+${fmt(totalInterest)}` : fmt(totalInterest)}
                </Num>
              </div>
            </div>

            {targetAmount > 0 && (
              <div className={`border p-4 ${monthsNeeded === 0
                ? 'border-line-card bg-surface'
                : monthsNeeded != null
                  ? 'border-warn-line bg-warn-bg'
                  : 'border-danger bg-surface'}`}>
                <Label size="sm" tone="faint">목표 {fmt(targetAmount)} 달성까지</Label>
                {monthsNeeded === 0 ? (
                  <p className="mt-1 text-[15px] font-semibold text-ok">이미 달성</p>
                ) : monthsNeeded != null ? (
                  <Num className="mt-1 block text-[15px] font-semibold text-warn">
                    {Math.floor(monthsNeeded / 12)}년 {monthsNeeded % 12}개월
                  </Num>
                ) : (
                  <p className="mt-1 text-sm text-danger">현재 조건으로는 100년 내 달성 불가</p>
                )}
              </div>
            )}
          </div>
        </div>

        {/* 차트 */}
        <section className="mt-8">
          <SectionHeader label="자산 성장 추이" />
          <div className="border-t-[1.5px] border-ink pt-4">
            <ResponsiveContainer width="100%" height={300}>
              <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'var(--c-fg-faint)' }} tickLine={false} axisLine={{ stroke: 'var(--c-line)' }} />
                <YAxis
                  tickFormatter={v => `₩${fmtShort(v)}`}
                  tick={{ fontSize: 11, fill: 'var(--c-fg-faint)' }}
                  tickLine={false}
                  axisLine={false}
                  width={70}
                />
                <Tooltip
                  formatter={(v: number) => [fmt(v), '예상 자산']}
                  contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
                  labelStyle={{ color: 'var(--c-fg-3)' }}
                />
                {targetAmount > 0 && (
                  <ReferenceLine
                    y={targetAmount}
                    stroke="var(--c-fg-muted)"
                    strokeDasharray="6 3"
                    label={{ value: `목표 ${fmtShort(targetAmount)}`, position: 'insideTopRight', fill: 'var(--c-fg-muted)', fontSize: 11 }}
                  />
                )}
                <Area
                  type="monotone"
                  dataKey="value"
                  name="예상 자산"
                  stroke="var(--c-ink)"
                  strokeWidth={1.5}
                  fill="var(--c-ink)"
                  fillOpacity={0.06}
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </section>

        {/* 연도별 상세 테이블 */}
        <section className="mt-8">
          <SectionHeader label="연도별 예상 자산" />
          <div className="overflow-x-auto">
            <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-sm">
              <thead>
                <tr className="border-b border-line">
                  <th className="py-2 text-left"><Label size="sm" tone="faint">연차</Label></th>
                  <th className="py-2 text-right"><Label size="sm" tone="faint">예상 자산</Label></th>
                  <th className="py-2 text-right"><Label size="sm" tone="faint">복리 수익</Label></th>
                  <th className="py-2 text-right"><Label size="sm" tone="faint">수익률</Label></th>
                </tr>
              </thead>
              <tbody>
                {chartData.filter((_, i) => i % Math.max(1, Math.floor(years / 10)) === 0 || i === years).map(p => {
                  const deposit = initial + monthly * 12 * p.year
                  const interest = p.value - deposit
                  const rate = deposit > 0 ? ((p.value / deposit - 1) * 100) : 0
                  return (
                    <tr key={p.year} className={`border-b border-line-hair hover:bg-surface-muted ${targetAmount > 0 && p.value >= targetAmount ? 'bg-surface-muted' : ''}`}>
                      <td className="py-2.5 text-[13px] text-fg-2">{p.year}년</td>
                      <td className="py-2.5 text-right"><Num className="text-[12.5px] text-ink">{fmt(p.value)}</Num></td>
                      <td className="py-2.5 text-right">
                        <Num className={`text-[12.5px] ${interest > 0 ? 'text-gain' : interest < 0 ? 'text-loss' : 'text-fg-3'}`}>
                          {interest > 0 ? `+${fmt(interest)}` : fmt(interest)}
                        </Num>
                      </td>
                      <td className="py-2.5 text-right">
                        <Num className={`text-[12.5px] ${rate > 0 ? 'text-gain' : rate < 0 ? 'text-loss' : 'text-fg-3'}`}>
                          {rate > 0 ? `+${rate.toFixed(1)}%` : `${rate.toFixed(1)}%`}
                        </Num>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  )
}
