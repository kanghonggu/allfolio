import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import type { Trade } from '@/types/trade'

function fmt(n: number, d = 2) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: d,
    maximumFractionDigits: d,
  })
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', {
    year: '2-digit', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function shortId(uuid: string) {
  return uuid.slice(0, 8).toUpperCase()
}

type Props = { trades: Trade[] }

export default function TradeTable({ trades }: Props) {
  if (trades.length === 0) {
    return <p className="text-sm text-fg-faint">거래 내역 없음</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] border-t-[1.5px] border-ink text-sm">
        <thead>
          <tr className="border-b border-line">
            <th className="py-2 pr-4 text-left"><Label size="sm" tone="faint">체결일시</Label></th>
            <th className="py-2 pr-4 text-left"><Label size="sm" tone="faint">자산 ID</Label></th>
            <th className="py-2 pr-4 text-left"><Label size="sm" tone="faint">구분</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">수량</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">단가</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">체결금액</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">수수료</Label></th>
            <th className="py-2 text-right"><Label size="sm" tone="faint">브로커</Label></th>
          </tr>
        </thead>
        <tbody>
          {trades.map((t) => (
            <tr key={t.tradeId} className="border-b border-line-hair hover:bg-surface-muted">
              <td className="py-2.5 pr-4">
                <Num className="text-[11.5px] text-fg-3">{fmtDate(t.executedAt)}</Num>
              </td>
              <td className="py-2.5 pr-4 font-mono text-[11.5px] tracking-[0.06em] text-fg-2">
                <span title={t.assetId}>{shortId(t.assetId)}</span>
              </td>
              <td className="py-2.5 pr-4">
                {/* 한국 시장 관례: 매수 빨강 / 매도 파랑 */}
                <span
                  className={`font-mono text-[10px] tracking-label ${
                    t.tradeType === 'BUY' ? 'text-gain' : 'text-loss'
                  }`}
                >
                  {t.tradeType === 'BUY' ? '매수' : '매도'}
                </span>
              </td>
              <td className="py-2.5 pr-4 text-right"><Num className="text-[12px]">{fmt(t.quantity, 6)}</Num></td>
              <td className="py-2.5 pr-4 text-right"><Num className="text-[12px]">{fmt(t.price)}</Num></td>
              <td className="py-2.5 pr-4 text-right"><Num className="text-[12.5px]">{fmt(t.quantity * t.price)}</Num></td>
              <td className="py-2.5 pr-4 text-right"><Num className="text-[11.5px] text-fg-faint">{fmt(t.fee)}</Num></td>
              <td className="py-2.5 text-right font-mono text-[10px] tracking-label text-fg-faint">
                {t.brokerType ?? '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
