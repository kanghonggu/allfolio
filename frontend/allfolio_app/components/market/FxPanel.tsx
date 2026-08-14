// components/market/FxPanel.tsx
'use client'

import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useUnifiedApi } from '@/lib/useApi'
import type { FxSnapshot, FxQuoteView } from '@/types/market'
import { currencyLabel } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

/**
 * 값이 없으면 0이 아니라 대시다 — 0은 "그 값이 0원"이라는 뜻이 된다.
 * 하나은행은 소수 통화를 현찰·송금으로 취급하지 않아 네 값이 통째로 비는 일이 흔하다.
 */
function orDash(v: string | null): string {
  return v ?? '-'
}

export default function FxPanel({ fx }: { fx: FxSnapshot | null }) {
  const unified = useUnifiedApi()
  const [q, setQ] = useState('')

  // 내 보유 통화 — 계좌 목록에서 뽑는다. 시장 데이터와 섞지 않는 이유는
  // /api/market이 사용자별 데이터를 안 싣기 때문이고, 그건 캐시 가능하게 두려는 판단이다.
  // 키는 다른 화면과 같은 ['unified','accounts']다 — 계좌 화면을 거쳐 왔으면 재요청이 없다.
  const { data: accounts, isPending: accountsPending } = useQuery({
    queryKey: ['unified', 'accounts'],
    queryFn: () => unified!.accounts.list(),
    enabled: !!unified,
    retry: false,
  })

  // Set을 펼치지 않는다 — tsconfig에 target이 없어 ES5로 떨어지고 Set 순회가 컴파일 오류가 난다.
  // 계좌가 몇 개뿐이라 indexOf 중복 제거로 충분하다.
  const myCurrencies = useMemo(
    () =>
      (accounts ?? [])
        .map((a) => a.currency)
        .filter((c) => !!c && c !== 'KRW') // 원화는 환율이 없다
        .filter((c, i, arr) => arr.indexOf(c) === i),
    [accounts],
  )

  // 훅 뒤에서 이탈한다 — 위 훅들보다 먼저 return하면 훅 순서가 깨진다
  if (!fx) return <EmptyState title="환율 데이터가 아직 없습니다" />

  const byCode = new Map(fx.quotes.map((x) => [x.currency, x]))
  const mine = myCurrencies.map((c) => byCode.get(c)).filter((x): x is FxQuoteView => !!x)

  // 코드는 대소문자 무시, 한글 이름은 그대로 — 라벨이 한글이라 소문자화가 의미 없다
  const filtered = q.trim()
    ? fx.quotes.filter(
        (x) =>
          x.currency.toLowerCase().includes(q.toLowerCase()) ||
          currencyLabel(x.currency).includes(q),
      )
    : fx.quotes

  return (
    <div className="space-y-6">
      {/* 고시 도장 — 사용자가 은행 화면과 직접 대조하는 신뢰 장치다.
          **회차와 기준일에만 해당한다** — 값은 우리가 1단위로 정규화해서(JPY 100엔 고시 등)
          은행 화면 숫자와 다를 수 있다. 그걸 "고치지" 말 것. */}
      <div className="flex items-baseline justify-between gap-3">
        <Label size="sm" tone="faint">내 통화</Label>
        <span className="font-mono text-[10px] tracking-label text-fg-faint">
          하나은행 고시 · {fx.roundNo}회차 · {fx.baseDate}
        </span>
      </div>

      {/* 이 카드가 이 화면의 "내 숫자와의 연결"을 혼자 진다 — 지수 카드의 내 수익률 한 줄은
          데이터가 없어 뺐다(AF-106). 없애면 화면이 순수 시세 나열이 된다. */}
      {/* 계좌 조회가 아직 안 끝났는데 "없습니다"를 띄우면 짧게나마 거짓을 말한다 —
          이 조회는 시장 스냅샷이 온 뒤에야 시작하므로 순간이 아니라 한 왕복만큼 보인다 */}
      {accountsPending ? (
        <p className="text-[13px] text-fg-faint">보유 통화를 불러오는 중…</p>
      ) : mine.length === 0 ? (
        <p className="text-[13px] text-fg-2">외화 계좌가 없습니다.</p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {mine.map((x) => (
            <div key={x.currency} className="border border-line-card p-4">
              <div className="flex items-baseline justify-between gap-2">
                <span className="font-mono text-[12px]">{x.currency}</span>
                <span className="text-[11px] text-fg-faint">{currencyLabel(x.currency)}</span>
              </div>
              <div className="mt-2 flex items-baseline gap-2">
                <Num className="text-[18px]">{x.baseRate}</Num>
                {x.change && (
                  <Num tone={dirTone(Number(x.change))} className="text-[12px]">
                    {x.change} ({x.changeRate}%)
                  </Num>
                )}
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] text-fg-2">
                <dt>현찰 살 때</dt>
                <dd className="text-right"><Num>{orDash(x.cashBuy)}</Num></dd>
                <dt>현찰 팔 때</dt>
                <dd className="text-right"><Num>{orDash(x.cashSell)}</Num></dd>
                <dt>송금 보낼 때</dt>
                <dd className="text-right"><Num>{orDash(x.remitSend)}</Num></dd>
                <dt>송금 받을 때</dt>
                <dd className="text-right"><Num>{orDash(x.remitReceive)}</Num></dd>
              </dl>
              {/* 은행 화면은 100엔 고시다 — 안 적으면 우리 값이 틀린 걸로 보인다 */}
              {x.currency === 'JPY' && (
                <p className="mt-2 text-[10px] text-fg-faint">1엔 기준입니다 (은행 화면은 100엔 기준)</p>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="flex items-baseline justify-between gap-3 pt-2">
        <Label size="sm" tone="faint">전체 통화 {fx.quotes.length}</Label>
        {/* `Field` 프리미티브를 안 쓴다 — `id`가 필수이고 자식이 단일 엘리먼트여야 하는
            폼 필드용이라, 라벨 없는 검색 입력에는 안 맞는다.
            검색이 필수인 이유: 58통화가 알파벳 순이라 USD가 50번째쯤에 있다 */}
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="통화 검색"
          aria-label="통화 검색"
          className="border border-line-card bg-transparent px-2 py-1 font-mono text-[11px]"
        />
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="border-b border-line-card text-left text-fg-faint">
              <th className="py-1 font-normal">통화</th>
              <th className="py-1 font-normal">이름</th>
              <th className="py-1 text-right font-normal">매매기준율</th>
              <th className="py-1 text-right font-normal">전일대비</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((x) => (
              <tr key={x.currency} className="border-b border-line-card/50">
                <td className="py-1 font-mono">{x.currency}</td>
                <td className="py-1 text-fg-2">{currencyLabel(x.currency)}</td>
                <td className="py-1 text-right"><Num>{x.baseRate}</Num></td>
                <td className="py-1 text-right">
                  {/* change가 null이면 직전 기준일에 그 통화가 없었다는 뜻 — 0이 아니다 */}
                  {x.change ? (
                    <Num tone={dirTone(Number(x.change))}>
                      {x.change} ({x.changeRate}%)
                    </Num>
                  ) : (
                    <span className="text-fg-faint">-</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {filtered.length === 0 && (
        <p className="py-6 text-center text-[12px] text-fg-faint">검색 결과가 없습니다.</p>
      )}
    </div>
  )
}
