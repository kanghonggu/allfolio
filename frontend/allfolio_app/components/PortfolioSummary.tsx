import type { PortfolioSnapshot } from '@/types/portfolio'

function fmt(n: number, decimals = 2) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })
}

function pctColor(n: number) {
  if (n > 0) return 'text-red-500'   // 한국식: 상승=빨강
  if (n < 0) return 'text-blue-500'
  return 'text-gray-400'
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
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Card label="총 자산 (NAV)">
          <span className="text-2xl font-bold">{fmt(p.nav)}</span>
        </Card>

        <Card label="일간 수익률">
          <span className={`text-2xl font-bold ${pctColor(p.dailyReturn)}`}>
            {pctStr(p.dailyReturn)}
          </span>
        </Card>

        <Card label="누적 수익률">
          <span className={`text-2xl font-bold ${pctColor(p.cumulativeReturn)}`}>
            {pctStr(p.cumulativeReturn)}
          </span>
        </Card>

        {p.alpha !== null && (
          <Card label="알파">
            <span className={`text-2xl font-bold ${pctColor(p.alpha ?? 0)}`}>
              {pctStr(p.alpha ?? 0)}
            </span>
          </Card>
        )}
      </div>

      {/* 리스크 지표 */}
      <div className="rounded-lg border border-gray-700 bg-gray-800 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-400">리스크 지표</h3>
        <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm sm:grid-cols-4">
          <RiskRow label="변동성 (일)" value={pctStr(r.volatility)} />
          <RiskRow label="변동성 (연)" value={pctStr(r.annualizedVolatility)} />
          <RiskRow label="VaR 95%" value={pctStr(r.var95)} />
          <RiskRow label="최대 낙폭" value={pctStr(r.maxDrawdown)} color="text-blue-500" />
        </div>
      </div>

      <p className="text-right text-xs text-gray-500">기준일: {snapshot.date}</p>
    </div>
  )
}

function Card({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-gray-700 bg-gray-800 p-4">
      <p className="mb-1 text-xs text-gray-400">{label}</p>
      {children}
    </div>
  )
}

function RiskRow({
  label,
  value,
  color = 'text-white',
}: {
  label: string
  value: string
  color?: string
}) {
  return (
    <div className="flex justify-between">
      <span className="text-gray-400">{label}</span>
      <span className={`font-mono font-medium ${color}`}>{value}</span>
    </div>
  )
}
