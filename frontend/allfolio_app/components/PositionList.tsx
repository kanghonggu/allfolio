import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import type { Position } from '@/types/portfolio'

function fmt(n: number, d = 6) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: 0,
    maximumFractionDigits: d,
  })
}

function shortId(uuid: string) {
  return uuid.slice(0, 8).toUpperCase()
}

type Props = { positions: Position[] }

export default function PositionList({ positions }: Props) {
  if (positions.length === 0) {
    return <p className="text-sm text-fg-faint">포지션 없음</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-sm">
        <thead>
          <tr className="border-b border-line">
            <th className="py-2 pr-4 text-left"><Label size="sm" tone="faint">자산 ID</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">수량</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">원가단가</Label></th>
            <th className="py-2 pr-4 text-right"><Label size="sm" tone="faint">통화</Label></th>
            <th className="py-2 text-right"><Label size="sm" tone="faint">평가금액(KRW)</Label></th>
          </tr>
        </thead>
        <tbody>
          {positions.map((pos) => (
            <tr key={pos.assetId} className="border-b border-line-hair hover:bg-surface-muted">
              <td className="py-2.5 pr-4 font-mono text-[11.5px] tracking-[0.06em] text-fg-2">
                <span title={pos.assetId}>{shortId(pos.assetId)}</span>
              </td>
              <td className="py-2.5 pr-4 text-right"><Num className="text-[12px]">{fmt(pos.quantity)}</Num></td>
              <td className="py-2.5 pr-4 text-right">
                <Num className="text-[12px]">{fmt(pos.costBasis, 2)}</Num>
                <span className="ml-1 font-mono text-[9px] tracking-label text-fg-ghost">{pos.costMethod}</span>
              </td>
              <td className="py-2.5 pr-4 text-right font-mono text-[10px] tracking-label text-fg-3">{pos.currency}</td>
              <td className="py-2.5 text-right"><Num className="text-[12.5px]">{fmt(pos.krwValue, 0)}원</Num></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
