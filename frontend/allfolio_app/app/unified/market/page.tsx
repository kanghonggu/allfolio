// app/unified/market/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import { useRouter, useSearchParams } from 'next/navigation'
import { useMarketApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Label from '@/components/ui/Label'
import { ErrorState, LoadingState } from '@/components/ui/states'
import FxPanel from '@/components/market/FxPanel'
import { cx } from '@/lib/cx'

type TabKey = 'domestic' | 'overseas' | 'fx' | 'rates'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'domestic', label: '국내' },
  { key: 'overseas', label: '해외' },
  { key: 'fx', label: '환율' },
  { key: 'rates', label: '금리' },
]

export default function MarketPage() {
  const api = useMarketApi()
  const router = useRouter()
  const params = useSearchParams()

  // 네 탭이 쿼리 하나를 나눠 쓴다. 키가 고정이라 탭을 바꿔도 다시 안 부른다.
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['market', 'snapshot'],
    queryFn: () => api!.snapshot(),
    enabled: !!api,
    retry: false,
  })

  // **지수 플래그가 off면 탭 자체를 지운다.** "준비 중"을 띄우지 않는다 —
  // 없는 기능을 광고하는 셈이고, 눌러도 빈 화면이면 다음부터 아무도 안 누른다.
  // `data`가 아직 없을 때 탭을 다 보여주면 로딩 후 사라지며 깜빡이므로, 로딩 중에도 숨긴다.
  const indicesOn = data?.flags.indicesEnabled ?? false
  const visibleTabs = TABS.filter((t) => indicesOn || (t.key !== 'domestic' && t.key !== 'overseas'))

  // 탭 상태를 URL에 둔다 — 안 그러면 뒤로가기가 화면을 통째로 벗어난다.
  // 모르는 값이거나 지금 안 보이는 탭(플래그 off인데 `?tab=domestic`)이면 첫 탭으로 되돌린다.
  // 그대로 뒀다간 본문이 아무것도 안 그려진 빈 화면이 된다.
  const requested = params.get('tab') as TabKey | null
  const tab: TabKey = visibleTabs.some((t) => t.key === requested)
    ? (requested as TabKey)
    : visibleTabs[0]?.key ?? 'fx'

  // push가 아니라 replace다 — 탭을 눌러댈 때마다 히스토리가 쌓이면
  // 뒤로가기 한 번으로 화면을 못 벗어난다
  const selectTab = (key: TabKey) => {
    router.replace(`/unified/market?tab=${key}`, { scroll: false })
  }

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader className="px-5 pt-4 sm:px-7" title="시장" meta="지수 · 환율 · 금리" />

      <nav className="flex gap-1 border-b border-line-card px-5 sm:px-7" aria-label="시장 탭">
        {visibleTabs.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => selectTab(t.key)}
            aria-current={tab === t.key ? 'page' : undefined}
            className={cx(
              'px-3 py-2 font-mono text-[11px] tracking-label transition-colors',
              tab === t.key ? 'border-b-2 border-ink text-ink' : 'text-fg-faint hover:text-ink',
            )}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div className="px-5 py-5 sm:px-7">
        {isLoading && <LoadingState />}
        {isError && <ErrorState message="시장 데이터를 불러오지 못했습니다." onRetry={() => refetch()} />}
        {data && (
          <>
            {/* 지수 탭 본문은 Task 6이 채운다 */}
            {tab === 'domestic' && <Label size="sm" tone="faint">국내 지수</Label>}
            {tab === 'overseas' && <Label size="sm" tone="faint">해외 지수</Label>}
            {/* fx가 null이면(데이터 없음) 패널 안에서 빈 상태를 그린다 */}
            {tab === 'fx' && <FxPanel fx={data.fx} />}
            {tab === 'rates' && <Label size="sm" tone="faint">금리</Label>}
          </>
        )}
      </div>
    </div>
  )
}
