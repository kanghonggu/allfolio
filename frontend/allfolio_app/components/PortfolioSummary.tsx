import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import type { PortfolioSnapshot } from '@/types/portfolio'

function fmt(n: number, decimals = 2) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })
}

function pctColor(n: number) {
  if (n > 0) return 'text-gain'   // 한국식: 상승=빨강
  if (n < 0) return 'text-loss'
  return 'text-fg-muted'
}

function pctStr(n: number) {
  return `${n >= 0 ? '+' : ''}${fmt(n * 100)}%`
}

type Props = { snapshot: PortfolioSnapshot }

export default function PortfolioSummary({ snapshot }: Props) {
  const { performance: p, risk: r } = snapshot

  return (
    <div className="space-y-4">
      {/* 핵심 지표 */}
      <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft sm:grid-cols-4">
        <Card label="총 자산 (NAV)">
          <Num className="text-[17px] font-semibold text-ink">{fmt(p.nav)}</Num>
        </Card>

        <Card label="일간 수익률">
          <Num className={`text-[17px] font-semibold ${pctColor(p.dailyReturn)}`}>
            {pctStr(p.dailyReturn)}
          </Num>
        </Card>

        <Card label="누적 수익률">
          <Num className={`text-[17px] font-semibold ${pctColor(p.cumulativeReturn)}`}>
            {pctStr(p.cumulativeReturn)}
          </Num>
        </Card>

        {p.alpha !== null && (
          <Card label="알파">
            <Num className={`text-[17px] font-semibold ${pctColor(p.alpha ?? 0)}`}>
              {pctStr(p.alpha ?? 0)}
            </Num>
          </Card>
        )}
      </div>

      {/* 리스크 지표 */}
      <div className="border border-line-card bg-surface-muted p-4">
        <h3 className="m-0 mb-3 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
          리스크 지표
        </h3>
        <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm sm:grid-cols-4">
          <RiskRow label="변동성 (일)" value={pctStr(r.volatility)} />
          <RiskRow label="변동성 (연)" value={pctStr(r.annualizedVolatility)} />
          <RiskRow label="VaR 95%" value={pctStr(r.var95)} />
          <RiskRow label="최대 낙폭" value={pctStr(r.maxDrawdown)} color="text-loss" />
        </div>
      </div>

      <p className="text-right font-mono text-[10px] tracking-label text-fg-faint">기준일 {snapshot.date}</p>
    </div>
  )
}

function Card({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <div className="mt-1">{children}</div>
    </div>
  )
}

function RiskRow({
  label,
  value,
  color = 'text-ink',
}: {
  label: string
  value: string
  color?: string
}) {
  return (
    <div className="flex items-baseline justify-between">
      <span className="text-[12.5px] text-fg-3">{label}</span>
      <Num className={`text-[12.5px] font-medium ${color}`}>{value}</Num>
    </div>
  )
}
