// components/holdings-report/HoldingsSummary.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone, type PnlTone } from '@/lib/format'
import type { HoldingsSummary as Summary } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'

export function HoldingsSummary({ summary }: { summary: Summary }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="요약" />
      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총평가액" value={fmtKrw(summary.totalValueKrw)} />
        <Card label="보유 종목 / 계좌" value={`${summary.holdingCount}종목 / ${summary.accountCount}계좌`} />
        <Card label="현금 비중" value={fmtPctScaled(summary.cashWeight)} />
        <Card label="평가손익 합계" value={fmtKrw(summary.unrealizedPnlKrw)} tone={dirTone(summary.unrealizedPnlKrw)} />
        {/* 5번째 타일 — gap-px 격자에서 빈 회색 칸이 남지 않도록 남은 폭을 채운다 */}
        <Card
          label="당월 실현손익"
          value={fmtKrw(summary.realizedPnlKrw)}
          tone={dirTone(summary.realizedPnlKrw)}
          className="sm:col-span-2 lg:col-span-4"
        />
      </div>
    </section>
  )
}

function Card({
  label,
  value,
  tone,
  className = '',
}: {
  label: string
  value: string
  tone?: PnlTone
  className?: string
}) {
  return (
    <div className={`bg-surface px-3.5 py-3 ${className}`}>
      <Label size="sm" tone="faint">{label}</Label>
      <Num tone={tone} className="mt-1 block text-[16px]">{value}</Num>
    </div>
  )
}
