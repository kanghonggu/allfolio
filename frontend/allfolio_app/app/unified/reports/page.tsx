'use client'

import { useState } from 'react'
import Link from 'next/link'
import PageHeader from '@/components/ui/PageHeader'
import Label from '@/components/ui/Label'

// 코드번호 체계: B-xx 기본 분석 · R-xx 기관급 리포트 · T-xx 도구
const REPORTS = [
  { href: '/unified/reports/summary',        group: 'basic', code: 'B-01', title: '포트폴리오 요약',      desc: 'NAV, 총 손익, 자산 수 및 유형별 현황' },
  { href: '/unified/reports/performance',    group: 'basic', code: 'B-02', title: '수익률 분석',          desc: '기간별 수익률(1W/1M/3M/YTD/1Y), 일별 NAV 시계열' },
  { href: '/unified/reports/allocation',     group: 'basic', code: 'B-03', title: '자산 배분',            desc: '유형·통화별 비중, 상위 보유 종목, HHI 집중도' },
  { href: '/unified/reports/risk',           group: 'basic', code: 'B-04', title: '리스크 분석',          desc: '변동성, VaR95, MDD, 샤프·칼마 비율' },
  { href: '/unified/reports/positions',      group: 'basic', code: 'B-05', title: '포지션 & 손익',        desc: '평균 매입가, 미실현 손익, 수익률 순위' },
  { href: '/unified/reports/benchmark',      group: 'basic', code: 'B-06', title: '벤치마크 비교',        desc: 'S&P500 / BTC / KOSPI 대비 알파 및 누적 수익률' },
  { href: '/unified/reports/networth',       group: 'basic', code: 'B-07', title: '순자산 추이',          desc: '총 자산 − 부채 = 순자산, 유형별 분해 및 NAV 이력' },
  { href: '/unified/reports/monthly',        group: 'basic', code: 'B-08', title: '월별 손익 정산',       desc: '월별 수익/손실, 최고·최저 달, 승률 통계' },
  { href: '/unified/reports/dividend',       group: 'basic', code: 'B-09', title: '배당금 보고서',        desc: '수령 배당금 합계, 월별 추이, 종목별 배당 이력' },
  { href: '/unified/reports/esg',            group: 'basic', code: 'B-10', title: 'ESG 점수',             desc: '환경·사회·지배구조 기반 포트폴리오 등급 및 자산별 분석' },
  { href: '/unified/reports/monthly-report', group: 'institutional', code: 'R-01', title: '월간 운용보고서',        desc: '성과·익스포저·계좌·입출금 분해, PDF 인쇄' },
  { href: '/unified/reports/returns',        group: 'institutional', code: 'R-02', title: '수익률 보고서 (TWR·MWR)', desc: '입출금 왜곡 제거(TWR), 체감 수익률(MWR), 입출금 효과 분해' },
  { href: '/unified/reports/dividend-report',group: 'institutional', code: 'R-03', title: '배당·이자 보고서',       desc: '세전·원천징수·세후, 종목·국가별 집계, PDF 인쇄' },
  { href: '/unified/reports/cost-report',    group: 'institutional', code: 'R-04', title: '비용 보고서',            desc: '수수료·거래세, 비용률·TER, 브로커별 매트릭스, PDF 인쇄' },
  { href: '/unified/reports/holdings-report',group: 'institutional', code: 'R-05', title: '월말 보유 명세서',       desc: '종목별 수량·평단·평가액·평가손익, 계좌·자산군별 소계, PDF 인쇄' },
  { href: '/unified/reports/cashflow-report',group: 'institutional', code: 'R-06', title: '현금흐름 보고서',        desc: '입금·출금·매매·배당·수수료, 순현금흐름, 월별 추이, PDF 인쇄' },
  { href: '/unified/reports/esg-screening',  group: 'institutional', code: 'R-07', title: 'ESG 스크리닝',           desc: 'E/S/G 점수, 종목별 등급, 배제리스트 위반 스크리닝, PDF 인쇄' },
  { href: '/unified/reports/tax',            group: 'tools', code: 'T-01', title: '세금 계산기',      desc: '해외주식 양도세, 금융투자소득세, 배당·이자소득세 예상액' },
  { href: '/unified/goals',                  group: 'tools', code: 'T-02', title: '목표 달성 트래커', desc: '재무 목표를 설정하고 현재 달성률을 추적' },
  { href: '/unified/simulator',              group: 'tools', code: 'T-03', title: '투자 시뮬레이터',  desc: '복리 기준 목표 도달 기간, 필요 월 적립액 역산' },
  { href: '/unified/advisor',                group: 'tools', code: 'T-04', title: 'AI 금융 상담',     desc: '포트폴리오 데이터 기반 상담 · 투자권유 아님' },
]

