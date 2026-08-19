// components/disclosure/InsiderTradePanel.tsx
'use client'

import type { InsiderTradeItem } from '@/types/disclosure'
import Label from '@/components/ui/Label'
import { EmptyState } from '@/components/ui/states'

/**
 * **"매수"·"매도"·"장내매수"를 쓰지 않는다.** `elestock`에 변동사유 필드가 없어
 * (30개사 3,922행 전건 확인) 무상증자·스톡옵션 행사·상속과 장내매수를 구분할 수 없다.
 * 무상증자를 매수로 오표기하는 것은 금융 서비스에서 회복 불가능한 신뢰 손상이다
 * (백엔드 설계 원칙 3). 부호와 수량만 낸다.
 *
 * "주목 종목"·"매수 신호" 류 큐레이션 표현도 금지다 — 유사투자자문 소지가 있다(9절).
 */
const QTY = new Intl.NumberFormat('ko-KR')

/** 지분율은 NUMERIC(7,2)라 소수 2자리다. JSON.parse가 뒤 0을 버리므로 다시 고정한다 */
function pct(v: number | null): string {
  return v === null ? '—' : `${v.toFixed(2)}%`
}

function signed(v: number | null): string {
  if (v === null) return '—'
  // 0은 "변동 없음"이고 null은 "값 없음"이다. 둘을 같게 그리지 않는다
  return v > 0 ? `+${QTY.format(v)}` : QTY.format(v)
}

function who(t: InsiderTradeItem): string {
  // 등기 여부는 3-값이다 — 결측(null)을 "비등기"로 접으면 실측 125건이 거짓이 된다
  const parts: string[] = []
  if (t.isRegistered !== null) parts.push(t.isRegistered ? '등기임원' : '비등기임원')
  if (t.officerPosition) parts.push(t.officerPosition)
  if (t.majorHolderType) parts.push(t.majorHolderType)
  return parts.join(' ')
}

export default function InsiderTradePanel({ trades }: { trades: InsiderTradeItem[] }) {
  if (trades.length === 0) {
    return <EmptyState title="최근 30일간 소유수량 변동이 없습니다" />
  }

  // 종목별로 묶는다 — 백엔드가 접수일 내림차순으로 주므로 그룹 안 순서는 그대로 둔다
  const byStock = new Map<string, InsiderTradeItem[]>()
  for (const t of trades) {
    const key = t.stockCode ?? '—'
    const list = byStock.get(key)
    if (list) list.push(t)
    else byStock.set(key, [t])
  }

  return (
    <div>
      {Array.from(byStock.entries()).map(([stockCode, rows]) => (
        <div key={stockCode} className="border-b border-line-card last:border-b-0">
          <div className="px-4 pt-3">
            <Label size="sm" tone="faint">
              <span className="font-mono">{stockCode}</span>
            </Label>
          </div>
          <ul className="m-0 list-none p-0">
            {rows.map((t) => (
              <li key={t.rceptNo} className="px-4 py-2 text-[12px]">
                <div className="flex items-baseline justify-between gap-3">
                  {/* 색은 fg 기본(text-ink 상속)을 쓴다 — 'text-fg-1'은 이 레포에 정의되지
                      않은 토큰이라 조용히 무색으로 렌더된다(tailwind.config.ts 확인) */}
                  <a
                    href={t.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="min-w-0 truncate underline-offset-2 hover:underline"
                  >
                    {t.repror}
                    {who(t) && <span className="ml-1.5 text-[11px] text-fg-faint">{who(t)}</span>}
                  </a>
                  <span className="shrink-0 font-mono tabular-nums text-fg-faint">
                    {t.ownedQty === null ? '—' : QTY.format(t.ownedQty)}주
                    <span className="ml-2 text-fg-2">{signed(t.changeQty)}</span>
                    <span className="ml-2">{pct(t.ownedRate)}</span>
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}
