// app/unified/disclosures/page.tsx
'use client'

import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useDisclosureApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Panel from '@/components/ui/Panel'
import SectionHeader from '@/components/ui/SectionHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import DisclosureFeedPanel from '@/components/disclosure/DisclosureFeedPanel'
import InsiderTradePanel from '@/components/disclosure/InsiderTradePanel'

export default function DisclosuresPage() {
  const api = useDisclosureApi()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['disclosures', 'feed'],
    queryFn: () => api!.feed(),
    enabled: !!api,
    retry: false,
  })

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6">
      {/* 조회 구간을 헤더에 명시한다 — 기간 선택 컨트롤을 만들지 않기로 했으므로
          무엇을 보고 있는지는 글로 밝혀야 한다(설계 5절) */}
      <PageHeader title="공시" meta="최근 30일" />

      {isLoading && <LoadingState />}

      {isError && (
        <ErrorState
          message={error instanceof Error ? error.message : '공시를 불러오지 못했습니다.'}
          onRetry={() => refetch()}
        />
      )}

      {data && data.heldCount === 0 && (
        /* **"보유가 없다"와 "공시가 없다"를 갈라야 한다.** 전자는 행동이 필요한 상태고
           후자는 정상 상태다. 같은 문구로 처리하면 계좌를 안 연결한 사용자가
           "공시가 없구나"로 오해한다 */
        <EmptyState
          title="보유 종목이 없습니다"
          description="계좌를 연결하면 보유 종목의 공시를 모아서 보여드립니다."
          action={
            /* `<Link>`에 버튼 클래스를 직접 입힌다 — `<Link>` 안에 `<Button>`을 넣으면
               `<a>` 안에 `<button>`이 들어가 HTML5 중첩 규칙(interactive 안에 interactive)을
               어긴다. 레포의 다른 빈 상태 CTA(accounts·advisor)가 전부 이 형태다 */
            <Link
              href="/unified/accounts"
              className="border border-ink bg-ink px-4 py-2 text-sm text-white transition-colors hover:bg-fg-2"
            >
              계좌 연결
            </Link>
          }
        />
      )}

      {data && data.heldCount > 0 && (
        <div className="mt-6 space-y-6">
          <Panel>
            <SectionHeader label="보유종목 공시" />
            <DisclosureFeedPanel items={data.items} />
          </Panel>

          <Panel>
            <SectionHeader
              label="임원·주요주주 소유수량 변동"
              note="취득·처분 사유는 공시 원문에서 확인하세요"
            />
            <InsiderTradePanel trades={data.insiderTrades} />
          </Panel>
        </div>
      )}
    </div>
  )
}
