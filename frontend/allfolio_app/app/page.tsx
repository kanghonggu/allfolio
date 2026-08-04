'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState } from '@/components/ui/states'

// 정적 마케팅 수치 — 실제 서비스 현황 기준 (시안의 표본값 아님)
const PROOF_STATS = [
  { value: '21종', label: '산출 보고서 · 기본 10 + 기관급 7 + 도구 4' },
  { value: '2곳', label: '실시간 API 연동 · 한국투자증권 + Binance' },
  { value: '일 1회', label: '정기 동기화 · 수동 동기화 무제한' },
]

// 표본 프리뷰 카드 값 — "표본 데이터" 라벨과 함께 노출되는 예시 수치
const SAMPLE = {
  nav: '₩56,694,548',
  pl: '−7.15%',
  rows: [
    { label: '취득원가', value: '61,062,700', tone: undefined },
    { label: '평가손익', value: '−₩4,368,152', tone: 'loss' as const },
    { label: '보유 종목', value: '9종목', tone: undefined },
    { label: '연결 계좌', value: '3계좌', tone: undefined },
    { label: '상위 3종목 비중', value: '58.6%', tone: undefined },
  ],
}

const REPORT_HIGHLIGHTS = [
  { no: 'B-01', title: '포트폴리오 요약', desc: '평가금액과 취득원가, 평가손익을 계좌·자산군·종목 단위로 집계합니다.', metrics: 'NAV · 취득원가 · 평가손익 · 비중' },
  { no: 'R-02', title: '수익률 보고서 (TWR·MWR)', desc: '입출금 왜곡을 제거한 시간가중 수익률과 체감 수익률을 분리해 계산합니다.', metrics: 'TWR · MWR · 입출금 효과 분해' },
  { no: 'B-03', title: '자산 배분', desc: '자산군·통화별 비중과 상위 보유 종목, 집중도 지수를 표시합니다.', metrics: '배분 · HHI · 상위 5종목 집중도' },
  { no: 'B-04', title: '리스크 분석', desc: '일간 수익률로 변동성과 최대 낙폭, 위험 지표를 산출합니다.', metrics: '변동성 · VaR95 · MDD · 샤프' },
  { no: 'R-05', title: '월말 보유 명세서', desc: '종목별 수량·평균단가·평가액·평가손익을 계좌·자산군별 소계와 함께 확정합니다.', metrics: '로트 · 평균단가 · 소계' },
  { no: 'R-06', title: '현금흐름 보고서', desc: '입출금·매매·배당·수수료를 집계하고 기초·기말 잔액을 대사합니다.', metrics: '순현금흐름 · 월별 추이 · 잔액 대사' },
]

const INSTITUTIONS = [
  { name: '한국투자증권', kind: '증권 · 실시간 API' },
  { name: 'Binance', kind: '거래소 · 실시간 API' },
  { name: 'CSV 임포트', kind: '증권사 거래내역' },
  { name: '수동 등록', kind: '부동산 · 비상장 · 현금성' },
]

const PRINCIPLES = [
  { no: '01', title: '조회 전용 연결', desc: '출금·주문 권한이 포함된 API 키는 연결 단계에서 거부합니다.' },
  { no: '02', title: '동기화 스냅샷 보존', desc: '동기화 시점마다 평가액을 스냅샷으로 기록하여 수치의 이력을 추적할 수 있습니다.' },
  { no: '03', title: '기관 원장 대사', desc: '집계값과 기관 원장의 차이를 항목별로 표시하고, 차이가 크면 보고서를 보류합니다.' },
  { no: '04', title: '자문 아님', desc: '집계와 표시만 제공하며 종목 추천이나 매매 판단을 제시하지 않습니다.' },
]

