// components/monthly-report/PerformanceSummary.tsx
import Label from '@/components/ui/Label'
import InfoTip from '@/components/ui/InfoTip'
import { METRIC_HELP } from '@/lib/metric-help'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { Performance } from '@/types/monthly-report'
import { fmtPct, fmtKrw } from '@/lib/report-format'

const STANDARD_KEYS: Array<'3M' | 'YTD' | '1Y' | 'SI'> = ['3M', 'YTD', '1Y', 'SI']

export function PerformanceSummary({ perf }: { perf: Performance }) {
  const m = perf.month
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="성과 요약" />

      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
        <Kpi label="TWR (시간가중)" value={fmtPct(m.twr)} toneOf={m.twr} help={METRIC_HELP.twr} />
        <Kpi label="MWR (금액가중)" value={fmtPct(m.mwr)} toneOf={m.mwr} help={METRIC_HELP.mwr} />
        <Kpi label="기말 NAV" value={fmtKrw(m.endNav)} help={METRIC_HELP.endNav} />
        <Kpi label="순증(입출금)" value={fmtKrw(m.netFlow)} help={METRIC_HELP.netFlow} />
      </div>

      {m.benchmark && (
        <p className="mt-3 text-[12.5px] leading-relaxed text-fg-3">
          벤치마크 <span className="text-ink">{m.benchmark.label}</span> 대비 — 기간수익률{' '}
          <Num tone={dirTone(m.benchmark.periodReturn)} className="text-[12px]">{fmtPct(m.benchmark.periodReturn)}</Num>
          {' '}· 초과수익{' '}
          <Num tone={dirTone(m.benchmark.excessReturn)} className="text-[12px]">{fmtPct(m.benchmark.excessReturn)}</Num>
        </p>
      )}

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <div className="border-t-[1.5px] border-ink">
          <div className="border-b border-line py-2">
            <Label size="sm" tone="faint">표준기간 TWR</Label>
          </div>
          {STANDARD_KEYS.map((k) => (
            <div key={k} className="flex items-baseline justify-between border-b border-line-hair py-2">
              <span className="font-mono text-[11px] text-fg-3">{k}</span>
              <Num tone={dirTone(perf.standard[k]?.twr)} className="text-[12.5px]">
                {fmtPct(perf.standard[k]?.twr)}
              </Num>
            </div>
          ))}
        </div>
        <div className="self-start">
          <Kpi label="연환산 변동성" value={fmtPct(perf.volatility)} help={METRIC_HELP.monthlyVolatility} standalone />
        </div>
      </div>
    </section>
  )
}

function Kpi({
  label,
  value,
  toneOf,
  standalone = false,
  help,
}: {
  label: string
  value: string
  toneOf?: number | null
  standalone?: boolean
  help?: string
}) {
  const head = <Label size="sm" tone="faint">{label}</Label>
  return (
    <div className={standalone ? 'border border-line-soft bg-surface px-3.5 py-3' : 'bg-surface px-3.5 py-3'}>
      {help ? <InfoTip label={label} text={help}>{head}</InfoTip> : head}
      <Num tone={toneOf === undefined ? undefined : dirTone(toneOf)} className="mt-1 block text-[16px]">
        {value}
      </Num>
    </div>
  )
}
