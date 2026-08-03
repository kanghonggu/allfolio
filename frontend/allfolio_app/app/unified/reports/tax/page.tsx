'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'

// QA P3: 세율 마스터(tax_rates) 현행 세율 조회 — 하드코딩 15.4% 대신 실연동
interface EffectiveTaxRates {
  country: string
  asOf: string
  rates: Partial<Record<'DIVIDEND' | 'INTEREST' | 'DISTRIBUTION', number>>
}

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(n)
}
function digitsOnly(s: string) { return s.replace(/[^\d]/g, '') }
function fmtComma(n: number) { return n > 0 ? Math.round(n).toLocaleString('ko-KR') : '' }

function MoneyInput({ label, value, onChange, hint }: {
  label: string; value: number; onChange: (v: number) => void; hint?: string
}) {
  return (
    <div>
      <label className="mb-1 block text-xs text-gray-400">{label}</label>
      <input
        type="text" inputMode="numeric"
        placeholder="0"
        value={fmtComma(value)}
        onChange={e => { const d = digitsOnly(e.target.value); onChange(d ? parseInt(d) : 0) }}
        className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
      />
      {hint && <p className="mt-1 text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

function TaxCard({ label, base, tax, rate, desc, color = 'border-gray-700' }: {
  label: string; base: number; tax: number; rate: string; desc: string; color?: string
}) {
  return (
    <div className={`rounded-xl border ${color} bg-gray-900 p-5`}>
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-1 text-xs text-gray-600">{desc}</p>
      <div className="mt-3 space-y-1">
        <div className="flex justify-between text-xs text-gray-500">
          <span>과세 기준</span>
          <span className="tabular-nums">{fmt(base)}</span>
        </div>
        <div className="flex justify-between text-xs text-gray-500">
          <span>세율</span>
          <span>{rate}</span>
        </div>
        <div className="mt-2 flex justify-between border-t border-gray-800 pt-2">
          <span className="text-sm font-medium text-gray-300">예상 세액</span>
          <span className={`text-lg font-bold tabular-nums ${tax > 0 ? 'text-red-400' : 'text-gray-500'}`}>
            {tax > 0 ? fmt(tax) : '없음'}
          </span>
        </div>
      </div>
    </div>
  )
}

export default function TaxPage() {
  const { accessToken } = useAuth()
  // 세율 마스터 현행 배당·이자 원천징수율 (QA P3) — 조회 실패 시 15.4% 폴백
  const { data: taxRates } = useQuery({
    queryKey: ['tax-rates', 'effective', 'KR'],
    queryFn: async (): Promise<EffectiveTaxRates> => {
      const res = await fetch('/api/tax-rates/effective?country=KR', {
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
      })
      if (!res.ok) throw new Error('세율 조회 실패')
      return res.json()
    },
    enabled: !!accessToken,
    staleTime: 60 * 60 * 1000,
  })
  const dividendRate = (taxRates?.rates?.DIVIDEND ?? 15.4) / 100
  const interestRate = (taxRates?.rates?.INTEREST ?? 15.4) / 100

  // 해외주식
  const [overseasGain, setOverseasGain] = useState(0)
  // 국내주식 — 금융투자소득세는 2024년 말 폐지(미시행). 현행: 소액주주 장내 양도차익
  // 비과세, 대주주·장외 거래만 양도소득세 (QA P2: 세법 현행화)
  const [domesticGain, setDomesticGain] = useState(0)
  // 배당소득
  const [dividend, setDividend] = useState(0)
  // 이자소득
  const [interest, setInterest] = useState(0)

  // ── 세액 계산 ──────────────────────────────────────────────────

  // 해외주식 양도소득세: (차익 - 250만) × 22% (지방세 포함)
  const OVERSEAS_DEDUCTION = 2_500_000
  const overseasBase = Math.max(0, overseasGain - OVERSEAS_DEDUCTION)
  const overseasTax = Math.round(overseasBase * 0.22)

  // 국내주식 양도소득세(대주주 등 과세 대상): 기본공제 250만, 과세표준 3억 이하 22% /
  // 초과분 27.5% (지방세 포함)
  const DOMESTIC_DEDUCTION = 2_500_000
  const DOMESTIC_BRACKET = 300_000_000
  const domesticBase = Math.max(0, domesticGain - DOMESTIC_DEDUCTION)
  const domesticTax = Math.round(
    Math.min(domesticBase, DOMESTIC_BRACKET) * 0.22 +
    Math.max(0, domesticBase - DOMESTIC_BRACKET) * 0.275,
  )

  // 배당소득세 — 세율 마스터 연동(현행 15.4%). 금융소득 2,000만 초과 시 종합과세 안내
  const dividendTax = Math.round(dividend * dividendRate)
  const combinedFinancial = dividend + interest
  const isComprehensive = combinedFinancial > 20_000_000

  // 이자소득세 — 세율 마스터 연동
  const interestTax = Math.round(interest * interestRate)

  const totalTax = overseasTax + domesticTax + dividendTax + interestTax

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">세금 계산기</h1>
      </div>

      <div className="rounded-xl border border-amber-800 bg-amber-950/20 p-4 text-xs text-amber-400">
        2026년 현행 세법 기준입니다 (금융투자소득세는 2024년 말 폐지되어 적용되지 않습니다).
        예상 세액은 참고용입니다. 실제 납부 세액은 개인 상황(다른 소득, 공제 항목 등)에 따라 달라질 수 있으니 세무사 확인을 권장합니다.
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* 입력 */}
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 space-y-4">
          <h2 className="text-sm font-semibold text-gray-300">소득 입력</h2>

          <MoneyInput
            label="해외주식 양도차익 (연간 합계)"
            value={overseasGain}
            onChange={setOverseasGain}
            hint="미국주식, ETF 등 해외 자산 매도 차익의 합계"
          />
          <MoneyInput
            label="국내주식 양도차익 (연간 합계)"
            value={domesticGain}
            onChange={setDomesticGain}
            hint="소액주주 장내 양도차익은 비과세 — 대주주·장외 거래분만 입력"
          />
          <MoneyInput
            label="배당소득 (연간 합계)"
            value={dividend}
            onChange={setDividend}
            hint="국내·해외 주식 배당금 합계 (세전)"
          />
          <MoneyInput
            label="이자소득 (연간 합계)"
            value={interest}
            onChange={setInterest}
            hint="예금·채권 이자 합계 (세전)"
          />
        </div>

        {/* 결과 */}
        <div className="space-y-4">
          <div className="rounded-xl border border-blue-800 bg-blue-950/20 p-5">
            <p className="text-xs text-gray-500">예상 총 세액</p>
            <p className={`mt-2 text-3xl font-bold tabular-nums ${totalTax > 0 ? 'text-red-400' : 'text-gray-400'}`}>
              {totalTax > 0 ? fmt(totalTax) : '없음'}
            </p>
            {totalTax > 0 && (
              <p className="mt-1 text-xs text-gray-600">
                실효세율 약 {(totalTax / (overseasGain + domesticGain + dividend + interest) * 100 || 0).toFixed(1)}%
              </p>
            )}
          </div>

          <TaxCard
            label="해외주식 양도소득세"
            base={overseasBase}
            tax={overseasTax}
            rate="22% (소득세 20% + 지방세 2%)"
            desc={`연 250만원 기본공제 후 과세`}
            color={overseasTax > 0 ? 'border-red-900' : 'border-gray-700'}
          />
          <TaxCard
            label="국내주식 양도소득세 (대주주 등)"
            base={domesticBase}
            tax={domesticTax}
            rate="22% · 3억 초과분 27.5% (지방세 포함)"
            desc="기본공제 250만원 — 소액주주 장내 거래는 비과세"
            color={domesticTax > 0 ? 'border-red-900' : 'border-gray-700'}
          />
          <TaxCard
            label="배당소득세"
            base={dividend}
            tax={dividendTax}
            rate={`${(dividendRate * 100).toFixed(1)}% (세율 마스터 기준)`}
            desc="분리과세 기준"
            color={dividendTax > 0 ? 'border-red-900' : 'border-gray-700'}
          />
          <TaxCard
            label="이자소득세"
            base={interest}
            tax={interestTax}
            rate={`${(interestRate * 100).toFixed(1)}% (세율 마스터 기준)`}
            desc="분리과세 기준"
            color={interestTax > 0 ? 'border-red-900' : 'border-gray-700'}
          />

          {isComprehensive && (
            <div className="rounded-xl border border-orange-800 bg-orange-950/30 p-4 text-xs text-orange-400">
              배당+이자 합계 {fmt(combinedFinancial)}으로 금융소득 2,000만원을 초과합니다.
              <br />초과분은 <strong>종합과세</strong> 대상으로, 다른 소득과 합산하여 최대 49.5% 세율이 적용될 수 있습니다.
            </div>
          )}
        </div>
      </div>

      {/* 절세 가이드 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">절세 가이드</h2>
        <div className="grid gap-3 sm:grid-cols-2 text-xs text-gray-500">
          <div>
            <span className="font-medium text-gray-400">ISA 계좌</span> — 비과세 한도 200만원(서민형 400만원),
            초과 시 9.9% 분리과세. 해외주식도 편입 가능.
          </div>
          <div>
            <span className="font-medium text-gray-400">해외주식 손익 통산</span> — 해외주식 손실과 이익을
            같은 해에 통산해 양도세 절감. 손실 종목을 연말 전 매도하고 재매수.
          </div>
          <div>
            <span className="font-medium text-gray-400">연금저축·IRP</span> — 납입액 최대 900만원
            세액공제(16.5%), 운용 수익 과세이연.
          </div>
          <div>
            <span className="font-medium text-gray-400">250만원 공제 활용</span> — 해외주식 매도 차익이
            250만원 이하면 세금 없음. 소액 차익 실현 시 연간 250만원 한도 내 분산 매도.
          </div>
        </div>
      </div>
    </div>
  )
}
