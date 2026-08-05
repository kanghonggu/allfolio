import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import { fmtKrw } from '@/lib/report-format'
import type { CashflowReconciliation as Recon } from '@/types/cashflow-report'

export function CashflowReconciliation({ data }: { data: Recon }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="현금 조정표" />
      <table className="w-full border-t-[1.5px] border-ink text-[13px]">
        <tbody>
          <tr className="border-b border-line-hair">
            <td className="py-2.5 pr-2 text-fg-2">기초 현금</td>
            <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px]">{fmtKrw(data.openingBalance)}</Num></td>
          </tr>
          {data.changes.map((c) => (
            <tr key={c.type} className="border-b border-line-hair">
              <td className="py-2.5 pl-4 pr-2 text-fg-muted">{c.type}</td>
              <td className="py-2.5 pl-2 text-right"><Num tone={dirTone(c.amount)} className="text-[12.5px]">{fmtKrw(c.amount)}</Num></td>
            </tr>
          ))}
          <tr className="border-t-[1.5px] border-ink">
            <td className="py-2.5 pr-2 font-medium text-ink">기말 현금 (계산)</td>
            <td className="py-2.5 pl-2 text-right"><Num className="text-[13px] font-medium">{fmtKrw(data.closingCalculated)}</Num></td>
          </tr>
        </tbody>
      </table>

      {/* 정합 검증 */}
      <div className="mt-3">
        {!data.reconcilable ? (
          <p className="m-0 text-[11.5px] leading-relaxed text-fg-faint">
            과거 기간 — 실제 잔고 대조 생략 (기간 이후 현금활동 존재)
          </p>
        ) : data.reconciled ? (
          <p className="m-0 text-[11.5px] leading-relaxed text-ok">
            정합 — 계산 기말 = 실제 현금 ({fmtKrw(data.actualCash)})
          </p>
        ) : (
          <p className="m-0 text-[11.5px] leading-relaxed text-danger">
            불일치 — 실제 현금 {fmtKrw(data.actualCash)} · 차액{' '}
            <Num tone={dirTone(data.difference)} className="text-[11px]">{fmtKrw(data.difference)}</Num>{' '}
            (미포착 환전·이체·특이거래 추정)
          </p>
        )}
      </div>
    </section>
  )
}