export default function LandingPage() {
  const { authenticated, initialized } = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (initialized && authenticated) {
      router.replace('/unified')
    }
  }, [initialized, authenticated, router])

  if (!initialized || authenticated) {
    return <LoadingState label="확인 중" />
  }

  return (
    <div className="border border-line-card bg-surface">
      {/* Hero */}
      <section className="grid grid-cols-1 border-b border-ink lg:grid-cols-2">
        <div className="border-b border-line px-6 py-10 sm:px-9 lg:border-b-0 lg:border-r lg:py-14">
          <div className="mb-7 border-b border-line pb-3.5 font-mono text-[10px] tracking-wideLabel text-fg-muted">
            개인 투자자를 위한 자산 통합·보고 시스템
          </div>
          <h1 className="m-0 mb-6 font-serif text-[32px] font-medium leading-[1.28] tracking-[-0.02em] sm:text-[44px]">
            흩어진 계좌의 자산을
            <br />
            하나의 명세서로 정리합니다.
          </h1>
          <p className="m-0 mb-8 max-w-[44ch] text-[15.5px] leading-[1.85] text-fg-2">
            증권사·거래소·수동 등록 자산을 단일 원장으로 통합합니다. 동기화 시점마다 평가액을
            스냅샷으로 기록하고, 취득원가·평가손익·자산배분·집중도를 매 시점 기준으로 재계산합니다.
          </p>
          <div className="mb-10 flex flex-wrap items-center gap-4">
            <Link
              href="/register"
              className="border border-ink bg-ink px-6 py-3 text-sm text-white transition-colors hover:border-fg-2 hover:bg-fg-2"
            >
              계정 개설
            </Link>
            <Link
              href="/login"
              className="border border-ink bg-surface px-6 py-3 text-sm text-ink transition-colors hover:bg-surface-muted"
            >
              로그인
            </Link>
          </div>
          <div className="grid grid-cols-1 border-t border-line sm:grid-cols-3">
            {PROOF_STATS.map((p) => (
              <div key={p.value} className="py-4">
                <Num className="text-[22px]">{p.value}</Num>
                <div className="mt-1 text-xs text-fg-muted">{p.label}</div>
              </div>
            ))}
          </div>
        </div>

        {/* REPORT 01 프리뷰 (표본 데이터) */}
        <div className="bg-surface-muted px-6 py-9 sm:px-9">
          <div className="border border-line-card bg-surface">
            <div className="flex items-baseline justify-between border-b border-ink px-5 py-3">
              <Label size="sm" tone="muted">REPORT 01 — 포트폴리오 요약</Label>
              <Label size="sm" tone="muted">표본</Label>
            </div>
            <div className="grid grid-cols-2 border-b border-line-soft">
              <div className="border-r border-line-soft p-5">
                <Label size="sm" tone="muted">평가금액</Label>
                <Num className="mt-1.5 block text-[26px]">{SAMPLE.nav}</Num>
              </div>
              <div className="p-5">
                <Label size="sm" tone="muted">평가손익</Label>
                <Num tone="loss" className="mt-1.5 block text-[26px]">{SAMPLE.pl}</Num>
              </div>
            </div>
            <div className="px-5 pb-3.5 pt-1.5">
              {SAMPLE.rows.map((r) => (
                <div key={r.label} className="grid grid-cols-[1fr_auto] border-b border-line-hair py-2 text-[12.5px]">
                  <span className="text-fg-2">{r.label}</span>
                  <Num tone={r.tone} className="text-xs">{r.value}</Num>
                </div>
              ))}
            </div>
            <div className="border-t border-line-soft bg-surface-muted px-5 py-3 text-[11.5px] leading-[1.65] text-fg-3">
              주식 비중이 50%를 초과하면 집중도 경보 조건에 해당하며, 보고서에 자동 표기됩니다.
            </div>
          </div>
          <div className="mt-3.5">
            <Label size="sm" tone="faint">예시 화면 · 표본 계정 데이터</Label>
          </div>
        </div>
      </section>

      {/* 보고서 하이라이트 */}
      <section className="border-b border-ink">
        <div className="flex flex-wrap items-baseline justify-between gap-2 px-6 pb-3.5 pt-8 sm:px-9">
          <h2 className="m-0 font-serif text-[22px] font-medium">보고서 체계</h2>
          <Label size="sm" tone="faint">동기화 시점 기준 자동 산출 · 월말 확정 스냅샷 보존</Label>
        </div>
        <div className="grid grid-cols-1 border-t border-line sm:grid-cols-2 lg:grid-cols-3">
          {REPORT_HIGHLIGHTS.map((r) => (
            <div key={r.no} className="border-b border-line-soft px-6 py-6 sm:border-r sm:px-9">
              <div className="mb-2.5 font-mono text-[10px] tracking-label text-fg-faint">{r.no}</div>
              <div className="mb-1.5 text-[15px] font-medium">{r.title}</div>
              <div className="mb-3 text-[12.5px] leading-[1.75] text-fg-3">{r.desc}</div>
              <div className="font-mono text-[10px] tracking-[0.1em] text-fg-faint">{r.metrics}</div>
            </div>
          ))}
        </div>
      </section>

      {/* 연결 기관 / 데이터 취급 원칙 */}
      <section className="grid grid-cols-1 border-b border-ink lg:grid-cols-2">
        <div className="border-b border-line px-6 py-8 sm:px-9 lg:border-b-0 lg:border-r">
          <h2 className="m-0 mb-4 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
            연결 지원
          </h2>
          <div className="border-t border-line">
            {INSTITUTIONS.map((i) => (
              <div key={i.name} className="flex items-baseline justify-between border-b border-line-hair py-2.5 text-[13px]">
                <span>{i.name}</span>
                <Label size="sm" tone="faint">{i.kind}</Label>
              </div>
            ))}
          </div>
          <p className="mt-4 text-xs leading-relaxed text-fg-faint">
            그 외 기관은 CSV 거래내역 임포트 또는 수동 등록으로 반영할 수 있습니다.
          </p>
        </div>
        <div className="px-6 py-8 sm:px-9">
          <h2 className="m-0 mb-4 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
            데이터 취급 원칙
          </h2>
          <div className="border-t border-line">
            {PRINCIPLES.map((p) => (
              <div key={p.no} className="grid grid-cols-[22px_1fr] gap-3.5 border-b border-line-hair py-3.5">
                <span className="pt-0.5 font-mono text-[10px] text-fg-faint">{p.no}</span>
                <div>
                  <div className="mb-0.5 text-[13.5px] font-medium">{p.title}</div>
                  <div className="text-[12.5px] leading-[1.7] text-fg-3">{p.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Footer disclaimer */}
      <footer className="flex flex-col justify-between gap-5 px-6 py-7 sm:flex-row sm:px-9">
        <Label size="sm" tone="faint">ALLFOLIO</Label>
        <span className="max-w-[70ch] text-right font-mono text-[9.5px] leading-[1.9] tracking-[0.12em] text-fg-faint">
          본 서비스는 자산 현황의 집계·표시만을 제공하며 투자자문 또는 투자권유를 하지 않습니다.
          모든 수치는 사용자가 연결한 계좌의 조회 데이터에 근거하며, 기관 원장과의 차이는 대사
          화면에서 확인할 수 있습니다.
        </span>
      </footer>
    </div>
  )
}
