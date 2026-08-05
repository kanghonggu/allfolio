'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input } from '@/components/ui/Field'

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

function MoneyInput({ id, label, value, onChange, hint }: {
  id: string; label: string; value: number; onChange: (v: number) => void; hint?: string
}) {
  return (
    <Field id={id} label={label} hint={hint}>
      <Input
        type="text" inputMode="numeric"
        placeholder="0"
        value={fmtComma(value)}
        onChange={e => { const d = digitsOnly(e.target.value); onChange(d ? parseInt(d) : 0) }}
      />
    </Field>
  )
}

function TaxCard({ label, base, tax, rate, desc, color = 'border-line-card' }: {
  label: string; base: number; tax: number; rate: string; desc: string; color?: string
}) {
  return (
    <div className={`border ${color} bg-surface p-4`}>
      <Label size="sm" tone="faint">{label}</Label>
      <p className="mt-1 text-[11px] text-fg-faint">{desc}</p>
      <div className="mt-3 space-y-1.5">
        <div className="flex items-baseline justify-between text-xs text-fg-3">
          <span>과세 기준</span>
          <Num>{fmt(base)}</Num>
        </div>
        <div className="flex items-baseline justify-between text-xs text-fg-3">
          <span>세율</span>
          <span className="font-mono text-[11px]">{rate}</span>
        </div>
        <div className="mt-2 flex items-baseline justify-between border-t-[1.5px] border-ink pt-2">
          <span className="text-[13px] font-medium text-fg-2">예상 세액</span>
          <Num className={`text-[15px] font-medium ${tax > 0 ? '' : 'text-fg-faint'}`}>
            {tax > 0 ? fmt(tax) : '없음'}
          </Num>
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
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-5 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] uppercase tracking-label text-fg-muted transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="세금 계산기"
        meta={<span>T-01 · 2026 현행 세법 기준</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        <div className="border border-warn-line bg-warn-bg px-3.5 py-2.5 text-xs leading-relaxed text-warn">
          2026년 현행 세법 기준입니다 (금융투자소득세는 2024년 말 폐지되어 적용되지 않습니다).
          예상 세액은 참고용입니다. 실제 납부 세액은 개인 상황(다른 소득, 공제 항목 등)에 따라 달라질 수 있으니 세무사 확인을 권장합니다.
        </div>

        <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-2">
          {/* 입력 */}
          <section className="border border-line-card bg-surface-muted p-5">
            <SectionHeader label="소득 입력" />
            <div className="space-y-3.5">
              <MoneyInput
                id="tax-overseas"
                label="해외주식 양도차익 (연간 합계)"
                value={overseasGain}
                onChange={setOverseasGain}
                hint="미국주식, ETF 등 해외 자산 매도 차익의 합계"
              />
              <MoneyInput
                id="tax-domestic"
                label="국내주식 양도차익 (연간 합계)"
                value={domesticGain}
                onChange={setDomesticGain}
                hint="소액주주 장내 양도차익은 비과세 — 대주주·장외 거래분만 입력"
              />
              <MoneyInput
                id="tax-dividend"
                label="배당소득 (연간 합계)"
                value={dividend}
                onChange={setDividend}
                hint="국내·해외 주식 배당금 합계 (세전)"
              />
              <MoneyInput
                id="tax-interest"
                label="이자소득 (연간 합계)"
                value={interest}
                onChange={setInterest}
                hint="예금·채권 이자 합계 (세전)"
              />
            </div>
          </section>

          {/* 결과 */}
          <div className="space-y-4">
            <div className="border border-ink bg-surface p-4">
              <Label size="sm" tone="faint">예상 총 세액</Label>
              <Num className={`mt-1.5 block text-[26px] font-medium ${totalTax > 0 ? '' : 'text-fg-faint'}`}>
                {totalTax > 0 ? fmt(totalTax) : '없음'}
              </Num>
              {totalTax > 0 && (
                <p className="mt-1 text-[11px] text-fg-faint">
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
              color={overseasTax > 0 ? 'border-ink' : 'border-line-card'}
            />
            <TaxCard
              label="국내주식 양도소득세 (대주주 등)"
              base={domesticBase}
              tax={domesticTax}
              rate="22% · 3억 초과분 27.5% (지방세 포함)"
              desc="기본공제 250만원 — 소액주주 장내 거래는 비과세"
              color={domesticTax > 0 ? 'border-ink' : 'border-line-card'}
            />
            <TaxCard
              label="배당소득세"
              base={dividend}
              tax={dividendTax}
              rate={`${(dividendRate * 100).toFixed(1)}% (세율 마스터 기준)`}
              desc="분리과세 기준"
              color={dividendTax > 0 ? 'border-ink' : 'border-line-card'}
            />
            <TaxCard
              label="이자소득세"
              base={interest}
              tax={interestTax}
              rate={`${(interestRate * 100).toFixed(1)}% (세율 마스터 기준)`}
              desc="분리과세 기준"
              color={interestTax > 0 ? 'border-ink' : 'border-line-card'}
            />

            {isComprehensive && (
              <div className="border border-warn-line bg-warn-bg px-3.5 py-2.5 text-xs leading-relaxed text-warn">
                배당+이자 합계 {fmt(combinedFinancial)}으로 금융소득 2,000만원을 초과합니다.
                <br />초과분은 <strong>종합과세</strong> 대상으로, 다른 소득과 합산하여 최대 49.5% 세율이 적용될 수 있습니다.
              </div>
            )}
          </div>
        </div>

        {/* 절세 가이드 */}
        <section className="mt-8">
          <SectionHeader label="절세 가이드" />
          <div className="grid grid-cols-1 gap-3 border-t-[1.5px] border-ink pt-3 text-xs leading-relaxed text-fg-faint sm:grid-cols-2">
            <div>
              <span className="font-medium text-fg-2">ISA 계좌</span> — 비과세 한도 200만원(서민형 400만원),
              초과 시 9.9% 분리과세. 해외주식도 편입 가능.
            </div>
            <div>
              <span className="font-medium text-fg-2">해외주식 손익 통산</span> — 해외주식 손실과 이익을
              같은 해에 통산해 양도세 절감. 손실 종목을 연말 전 매도하고 재매수.
            </div>
            <div>
              <span className="font-medium text-fg-2">연금저축·IRP</span> — 납입액 최대 900만원
              세액공제(16.5%), 운용 수익 과세이연.
            </div>
            <div>
              <span className="font-medium text-fg-2">250만원 공제 활용</span> — 해외주식 매도 차익이
              250만원 이하면 세금 없음. 소액 차익 실현 시 연간 250만원 한도 내 분산 매도.
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}
