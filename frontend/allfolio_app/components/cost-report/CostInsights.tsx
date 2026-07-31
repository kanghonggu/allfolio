// components/cost-report/CostInsights.tsx
import type { CostInsight } from '@/types/cost-report'

export function CostInsights({ items }: { items: CostInsight[] }) {
  if (!items || items.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">비용 인사이트</h2>
      <p className="text-xs text-gray-500">기존 집계의 사실형 하이라이트입니다(투자·재무 조언 아님).</p>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {items.map((it, i) => (
          <div key={`${it.label}-${i}`} className="rounded-xl border border-gray-700 bg-gray-900 p-4">
            <div className="text-xs text-gray-500">{it.label}</div>
            <div className="mt-1 text-lg font-semibold text-gray-100">{it.value}</div>
            {it.detail && <div className="mt-0.5 text-xs text-gray-400">{it.detail}</div>}
          </div>
        ))}
      </div>
    </section>
  )
}