// QA P2: 21개 카드 평면 나열 → 추천 4개 + 카테고리별 접기
const FEATURED_HREFS = [
  '/unified/reports/summary',
  '/unified/reports/returns',
  '/unified/reports/monthly-report',
  '/unified/reports/networth',
]

const GROUPS: Array<{ key: string; title: string; note: string }> = [
  { key: 'basic',         title: '기본 분석',    note: '스냅샷 기반 · 자동 산출' },
  { key: 'institutional', title: '기관급 리포트', note: 'R-01 ~ R-07 · 월말 확정 후 재산출 · PDF 출력' },
  { key: 'tools',         title: '도구',         note: '사용자 입력 기반 계산' },
]

function ReportRow({ r }: { r: (typeof REPORTS)[number] }) {
  return (
    <Link
      href={r.href}
      className="grid grid-cols-[46px_1fr] items-baseline gap-x-4 gap-y-1 border-b border-line-hair py-3 transition-colors hover:bg-surface-muted sm:grid-cols-[46px_1.4fr_2.4fr_24px]"
    >
      <span className="font-mono text-[10px] tracking-[0.1em] text-fg-ghost">{r.code}</span>
      <span className="text-[14px] text-ink">{r.title}</span>
      <span className="col-start-2 text-[12.5px] leading-[1.65] text-fg-3 sm:col-start-3">{r.desc}</span>
      <span className="hidden text-right font-mono text-[11px] text-fg-ghost sm:block">→</span>
    </Link>
  )
}

export default function ReportsHub() {
  const [showAll, setShowAll] = useState(false)
  const featured = FEATURED_HREFS
    .map((href) => REPORTS.find((r) => r.href === href))
    .filter((r): r is (typeof REPORTS)[number] => !!r)

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="보고서"
        meta={`${REPORTS.length}종 · 기본 ${REPORTS.filter(r => r.group === 'basic').length} · 기관급 ${REPORTS.filter(r => r.group === 'institutional').length} · 도구 ${REPORTS.filter(r => r.group === 'tools').length}`}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 추천 보고서 */}
        <div className="mb-2 flex items-baseline justify-between border-b border-ink pb-2">
          <h2 className="m-0 font-serif text-[15.5px] font-medium">추천</h2>
          <Label size="sm" tone="faint">자주 찾는 보고서</Label>
        </div>
        <div className="mb-6">
          {featured.map((r) => <ReportRow key={r.href} r={r} />)}
        </div>

        <button
          onClick={() => setShowAll((v) => !v)}
          aria-expanded={showAll}
          className="mb-8 border border-line px-4 py-2 font-mono text-[10px] tracking-label text-fg-3 transition-colors hover:border-ink hover:text-ink"
        >
          {showAll ? '접기' : `전체 보고서 보기 (${REPORTS.length}종)`}
        </button>

        {showAll && GROUPS.map((g) => (
          <section key={g.key} className="mb-8">
            <div className="mb-1 flex flex-wrap items-baseline justify-between gap-2 border-b border-ink pb-2">
              <h2 className="m-0 font-serif text-[15.5px] font-medium">{g.title}</h2>
              <Label size="sm" tone="faint">{g.note}</Label>
            </div>
            {REPORTS.filter((r) => r.group === g.key).map((r) => <ReportRow key={r.href} r={r} />)}
          </section>
        ))}

        {showAll && (
          <p className="m-0 max-w-[90ch] text-[11.5px] leading-[1.8] text-fg-faint">
            기관급 리포트(R-01~R-07)는 월말 마감 후 확정 수치로 재산출되며, 확정 이전에는 잠정으로
            표기됩니다. 스냅샷 기간이 짧으면 일부 지표는 데이터 부족으로 표시될 수 있습니다.
          </p>
        )}
      </div>
    </div>
  )
}
