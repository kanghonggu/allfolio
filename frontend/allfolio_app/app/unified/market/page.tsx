// app/unified/market/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import { useRouter, useSearchParams } from 'next/navigation'
import { useMarketApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import { ErrorState, LoadingState } from '@/components/ui/states'
import IndexCards from '@/components/market/IndexCards'
import FxPanel from '@/components/market/FxPanel'
import RatePanel from '@/components/market/RatePanel'
import CommodityPanel from '@/components/market/CommodityPanel'
import { cx } from '@/lib/cx'

type TabKey = 'domestic' | 'overseas' | 'fx' | 'rates' | 'commodities'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'domestic', label: '국내' },
  { key: 'overseas', label: '해외' },
  { key: 'fx', label: '환율' },
  { key: 'rates', label: '금리' },
  { key: 'commodities', label: '원자재' },
]

export default function MarketPage() {
  const api = useMarketApi()
  const router = useRouter()
  const params = useSearchParams()

  // 다섯 탭이 쿼리 하나를 나눠 쓴다. 키가 고정이라 탭을 바꿔도 다시 안 부른다.
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['market', 'snapshot'],
    queryFn: () => api!.snapshot(),
    enabled: !!api,
    retry: false,
  })

  // **지수 플래그가 off면 탭 자체를 지운다.** "준비 중"을 띄우지 않는다 —
  // 없는 기능을 광고하는 셈이고, 눌러도 빈 화면이면 다음부터 아무도 안 누른다.
  // 플래그는 응답에 실려 오므로 `data` 전에는 알 수 없다. 그래서 탭 막대 자체를 응답 뒤에 그린다
  // (아래 렌더 참고) — 로딩 중에 환율/금리만 먼저 그리면 선택이 환율에 붙었다가
  // 응답이 오면서 국내가 생겨 선택이 국내로 튄다. 사용자 눈에는 내용이 저 혼자 바뀐 것으로 보인다.
  const indicesOn = data?.flags.indicesEnabled ?? false
  // 원자재는 플래그가 따로다(소스가 달라 약관 판단도 따로다 — MarketQueryProperties).
  // **`data.commodities`의 null 여부로 유도하지 않는다.** 그건 조회 결과고 플래그는 설정이다.
  const commoditiesOn = data?.flags.commoditiesEnabled ?? false
  const visibleTabs = TABS.filter((t) => {
    if (t.key === 'domestic' || t.key === 'overseas') return indicesOn
    if (t.key === 'commodities') return commoditiesOn
    return true
  })

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
      <PageHeader className="px-5 pt-4 sm:px-7" title="시장" meta="지수 · 환율 · 금리 · 원자재" />

      {/* **응답이 온 뒤에 한 번만 그린다.** 로딩 중에 미리 그리면 그때의 구성(환율·금리)이
          최종 구성과 달라 선택이 튄다. 본문이 이미 LoadingState라 탭 막대가 잠깐 없어도
          빈 화면으로 보이지 않는다. 반대로 로딩 중에 지수 탭을 미리 보여주는 것도 안 된다 —
          플래그가 off면 없어질 탭을 잠깐 광고하는 셈이다 */}
      {data && (
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
      )}

      <div className="px-5 py-5 sm:px-7">
        {isLoading && <LoadingState />}
        {isError && <ErrorState message="시장 데이터를 불러오지 못했습니다." onRetry={() => refetch()} />}
        {data && (
          <>
            {/* **`data.domestic ?? []`를 쓰지 말 것.** null은 플래그 off라 이 탭 자체가 없고,
                []는 켜져 있고 데이터가 없다는 뜻이다. 합치면 킬 스위치가 화면상 무력해진다 */}
            {tab === 'domestic' && data.domestic && <IndexCards quotes={data.domestic} />}
            {tab === 'overseas' && data.overseas && <IndexCards quotes={data.overseas} />}
            {/* fx가 null이면(데이터 없음) 패널 안에서 빈 상태를 그린다 */}
            {tab === 'fx' && <FxPanel fx={data.fx} />}
            {/* rates는 빈 배열로 온다 — 빈 상태는 패널 안에서 그린다 */}
            {tab === 'rates' && <RatePanel rates={data.rates} />}
            {/* 지수와 같은 관례다 — **`data.commodities ?? []`를 쓰지 말 것.**
                null은 플래그 off라 이 탭 자체가 없고, []는 켜져 있고 데이터가 없다는 뜻이라
                패널이 빈 상태를 그린다. 합치면 약관 때문에 감춘 탭이 빈 표로 노출된다 */}
            {tab === 'commodities' && data.commodities && <CommodityPanel quotes={data.commodities} />}
          </>
        )}
      </div>
    </div>
  )
}
