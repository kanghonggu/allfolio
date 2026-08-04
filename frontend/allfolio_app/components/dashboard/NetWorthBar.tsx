'use client'

interface NetWorthBarProps {
  total: number
  liquid: number
  illiquid: number
  debt: number
  // null = 30일 전 비교 기준 없음
  change30d: number | null
  changeRate30d: number | null
  currency?: string
}

function fmt(n: number) {
  if (Math.abs(n) >= 100_000_000)
    return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000)
    return `${Math.round(n / 10_000).toLocaleString('ko-KR')}만`
  return n.toLocaleString('ko-KR')
}

function fmtFull(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

export default function NetWorthBar({
  total, liquid, illiquid, debt, change30d, changeRate30d,
}: NetWorthBarProps) {
  // QA: 비교 기준 스냅샷이 없으면 0이 아니라 '비교 데이터 없음'으로 표기
  const hasBaseline = change30d !== null && changeRate30d !== null
  const isUp = (changeRate30d ?? 0) >= 0

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 px-6 py-5">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        {/* 왼쪽: 순자산 총액 */}
        <div>
          <p className="text-xs font-medium uppercase tracking-widest text-gray-500">
            총 순자산 (Net Worth)
          </p>
          <p className="mt-1 text-3xl font-bold tabular-nums text-white">
            {fmtFull(total)}
          </p>
          {hasBaseline ? (
            <p className={`mt-1 text-sm tabular-nums ${isUp ? 'text-emerald-400' : 'text-red-400'}`}>
              {isUp ? '+' : ''}{fmtFull(change30d!)} ({isUp ? '+' : ''}{changeRate30d!.toFixed(2)}%)
              <span className="ml-1 text-xs text-gray-600">30일 전 대비</span>
            </p>
          ) : (
            <p className="mt-1 text-sm text-gray-500">30일 전 비교 데이터 없음</p>
          )}
        </div>

        {/* 오른쪽: 구성 */}
        <div className="flex gap-6">
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-emerald-400">
              {fmt(liquid)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">투자자산</p>
          </div>
          <div className="text-gray-700 text-xl font-light">·</div>
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-blue-400">
              {fmt(illiquid)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">실물자산</p>
          </div>
          <div className="text-gray-700 text-xl font-light">·</div>
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-gray-500">
              -{fmt(debt)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">부채</p>
          </div>
        </div>
      </div>
    </div>
  )
}
