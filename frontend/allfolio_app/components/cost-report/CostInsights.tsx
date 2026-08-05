// components/cost-report/CostInsights.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CostInsight } from '@/types/cost-report'

export function CostInsights({ items }: { items: CostInsight[] }) {
  if (!items || items.length === 0) return null
  const n = items.length
  // gap-px 격자에서 마지막 행에 빈 회색 칸이 남지 않도록 마지막 타일이 남은 폭을 채운다
  const lastSpan = [
    n % 2 === 1 ? 'col-span-2' : 'col-span-1',
    n % 3 === 1 ? 'md:col-span-3' : n % 3 === 2 ? 'md:col-span-2' : 'md:col-span-1',
  ].join(' ')
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="비용 인사이트" note="기존 집계의 사실형 하이라이트 (투자·재무 조언 아님)" />
      <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft md:grid-cols-3">
        {items.map((it, i) => (
          <div key={`${it.label}-${i}`} className={`bg-surface px-3.5 py-3 ${i === n - 1 ? lastSpan : ''}`}>
            <Label size="sm" tone="faint">{it.label}</Label>
            <Num className="mt-1 block text-[15px]">{it.value}</Num>
            {it.detail && <p className="mt-0.5 text-[11px] text-fg-faint">{it.detail}</p>}
          </div>
        ))}
      </div>
    </section>
  )
}
